package io.github.wowmancode.spartaremixer.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.wowmancode.spartaremixer.core.AudioMix
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.PcmReader
import io.github.wowmancode.spartaremixer.core.SAMPLE_RATE
import io.github.wowmancode.spartaremixer.core.usToFrames
import kotlin.math.roundToInt

/** Streams the live mix to the speaker; the audio clock drives the playhead. */
class AudioPreview(
    private val startUs: Long,
    clips: List<Clip>,
    private val readers: Map<String, PcmReader>,
    gains: FloatArray,
) {
    @Volatile private var running = true
    @Volatile private var mixState = clips to gains
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

    /** Applies edits made while playing, from the next audio block on. */
    fun update(clips: List<Clip>, gains: FloatArray) {
        mixState = clips to gains
    }

    private fun run() {
        val frames = 1024
        val mixed = FloatArray(frames * 2)
        val pcm = ShortArray(frames * 2)
        var frame = usToFrames(startUs)
        while (running) {
            val (clips, gains) = mixState
            AudioMix.mix(clips, { readers[it] }, frame, mixed, frames, gains)
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
