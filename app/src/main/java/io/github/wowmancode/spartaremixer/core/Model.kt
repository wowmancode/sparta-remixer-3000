package io.github.wowmancode.spartaremixer.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

const val TRACK_COUNT = 3
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
 * A piece of a source placed on the timeline. Pitch is varispeed, like a sampler:
 * raising it plays audio and video faster, so inUs/outUs stay in source time.
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
) {
    val speed: Double get() = 2.0.pow(semitones / 12.0)
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

data class VideoSegment(val startUs: Long, val endUs: Long, val clip: Clip?)

object Timeline {
    fun durationUs(clips: List<Clip>): Long = clips.maxOfOrNull { it.endUs } ?: 0L

    /** The clip visible at a time: the top track wins, then the most recently started clip. */
    fun visibleClip(clips: List<Clip>, timeUs: Long, hasVideo: (Clip) -> Boolean): Clip? =
        clips.filter { it.contains(timeUs) && hasVideo(it) }
            .minWithOrNull(compareBy<Clip> { it.track }.thenByDescending { it.startUs })

    /** Flattens the tracks into a gapless list of what is on screen; clip == null is black. */
    fun videoSegments(clips: List<Clip>, hasVideo: (Clip) -> Boolean): List<VideoSegment> {
        val total = durationUs(clips)
        if (total <= 0) return emptyList()
        val cuts = sortedSetOf(0L, total)
        clips.forEach { cuts += it.startUs; cuts += it.endUs }
        val result = ArrayList<VideoSegment>()
        cuts.toList().zipWithNext().forEach { (a, b) ->
            val clip = visibleClip(clips, a, hasVideo)
            val last = result.lastOrNull()
            if (last != null && last.clip?.id == clip?.id) {
                result[result.lastIndex] = last.copy(endUs = b)
            } else {
                result += VideoSegment(a, b, clip)
            }
        }
        return result
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
