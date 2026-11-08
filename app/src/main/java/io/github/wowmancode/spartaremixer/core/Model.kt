package io.github.wowmancode.spartaremixer.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

const val MAX_LAYERS = 6
const val DEFAULT_LAYERS = 3
const val MIN_CLIP_US = 20_000L

/** An imported media file. displayWidth/Height already account for rotation; 0 means audio only. */
data class Source(
    val id: String,
    val uri: String,
    val name: String,
    val durationUs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val hasVideo: Boolean get() = displayWidth > 0 && displayHeight > 0
}

/**
 * A piece of a source placed on the timeline; [track] is its layer index (0 is the front).
 * By default pitch is varispeed, like a sampler: raising it plays audio and video faster.
 * With [keepLength] the audio is pitch-shifted and the clip stays at normal speed.
 */
data class Clip(
    val id: Long,
    val sourceId: String,
    val track: Int,
    val startUs: Long,
    val inUs: Long,
    val outUs: Long,
    val semitones: Int = 0,
    val invert: Boolean = false,
    val keepLength: Boolean = false,
) {
    val pitchRatio: Double get() = 2.0.pow(semitones / 12.0)

    /** How fast the source plays on the timeline. */
    val speed: Double get() = if (keepLength) 1.0 else pitchRatio
    val durationUs: Long get() = ((outUs - inUs) / speed).roundToLong()
    val endUs: Long get() = startUs + durationUs

    /** Source time shown at a timeline position. */
    fun sourceAt(timelineUs: Long): Long = inUs + ((timelineUs - startUs) * speed).roundToLong()

    fun contains(timelineUs: Long): Boolean = timelineUs in startUs until endUs

    /** Returns both halves, or null when the split would leave an unusably short piece. */
    fun splitAt(timelineUs: Long, newId: Long): Pair<Clip, Clip>? {
        if (timelineUs - startUs < MIN_CLIP_US || endUs - timelineUs < MIN_CLIP_US) return null
        val cut = sourceAt(timelineUs)
        return copy(outUs = cut) to copy(id = newId, startUs = timelineUs, inUs = cut)
    }
}

/** Per-layer look and level. x and y move the center by fractions of the half frame; +y is down. */
data class Layer(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val volume: Float = 1f,
)

/** A quad in clip space; negative scales mirror the image. */
data class Placement(val scaleX: Float, val scaleY: Float, val centerX: Float, val centerY: Float)

object Composition {
    /** Fits the source inside the output, then applies the layer's scale, position and flips. */
    fun place(sourceWidth: Int, sourceHeight: Int, outWidth: Int, outHeight: Int, layer: Layer): Placement {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val outAspect = outWidth.toFloat() / outHeight
        val fitX = if (sourceAspect > outAspect) 1f else sourceAspect / outAspect
        val fitY = if (sourceAspect > outAspect) outAspect / sourceAspect else 1f
        return Placement(
            fitX * layer.scale * (if (layer.flipH) -1f else 1f),
            fitY * layer.scale * (if (layer.flipV) -1f else 1f),
            layer.x,
            -layer.y,
        )
    }

    /** 16:9 frame, portrait when the earliest video clip is portrait. */
    fun outputSize(shortSide: Int, clips: List<Clip>, source: (String) -> Source?): Pair<Int, Int> {
        val first = clips.sortedBy { it.startUs }.firstNotNullOfOrNull { c -> source(c.sourceId)?.takeIf { it.hasVideo } }
        val short = shortSide and 1.inv()
        val long = (shortSide * 16.0 / 9.0).roundToLong().toInt() and 1.inv()
        return if (first != null && first.displayHeight > first.displayWidth) short to long else long to short
    }
}

/** Grid sizes in beats, shared by snapping and slicing. */
object Divisions {
    val all: List<Pair<Double, String>> = listOf(
        4.0 to "1 bar",
        2.0 to "2 beats",
        1.0 to "1 beat",
        0.5 to "1/2 beat",
        0.25 to "1/4 beat",
        1.0 / 6.0 to "1/6 beat",
        0.125 to "1/8 beat",
    )

    fun name(beats: Double): String = all.firstOrNull { abs(it.first - beats) < 1e-9 }?.second ?: "1 beat"
}

object Timeline {
    fun durationUs(clips: List<Clip>): Long = clips.maxOfOrNull { it.endUs } ?: 0L

