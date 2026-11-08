package io.github.wowmancode.spartaremixer.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.view.Surface
import io.github.wowmancode.spartaremixer.core.Source
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

object Codecs {
    /** Asks the codec to run flat out; falls back for the few codecs that reject those keys. */
    fun configureFast(codec: MediaCodec, makeFormat: () -> MediaFormat, surface: Surface?, flags: Int) {
        try {
            val fast = makeFormat().apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
            codec.configure(fast, surface, null, flags)
        } catch (_: RuntimeException) {
            codec.reset()
            codec.configure(makeFormat(), surface, null, flags)
        }
    }
}

/**
 * One long-lived decoder feeding a texture. Moving to another point flushes and seeks instead of
 * rebuilding the codec, and moving forward just keeps decoding, which is what makes chops cheap.
 */
class FrameDecoder(
    context: Context,
    source: Source,
    private val renderer: FrameRenderer,
    handler: Handler,
) {
    val texture = renderer.createTexture()
    val matrix = FloatArray(16)
    var hasFrame = false
        private set
    var lastUsedTick = 0L

    private val name = source.name
    private val frames = Semaphore(0)
    private val info = MediaCodec.BufferInfo()
    private val extractor = MediaExtractor()
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var currentPts = -1L
    private var lastTargetLimit = Long.MAX_VALUE
    private var inputDone = false
    private var outputDone = false

    init {
        try {
            extractor.setDataSource(context, Uri.parse(source.uri), null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                lastTargetLimit = format.getLong(MediaFormat.KEY_DURATION) - LAST_FRAME_MARGIN_US
            }
            val st = SurfaceTexture(texture)
            surfaceTexture = st
            st.setOnFrameAvailableListener({ frames.release() }, handler)
            val out = Surface(st)
            surface = out
            val created = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = created
            Codecs.configureFast(created, { extractor.getTrackFormat(track) }, out, 0)
            created.start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Puts the first frame at or after [requestedUs] on the texture. */
    fun advanceTo(requestedUs: Long) {
        val target = requestedUs.coerceAtMost(maxOf(0L, lastTargetLimit))
        if (hasFrame && currentPts >= target && currentPts - target <= SAME_FRAME_US) return
        if (currentPts < 0 || target < currentPts || target - currentPts > SEEK_AHEAD_US) seek(target)
        val active = codec!!
        var idleSince = System.currentTimeMillis()
        while (!outputDone) {
            if (!inputDone) feed(active)
            val index = active.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index >= 0) {
                idleSince = System.currentTimeMillis()
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                val wanted = info.size > 0 && pts >= target
                active.releaseOutputBuffer(index, wanted)
                if (info.size > 0) currentPts = pts
                if (wanted) {
                    if (frames.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        surfaceTexture!!.updateTexImage()
                        surfaceTexture!!.getTransformMatrix(matrix)
                        hasFrame = true
                    }
                    return
                }
            } else {
                check(System.currentTimeMillis() - idleSince < STALL_MS) { "The video decoder stopped responding on $name" }
            }
        }
    }

    private fun feed(active: MediaCodec) {
        val index = active.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val size = extractor.readSampleData(active.getInputBuffer(index)!!, 0)
        if (size < 0) {
            active.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            active.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun seek(target: Long) {
        extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec!!.flush()
        frames.drainPermits()
        inputDone = false
        outputDone = false
        currentPts = -1
    }

    fun release() {
        try {
            codec?.stop()
        } catch (_: RuntimeException) {
            // Stopping can fail after a codec error; release below still frees it.
        }
        codec?.release()
        surface?.release()
        surfaceTexture?.release()
        renderer.deleteTexture(texture)
        extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 2_000L
        const val FRAME_TIMEOUT_MS = 2_500L
        const val STALL_MS = 15_000L
        const val SAME_FRAME_US = 34_000L
        const val SEEK_AHEAD_US = 2_000_000L
        const val LAST_FRAME_MARGIN_US = 70_000L
    }
}

/** Keeps a decoder per layer and source alive for the whole render, within device limits. */
class DecoderPool(
    private val context: Context,
    private val renderer: FrameRenderer,
    private val handler: Handler,
) {
    private val decoders = HashMap<String, FrameDecoder>()

    fun get(layer: Int, source: Source, tick: Long): FrameDecoder {
        val key = "$layer:${source.id}"
        val decoder = decoders[key] ?: create(key, source, tick)
        decoder.lastUsedTick = tick
        return decoder
    }

    private fun create(key: String, source: Source, tick: Long): FrameDecoder {
        if (decoders.size >= MAX_DECODERS) evictOldest(tick)
        while (true) {
            try {
                return FrameDecoder(context, source, renderer, handler).also { decoders[key] = it }
            } catch (e: Exception) {
                if (!evictOldest(tick)) {
                    throw IllegalStateException("This device cannot decode that many videos at once. Try fewer layers.", e)
                }
            }
        }
    }

    private fun evictOldest(tick: Long): Boolean {
        val oldest = decoders.entries.filter { it.value.lastUsedTick < tick }.minByOrNull { it.value.lastUsedTick } ?: return false
        oldest.value.release()
        decoders.remove(oldest.key)
        return true
    }

    fun release() {
        decoders.values.forEach { it.release() }
        decoders.clear()
    }

    private companion object {
        const val MAX_DECODERS = 8
    }
}
