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
        layerGains: FloatArray? = null,
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
            val gain = layerGains?.getOrNull(clip.track) ?: 1f
            if (gain <= 0f) continue
            if (clip.keepLength && clip.semitones != 0) {
                mixStretched(clip, reader, clipStart, clipEnd, from, to, startFrame, gain, out)
                continue
            }
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
                    out[i + ch] += (a + (b - a) * t) * fade * gain
                }
            }
        }
        for (i in 0 until frames * 2) out[i] = softClip(out[i])
    }

    private fun mixStretched(
        clip: Clip, reader: PcmReader, clipStart: Long, clipEnd: Long,
        from: Long, to: Long, startFrame: Long, gain: Float, out: FloatArray,
    ) {
        val grains = PitchShift.grains(clip, reader)
        val ratio = clip.pitchRatio
        for (f in from until to) {
            val n = f - clipStart
            val k = min(n / PitchShift.HOP, grains.size - 1L).toInt()
            val p = n - k * PitchShift.HOP
            val fade = min(1.0, min((f - clipStart + 1) / FADE_FRAMES, (clipEnd - f) / FADE_FRAMES)).toFloat() * gain
            val i = ((f - startFrame) * 2).toInt()
            if (k == 0) {
                for (ch in 0..1) out[i + ch] += read(reader, grains[0] + ratio * p, ch) * fade
            } else {
                val rise = PitchShift.rise(p)
                val a = grains[k - 1] + ratio * (p + PitchShift.HOP)
                val b = grains[k] + ratio * p
                for (ch in 0..1) {
                    out[i + ch] += (read(reader, a, ch) * (1f - rise) + read(reader, b, ch) * rise) * fade
                }
            }
        }
    }

    private fun read(reader: PcmReader, pos: Double, channel: Int): Float {
        val whole = floor(pos).toLong()
        if (whole < 0 || whole + 1 >= reader.frames) return 0f
        val a = reader.sample(whole, channel)
        return a + (reader.sample(whole + 1, channel) - a) * (pos - whole).toFloat()
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

/**
 * Pitch shifting that keeps the length (WSOLA): overlapping grains are read at the pitch ratio
 * while their start points advance at normal speed. Each grain start is nudged to line up with
 * the waveform of the previous grain, which avoids the warbling of plain granular shifting.
 */
object PitchShift {
    const val GRAIN = 2048L
    const val HOP = GRAIN / 2
    private const val SEARCH = 128
    private const val SEARCH_STEP = 2
    private const val COMPARE_STEP = 8
    private const val MAX_CACHED = 128

    private data class Key(val sourceId: String, val inUs: Long, val outUs: Long, val semitones: Int)

    private val cache = java.util.concurrent.ConcurrentHashMap<Key, LongArray>()

    /** Hann crossfade weight for position [p] in the overlap; both grains always sum to one. */
    fun rise(p: Long): Float {
        val s = kotlin.math.sin(Math.PI * p / GRAIN).toFloat()
        return s * s
    }

    fun grains(clip: Clip, reader: PcmReader): LongArray {
        val key = Key(clip.sourceId, clip.inUs, clip.outUs, clip.semitones)
        cache[key]?.let { return it }
        if (cache.size >= MAX_CACHED) cache.clear()
        return compute(clip, reader).also { cache[key] = it }
    }

    private fun compute(clip: Clip, reader: PcmReader): LongArray {
        val ratio = clip.pitchRatio
        val inFrame = usToFrames(clip.inUs)
        val outFrame = usToFrames(clip.outUs)
        val latest = max(inFrame, outFrame - kotlin.math.ceil(ratio * GRAIN).toLong())
        val count = ((outFrame - inFrame) / HOP + 2).toInt()
        val points = (HOP / COMPARE_STEP).toInt()
        val tail = FloatArray(points)
        val starts = LongArray(count)
        starts[0] = inFrame.coerceAtMost(latest)
        for (k in 1 until count) {
            val nominal = inFrame + k * HOP
            for (j in 0 until points) {
                tail[j] = mono(reader, starts[k - 1] + ((HOP + j * COMPARE_STEP) * ratio).toLong())
            }
            var best = nominal
            var bestScore = -Double.MAX_VALUE
            var delta = -SEARCH
            while (delta <= SEARCH) {
                val candidate = nominal + delta
                var dot = 0.0
                var energy = 1e-9
                for (j in 0 until points) {
                    val v = mono(reader, candidate + (j * COMPARE_STEP * ratio).toLong())
                    dot += tail[j] * v
                    energy += v * v
                }
                val score = dot / sqrt(energy)
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
                delta += SEARCH_STEP
            }
            starts[k] = best.coerceIn(inFrame, latest)
        }
        return starts
    }

    private fun mono(reader: PcmReader, frame: Long): Float =
        (reader.sample(frame, 0) + reader.sample(frame, 1)) * 0.5f
}
