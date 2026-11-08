package io.github.wowmancode.spartaremixer.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import io.github.wowmancode.spartaremixer.core.AudioMix
import io.github.wowmancode.spartaremixer.core.PcmReader
import io.github.wowmancode.spartaremixer.core.Source
import io.github.wowmancode.spartaremixer.core.StereoResampler
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class MappedPcm(file: File) : PcmReader {
    private val samples: ShortBuffer
    override val frames: Long

    init {
        val length = file.length()
        samples = if (length < 4) {
            ShortBuffer.allocate(0)
        } else {
            PcmFileMapper.map(file, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        frames = samples.limit() / 2L
    }

    override fun sample(frame: Long, channel: Int): Float {
        if (frame < 0 || frame >= frames) return 0f
        return samples.get((frame * 2).toInt() + channel) / 32768f
    }
}

private object PcmFileMapper {
    fun map(file: File, length: Long): ByteBuffer =
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
        }
}

class LoadedAudio(val reader: PcmReader, val envelope: FloatArray)

object MediaLibrary {
    private const val MAX_PCM_BYTES = Int.MAX_VALUE.toLong() - 4
    private const val TIMEOUT_US = 10_000L

    fun probe(context: Context, uri: Uri): Source {
        val extractor = MediaExtractor()
        var width = 0
        var height = 0
        var duration = 0L
        var usableTracks = 0
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && width == 0) {
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    usableTracks++
                } else if (mime.startsWith("audio/")) {
                    usableTracks++
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    duration = max(duration, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
        } finally {
            extractor.release()
        }
        require(usableTracks > 0) { "No audio or video found in this file" }
        require(duration > 0) { "Could not read the length of this file" }
        if (width > 0 && rotationOf(context, uri).let { it == 90 || it == 270 }) {
            val w = width
            width = height
            height = w
        }
        return Source(UUID.randomUUID().toString(), uri.toString(), displayName(context, uri), duration, width, height)
    }

    private fun rotationOf(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: RuntimeException) {
            0
        } finally {
            retriever.release()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
                }
            } catch (_: RuntimeException) {
                // The fallback below is good enough for a label.
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "clip"
    }

    /** Decodes a source to the PCM cache when needed and maps it for random access. */
    fun loadAudio(context: Context, source: Source): LoadedAudio {
        val dir = File(context.cacheDir, "pcm").apply { mkdirs() }
        val file = File(dir, "${source.id}.pcm")
        if (!file.exists()) {
            val temp = File(dir, "${source.id}.tmp")
            decodeToPcm(context, Uri.parse(source.uri), temp)
            if (!temp.renameTo(file)) throw IllegalStateException("Could not save decoded audio")
        }
        val reader = MappedPcm(file)
        return LoadedAudio(reader, AudioMix.envelope(reader))
    }

    private fun decodeToPcm(context: Context, uri: Uri, target: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                break
            }
        }
        BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
            if (track < 0) {
                extractor.release()
                return
            }
            var written = 0L
            val two = ByteArray(4)
            var resampler: StereoResampler? = null
            var channels = 2
            var isFloat = false
            val emit = { l: Float, r: Float ->
                if (written < MAX_PCM_BYTES) {
                    val a = (l.coerceIn(-1f, 1f) * 32767f).roundToInt()
                    val b = (r.coerceIn(-1f, 1f) * 32767f).roundToInt()
                    two[0] = a.toByte()
                    two[1] = (a shr 8).toByte()
                    two[2] = b.toByte()
                    two[3] = (b shr 8).toByte()
                    out.write(two)
                    written += 4
                }
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        resampler = StereoResampler(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), emit)
                    } else if (index >= 0) {
                        if (info.size > 0) {
                            val active = resampler ?: StereoResampler(
                                format.getInteger(MediaFormat.KEY_SAMPLE_RATE), emit,
                            ).also {
                                resampler = it
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = if (isFloat) 4 else 2
                            val frames = info.size / (bytes * channels)
                            for (f in 0 until frames) {
                                val base = info.offset + f * bytes * channels
                                val second = if (channels > 1) bytes else 0
                                if (isFloat) {
                                    active.push(buffer.getFloat(base), buffer.getFloat(base + second))
                                } else {
                                    active.push(buffer.getShort(base) / 32768f, buffer.getShort(base + second) / 32768f)
                                }
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                codec.stop()
            } finally {
                codec.release()
                extractor.release()
            }
        }
    }

    fun deleteCached(context: Context, source: Source) {
        File(File(context.cacheDir, "pcm"), "${source.id}.pcm").delete()
    }

}
