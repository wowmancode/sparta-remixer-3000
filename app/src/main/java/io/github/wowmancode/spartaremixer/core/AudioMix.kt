package io.github.wowmancode.spartaremixer.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sqrt

const val SAMPLE_RATE = 44100
const val ENVELOPE_WINDOW_FRAMES = 882
const val ENVELOPE_WINDOW_US = ENVELOPE_WINDOW_FRAMES * 1_000_000L / SAMPLE_RATE

/** Decoded stereo audio at [SAMPLE_RATE]. */
interface PcmReader {
    val frames: Long
    fun sample(frame: Long, channel: Int): Float
}

fun usToFrames(us: Long): Long = (us * SAMPLE_RATE / 1_000_000.0).roundToLong()

object AudioMix {
    private const val FADE_FRAMES = 132.0 // 3 ms, removes clicks on hard chops

    /** Mixes every clip into interleaved stereo [out], starting at a timeline frame. */
    fun mix(
        clips: List<Clip>,
        readers: (String) -> PcmReader?,
        startFrame: Long,
        out: FloatArray,
        frames: Int,
    ) {
        out.fill(0f, 0, frames * 2)
        val endFrame = startFrame + frames
        for (clip in clips) {
            val reader = readers(clip.sourceId) ?: continue
            val clipStart = usToFrames(clip.startUs)
            val clipEnd = usToFrames(clip.endUs)
            val from = max(startFrame, clipStart)
            val to = min(endFrame, clipEnd)
            if (from >= to) continue
            val inFrame = clip.inUs * SAMPLE_RATE / 1_000_000.0
            val speed = clip.speed
            for (f in from until to) {
                val pos = inFrame + (f - clipStart) * speed
                val whole = floor(pos).toLong()
                if (whole < 0 || whole + 1 >= reader.frames) continue
                val t = (pos - whole).toFloat()
                val fade = min(1.0, min((f - clipStart + 1) / FADE_FRAMES, (clipEnd - f) / FADE_FRAMES)).toFloat()
                val i = ((f - startFrame) * 2).toInt()
                for (ch in 0..1) {
                    val a = reader.sample(whole, ch)
                    val b = reader.sample(whole + 1, ch)
                    out[i + ch] += (a + (b - a) * t) * fade
                }
            }
        }
        for (i in 0 until frames * 2) out[i] = softClip(out[i])
    }

    /** Transparent below 0.8, then eases towards full scale so stacked clips do not crackle. */
    fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val over = a - 0.8f
        return sign(x) * (0.8f + 0.2f * (over / (over + 0.2f)))
    }

    fun envelope(reader: PcmReader): FloatArray {
        val windows = (reader.frames / ENVELOPE_WINDOW_FRAMES).toInt()
        return FloatArray(windows) { w ->
            var sum = 0.0
            val base = w.toLong() * ENVELOPE_WINDOW_FRAMES
            for (i in 0 until ENVELOPE_WINDOW_FRAMES) {
                val l = reader.sample(base + i, 0)
                val r = reader.sample(base + i, 1)
                sum += (l * l + r * r) / 2.0
            }
            sqrt(sum / ENVELOPE_WINDOW_FRAMES).toFloat()
        }
    }
}

/** Streaming linear resampler to [SAMPLE_RATE] that works across decoder buffers. */
class StereoResampler(inputRate: Int, private val emit: (Float, Float) -> Unit) {
    private val step = inputRate.toDouble() / SAMPLE_RATE
    private var phase = 0.0
    private var prevL = 0f
    private var prevR = 0f
    private var started = false

    fun push(l: Float, r: Float) {
        if (!started) {
            prevL = l
            prevR = r
            started = true
            return
        }
        while (phase < 1.0) {
            val t = phase.toFloat()
            emit(prevL + (l - prevL) * t, prevR + (r - prevR) * t)
            phase += step
        }
        phase -= 1.0
        prevL = l
        prevR = r
    }
}
