package io.github.wowmancode.spartaremixer.media

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.LruCache
import io.github.wowmancode.spartaremixer.core.AudioMix
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.PcmReader
import io.github.wowmancode.spartaremixer.core.SAMPLE_RATE
import io.github.wowmancode.spartaremixer.core.Source
import io.github.wowmancode.spartaremixer.core.usToFrames
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Fetches preview frames in the background; only the newest request matters. */
class FramePreviewer(private val context: Context, private val onFrame: (Bitmap?) -> Unit) {
    private data class Request(val source: Source, val slot: Long)

    private val thread = HandlerThread("preview-frames").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<Request?>(null)
    private val retrievers = HashMap<String, MediaMetadataRetriever>()
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun show(source: Source, sourceTimeUs: Long) {
        val request = Request(source, sourceTimeUs / SLOT_US)
        cache.get(key(request))?.let {
            pending.set(null)
            onFrame(it)
            return
        }
        if (pending.getAndSet(request) == null) worker.post(::work)
    }

    private fun key(r: Request) = "${r.source.id}:${r.slot}"

    private fun work() {
        val request = pending.getAndSet(null) ?: return
        val frame = try {
            val retriever = retrievers.getOrPut(request.source.id) {
                MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(request.source.uri)) }
            }
            val scale = PREVIEW_LONG_SIDE.toFloat() / maxOf(request.source.displayWidth, request.source.displayHeight)
            retriever.getScaledFrameAtTime(
                request.slot * SLOT_US,
                MediaMetadataRetriever.OPTION_CLOSEST,
                (request.source.displayWidth * scale).roundToInt(),
                (request.source.displayHeight * scale).roundToInt(),
            )
        } catch (_: RuntimeException) {
            null
        }
        if (frame != null) cache.put(key(request), frame)
        main.post { if (frame != null) onFrame(frame) }
    }

    fun release() {
        worker.post {
            retrievers.values.forEach { it.release() }
            retrievers.clear()
            thread.quitSafely()
        }
    }

    private companion object {
        const val SLOT_US = 33_333L
        const val PREVIEW_LONG_SIDE = 640
    }
}

/** Streams the live mix to the speaker; the audio clock drives the playhead. */
class AudioPreview(
    private val startUs: Long,
    private val clips: List<Clip>,
    private val readers: Map<String, PcmReader>,
) {
    @Volatile private var running = true
    private val minBuffer = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
    )
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBuffer, 8192))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    private val thread = Thread(::run, "preview-audio")

    fun start() {
        track.play()
        thread.start()
    }

    val positionUs: Long
        get() = startUs + track.playbackHeadPosition * 1_000_000L / SAMPLE_RATE

    private fun run() {
        val frames = 1024
        val mixed = FloatArray(frames * 2)
        val pcm = ShortArray(frames * 2)
        var frame = usToFrames(startUs)
        while (running) {
            AudioMix.mix(clips, { readers[it] }, frame, mixed, frames)
            for (i in pcm.indices) pcm[i] = (mixed[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            if (track.write(pcm, 0, pcm.size) < 0) break
            frame += frames
        }
    }

    fun stop() {
        running = false
        track.pause()
        track.flush()
        thread.join(500)
        track.release()
    }
}