    /** The clips on screen at a time, back to front; within a layer the latest start wins. */
    fun stack(clips: List<Clip>, timeUs: Long, layerCount: Int, hasVideo: (Clip) -> Boolean): List<Clip> {
        val winners = arrayOfNulls<Clip>(layerCount)
        for (clip in clips) {
            if (clip.track !in 0 until layerCount || !clip.contains(timeUs) || !hasVideo(clip)) continue
            val current = winners[clip.track]
            if (current == null || clip.startUs > current.startUs) winners[clip.track] = clip
        }
        return winners.reversed().filterNotNull()
    }

    fun beatUs(bpm: Double): Double = 60_000_000.0 / bpm

    fun snap(timeUs: Long, bpm: Double, beats: Double): Long {
        val step = beatUs(bpm) * beats
        return max(0L, (timeUs / step).roundToLong().let { (it * step).roundToLong() })
    }

    /** The first output frame index at or after a time. */
    fun tickAtOrAfter(timeUs: Long, fps: Int): Long = ceil(timeUs * fps / 1_000_000.0).toLong()
}

object AutoCut {
    const val MAX_SLICES = 1024

    /** Chops a clip into contiguous slices every [beats] beats, counted from the clip start. */
    fun sliceOnGrid(clip: Clip, bpm: Double, beats: Double, nextId: () -> Long): List<Clip> {
        val step = Timeline.beatUs(bpm) * beats
        if (step < MIN_CLIP_US) return listOf(clip)
        val result = ArrayList<Clip>()
        var rest = clip
        var n = 1
        while (result.size < MAX_SLICES - 1) {
            val cutAt = clip.startUs + (step * n++).roundToLong()
            if (cutAt >= rest.endUs) break
            val pair = rest.splitAt(cutAt, nextId()) ?: break
            result += pair.first
            rest = pair.second
        }
        result += rest
        return result
    }

    /**
     * Finds the audible source ranges in an RMS envelope. Silences shorter than [minSilenceUs]
     * are kept, and each audible range gets [padUs] on both sides so consonants are not clipped.
     */
    fun audibleRanges(
        envelope: FloatArray,
        windowUs: Long,
        thresholdDb: Double,
        minSilenceUs: Long = 150_000,
        padUs: Long = 15_000,
    ): List<LongRange> {
        val threshold = 10.0.pow(thresholdDb / 20.0)
        val ranges = ArrayList<LongRange>()
        var start = -1
        var lastLoud = -1
        val minSilenceWindows = max(1L, minSilenceUs / windowUs).toInt()
        for (i in envelope.indices) {
            if (envelope[i] >= threshold) {
                if (start < 0) start = i
                else if (i - lastLoud - 1 >= minSilenceWindows) {
                    ranges += (start * windowUs)..((lastLoud + 1) * windowUs)
                    start = i
                }
                lastLoud = i
            }
        }
        if (start >= 0) ranges += (start * windowUs)..((lastLoud + 1) * windowUs)
        val total = envelope.size * windowUs
        return ranges.map { max(0L, it.first - padUs)..min(total, it.last + padUs) }
            .fold(ArrayList<LongRange>()) { merged, r ->
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last) {
                    merged[merged.lastIndex] = last.first..max(last.last, r.last)
                } else {
                    merged += r
                }
                merged
            }
    }

    /** Rebuilds a clip from its audible ranges, closing the gaps. Empty if nothing is audible. */
    fun trimSilence(clip: Clip, audible: List<LongRange>, nextId: () -> Long): List<Clip> {
        val result = ArrayList<Clip>()
        var cursor = clip.startUs
        for (range in audible) {
            val from = max(range.first, clip.inUs)
            val to = min(range.last, clip.outUs)
            if (to - from < MIN_CLIP_US) continue
            val piece = clip.copy(
                id = if (result.isEmpty()) clip.id else nextId(),
                startUs = cursor,
                inUs = from,
                outUs = to,
            )
            result += piece
            cursor = piece.endUs
        }
        return result
    }
}

/** Averages taps into a tempo; a pause longer than two seconds starts over. */
class TapTempo(private val maxTaps: Int = 8, private val resetMs: Long = 2000) {
    private val taps = ArrayDeque<Long>()

    fun tap(nowMs: Long): Double? {
        if (taps.isNotEmpty() && nowMs - taps.last() > resetMs) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > maxTaps) taps.removeFirst()
        if (taps.size < 2) return null
        return 60_000.0 * (taps.size - 1) / (taps.last() - taps.first())
    }
}
