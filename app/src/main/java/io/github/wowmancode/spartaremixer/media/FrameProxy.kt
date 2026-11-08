package io.github.wowmancode.spartaremixer.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.wowmancode.spartaremixer.core.Placement
import io.github.wowmancode.spartaremixer.core.Source
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A small JPEG for every 1/30 s of a video. Any frame opens in about a millisecond with no
 * keyframe seek and no codec, so preview cuts stay instant and any number of layers can play.
 * Export ignores this and always decodes the original.
 */
class FrameProxy(context: Context, val source: Source) {
    private val dir = File(context.cacheDir, "proxy").apply { mkdirs() }
    private val jpegFile = File(dir, "${source.id}.jpg")
    private val indexFile = File(dir, "${source.id}.idx")
    private val slotCount = (source.durationUs * FPS / 1_000_000L).toInt() + 2
    private val offsets = LongArray(slotCount) { -1 }
    private val sizes = IntArray(slotCount)
    private var reader: RandomAccessFile? = null

    @Volatile private var builtSlots = 0
    @Volatile var progress = 0f
        private set
    @Volatile var complete = false
        private set

    init {
        if (indexFile.exists() && jpegFile.exists()) loadIndex()
    }

    fun slotFor(sourceUs: Long): Int = (sourceUs * FPS / 1_000_000L).toInt().coerceIn(0, slotCount - 1)

    /** The nearest frame at or before a slot, or -1 when none has been built yet. */
    fun resolve(slot: Int): Int {
        var s = slot.coerceAtMost(builtSlots - 1)
        val lowest = max(0, s - MAX_GAP)
        while (s >= lowest) {
            if (offsets[s] >= 0) return s
            s--
        }
        return -1
    }

    fun read(slot: Int): ByteArray? {
        if (slot < 0 || offsets[slot] < 0) return null
        val file = reader ?: RandomAccessFile(jpegFile, "r").also { reader = it }
        val bytes = ByteArray(sizes[slot])
        file.seek(offsets[slot])
        file.readFully(bytes)
        return bytes
    }

    private fun loadIndex() {
        try {
            DataInputStream(indexFile.inputStream().buffered()).use { input ->
                check(input.readInt() == slotCount)
                for (i in 0 until slotCount) {
                    offsets[i] = input.readLong()
                    sizes[i] = input.readInt()
                }
            }
            builtSlots = slotCount
            progress = 1f
            complete = true
        } catch (_: Exception) {
            offsets.fill(-1)
            indexFile.delete()
        }
    }

    private fun saveIndex() {
        DataOutputStream(FileOutputStream(indexFile).buffered()).use { out ->
            out.writeInt(slotCount)
            for (i in 0 until slotCount) {
                out.writeLong(offsets[i])
                out.writeInt(sizes[i])
            }
        }
    }

    /** Decodes the whole video once; frames become usable as soon as they are written. */
    fun build(context: Context, cancelled: () -> Boolean) {
        if (complete || !source.hasVideo) {
            progress = 1f
            return
        }
        val scale = LONG_SIDE.toFloat() / max(source.displayWidth, source.displayHeight)
        val width = (source.displayWidth * scale).roundToInt().coerceAtLeast(2)
        val height = (source.displayHeight * scale).roundToInt().coerceAtLeast(2)
        val extractor = MediaExtractor()
        val callbacks = HandlerThread("proxy-frames").apply { start() }
        var egl: EglTarget? = null
        var renderer: FrameRenderer? = null
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        var codec: MediaCodec? = null
        var texture = 0
        try {
            FileOutputStream(jpegFile).use { out ->
                extractor.setDataSource(context, Uri.parse(source.uri), null)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                egl = EglTarget(null, width, height)
                val gl = FrameRenderer(width, height)
                renderer = gl
                texture = gl.createTexture()
                val frames = Semaphore(0)
                val st = SurfaceTexture(texture)
                surfaceTexture = st
                st.setOnFrameAvailableListener({ frames.release() }, Handler(callbacks.looper))
                val outSurface = Surface(st)
                surface = outSurface
                val active = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = active
                Codecs.configureFast(active, { extractor.getTrackFormat(track) }, outSurface, 0)
                active.start()

                val matrix = FloatArray(16)
                val info = MediaCodec.BufferInfo()
                val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val jpeg = ByteArrayOutputStream(1 shl 16)
                // glReadPixels returns the bottom row first, so draw upside down.
                val upsideDown = Placement(1f, -1f, 0f, 0f)
                var written = 0L
                var lastSlot = -1
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (cancelled()) return
                    if (!inputDone) {
                        val index = active.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(active.getInputBuffer(index)!!, 0)
                            if (size < 0) {
                                active.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                active.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = active.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index < 0) continue
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val slot = slotFor(info.presentationTimeUs)
                    val wanted = info.size > 0 && slot > lastSlot
                    active.releaseOutputBuffer(index, wanted)
                    if (!wanted || !frames.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) continue
                    st.updateTexImage()
                    st.getTransformMatrix(matrix)
                    gl.drawBlack()
                    gl.draw(texture, matrix, upsideDown, false)
                    pixels.clear()
                    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    pixels.position(0)
                    bitmap.copyPixelsFromBuffer(pixels)
                    jpeg.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                    jpeg.writeTo(out)
                    offsets[slot] = written
                    sizes[slot] = jpeg.size()
                    written += jpeg.size()
                    lastSlot = slot
                    builtSlots = slot + 1
                    progress = (slot + 1f) / slotCount
                }
                active.stop()
            }
            builtSlots = slotCount
            saveIndex()
            progress = 1f
            complete = true
        } finally {
            codec?.release()
            surface?.release()
            surfaceTexture?.release()
            renderer?.let {
                it.deleteTexture(texture)
                it.release()
            }
            egl?.release()
            callbacks.quitSafely()
            extractor.release()
        }
    }

    fun close() {
        try {
            reader?.close()
        } catch (_: java.io.IOException) {
            // Nothing useful can be done about a failed close of a read-only cache file.
        }
        reader = null
    }

    fun deleteFiles() {
        close()
        jpegFile.delete()
        indexFile.delete()
    }

    private companion object {
        const val FPS = 30L
        const val LONG_SIDE = 480
        const val JPEG_QUALITY = 60
        const val MAX_GAP = 120
        const val TIMEOUT_US = 2_000L
        const val FRAME_TIMEOUT_MS = 2_500L
    }
}
