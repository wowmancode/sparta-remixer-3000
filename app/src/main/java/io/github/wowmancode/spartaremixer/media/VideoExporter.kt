package io.github.wowmancode.spartaremixer.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.wowmancode.spartaremixer.core.AudioMix
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.Composition
import io.github.wowmancode.spartaremixer.core.Layer
import io.github.wowmancode.spartaremixer.core.PcmReader
import io.github.wowmancode.spartaremixer.core.SAMPLE_RATE
import io.github.wowmancode.spartaremixer.core.Source
import io.github.wowmancode.spartaremixer.core.Timeline
import io.github.wowmancode.spartaremixer.core.usToFrames
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ExportCancelled : Exception()

data class ExportSettings(val shortSide: Int = 720, val fps: Int = 30)

/** Renders the timeline to H.264/AAC in an MP4, using only MediaCodec, MediaMuxer and GLES. */
class VideoExporter(
    private val context: Context,
    private val clips: List<Clip>,
    private val layers: List<Layer>,
    private val sources: Map<String, Source>,
    private val readers: Map<String, PcmReader>,
    private val settings: ExportSettings,
    private val onProgress: (Float) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    private class AudioSample(val offset: Long, val size: Int, val timeUs: Long, val flags: Int)

    private val info = MediaCodec.BufferInfo()
    private val audioSamples = ArrayList<AudioSample>()
    private var nextAudio = 0
    private var audioBlob: RandomAccessFile? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var scratch = ByteBuffer.allocate(1 shl 16)

    fun export(output: File) {
        val total = Timeline.durationUs(clips)
        require(total > 0) { "The timeline is empty" }
        val blobFile = File.createTempFile("export_audio", ".aac", context.cacheDir)
        try {
            audioBlob = RandomAccessFile(blobFile, "rw")
            encodeAudio(total)
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encodeVideo(total)
            writeAudioUpTo(Long.MAX_VALUE)
            muxer?.stop()
            onProgress(1f)
        } finally {
            try {
                muxer?.release()
            } catch (_: RuntimeException) {
                // A failed export is reported by the original exception.
            }
            audioBlob?.close()
            blobFile.delete()
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw ExportCancelled()
    }

    private fun encodeAudio(totalUs: Long) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 15)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val totalFrames = usToFrames(totalUs)
        val mixChunk = FloatArray(8192 * 2)
        val gains = FloatArray(layers.size) { layers[it].volume }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                checkCancelled()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        input.clear()
                        input.order(ByteOrder.nativeOrder())
                        val frames = min(min(input.capacity() / 4, 8192).toLong(), totalFrames - frame).toInt()
                        val timeUs = frame * 1_000_000L / SAMPLE_RATE
                        if (frames <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            AudioMix.mix(clips, { readers[it] }, frame, mixChunk, frames, gains)
                            for (i in 0 until frames * 2) {
                                input.putShort((mixChunk[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                            }
                            codec.queueInputBuffer(index, 0, frames * 4, timeUs, 0)
                            frame += frames
                            onProgress(0.2f * frame / totalFrames)
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioFormat = codec.outputFormat
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val encoded = codec.getOutputBuffer(index)!!
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        encoded.get(bytes)
                        val blob = audioBlob!!
                        val offset = blob.length()
                        blob.seek(offset)
                        blob.write(bytes)
                        val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                        audioSamples += AudioSample(offset, info.size, info.presentationTimeUs, flags)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }
        checkNotNull(audioFormat) { "The audio encoder produced no format" }
    }

    private fun encodeVideo(totalUs: Long) {
        val (width, height) = Composition.outputSize(settings.shortSide, clips) { sources[it] }
        val makeFormat = {
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * settings.fps * 0.2).roundToInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        }
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height),
        ) ?: throw IllegalStateException("This device has no H.264 encoder for ${width}x$height")
        val encoder = MediaCodec.createByCodecName(encoderName)
        var inputSurface: Surface? = null
        var egl: EglTarget? = null
        var renderer: FrameRenderer? = null
        val callbackThread = HandlerThread("frame-available").apply { start() }
        try {
            Codecs.configureFast(encoder, makeFormat, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            egl = EglTarget(inputSurface)
            renderer = FrameRenderer(width, height)
            encoder.start()
            val totalTicks = max(1L, Timeline.tickAtOrAfter(totalUs, settings.fps))
            val pool = DecoderPool(context, renderer, Handler(callbackThread.looper))
            try {
                val hasVideo = { c: Clip -> sources[c.sourceId]?.hasVideo == true }
                for (tick in 0 until totalTicks) {
                    checkCancelled()
                    val timeUs = tick * 1_000_000L / settings.fps
                    renderer.drawBlack()
                    for (clip in Timeline.stack(clips, timeUs, layers.size, hasVideo)) {
                        val source = sources.getValue(clip.sourceId)
                        val decoder = pool.get(clip.track, source, tick)
                        decoder.advanceTo(clip.sourceAt(timeUs).coerceIn(clip.inUs, clip.outUs))
                        if (decoder.hasFrame) {
                            val placement = Composition.place(
                                source.displayWidth, source.displayHeight, width, height, layers[clip.track],
                            )
                            renderer.draw(decoder.texture, decoder.matrix, placement, clip.invert)
                        }
                    }
                    egl.present(tick * 1_000_000_000L / settings.fps)
                    drainEncoder(encoder, false)
                    onProgress(0.2f + 0.8f * (tick + 1) / totalTicks)
                }
            } finally {
                pool.release()
            }
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, true)
            encoder.stop()
        } finally {
            renderer?.release()
            egl?.release()
            inputSurface?.release()
            encoder.release()
            callbackThread.quitSafely()
        }
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val mux = muxer!!
        var idleSince = System.currentTimeMillis()
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                check(System.currentTimeMillis() - idleSince < DECODER_STALL_MS) { "The video encoder stopped responding" }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!muxerStarted) { "Encoder format changed twice" }
                videoTrack = mux.addTrack(encoder.outputFormat)
                audioTrack = mux.addTrack(audioFormat!!)
                mux.start()
                muxerStarted = true
            } else if (index >= 0) {
                idleSince = System.currentTimeMillis()
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0) {
                    check(muxerStarted) { "Encoder output before format" }
                    val data = encoder.getOutputBuffer(index)!!
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    mux.writeSampleData(videoTrack, data, info)
                    writeAudioUpTo(info.presentationTimeUs)
                }
                val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (done) return
            }
        }
    }

    /** Interleaves already encoded audio with the video as the video is produced. */
    private fun writeAudioUpTo(timeUs: Long) {
        if (!muxerStarted) return
        val mux = muxer!!
        val blob = audioBlob!!
        val sampleInfo = MediaCodec.BufferInfo()
        while (nextAudio < audioSamples.size && audioSamples[nextAudio].timeUs <= timeUs) {
            val sample = audioSamples[nextAudio++]
            if (scratch.capacity() < sample.size) scratch = ByteBuffer.allocate(ceil(sample.size * 1.5).toInt())
            blob.seek(sample.offset)
            blob.readFully(scratch.array(), 0, sample.size)
            scratch.position(0)
            scratch.limit(sample.size)
            sampleInfo.set(0, sample.size, sample.timeUs, sample.flags)
            mux.writeSampleData(audioTrack, scratch, sampleInfo)
            scratch.clear()
        }
    }

    private companion object {
        const val TIMEOUT_US = 5_000L
        const val DECODER_STALL_MS = 15_000L
    }
}
