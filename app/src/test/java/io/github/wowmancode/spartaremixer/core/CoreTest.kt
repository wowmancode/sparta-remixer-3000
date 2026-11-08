package io.github.wowmancode.spartaremixer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun clip(id: Long = 1, track: Int = 0, start: Long = 0, inUs: Long = 0, out: Long = 4_000_000, semi: Int = 0) =
        Clip(id, "s", track, start, inUs, out, semi)

    private class Constant(override val frames: Long, val v: Float) : PcmReader {
        override fun sample(frame: Long, channel: Int) = v
    }

    private class Ramp(override val frames: Long) : PcmReader {
        override fun sample(frame: Long, channel: Int) = frame / 100000f
    }

    @Test fun octaveUpHalvesDuration() {
        assertEquals(2_000_000L, clip(semi = 12).durationUs)
        assertEquals(8_000_000L, clip(semi = -12).durationUs)
    }

    @Test fun splitKeepsSourceContinuous() {
        val (a, b) = clip(semi = 12).splitAt(1_000_000, 2)!!
        assertEquals(2_000_000L, a.outUs)
        assertEquals(2_000_000L, b.inUs)
        assertEquals(1_000_000L, b.startUs)
        assertEquals(1_000_000L, a.durationUs)
    }

    @Test fun splitRejectsSlivers() {
        assertNull(clip().splitAt(5_000, 2))
        assertNotNull(clip().splitAt(1_000_000, 2))
    }

    @Test fun topTrackWinsAndGapsAreBlack() {
        val low = clip(id = 1, track = 1, start = 0, out = 3_000_000)
        val top = clip(id = 2, track = 0, start = 1_000_000, out = 1_000_000)
        val late = clip(id = 3, track = 0, start = 4_000_000, out = 1_000_000)
        val seg = Timeline.videoSegments(listOf(low, top, late)) { true }
        assertEquals(listOf(1L, 2L, 1L, null, 3L), seg.map { it.clip?.id })
        assertEquals(5_000_000L, seg.last().endUs)
        for ((a, b) in seg.zipWithNext()) assertEquals(a.endUs, b.startUs)
    }

    @Test fun snapRoundsToGrid() {
        assertEquals(500_000L, Timeline.snap(520_000, 120.0, 1.0))
        assertEquals(250_000L, Timeline.snap(240_000, 120.0, 0.5))
    }

    @Test fun gridSliceIsContiguous() {
        var id = 10L
        val slices = AutoCut.sliceOnGrid(clip(out = 2_100_000), 120.0, 1.0) { id++ }
        assertEquals(5, slices.size)
        assertEquals(0L, slices.first().inUs)
        assertEquals(2_100_000L, slices.last().outUs)
        for ((a, b) in slices.zipWithNext()) {
            assertEquals(a.outUs, b.inUs)
            assertEquals(a.endUs, b.startUs)
        }
        assertEquals(slices.size, slices.map { it.id }.toSet().size)
    }

    @Test fun gridSliceFollowsPitchedTime() {
        var id = 10L
        val slices = AutoCut.sliceOnGrid(clip(out = 2_000_000, semi = 12), 120.0, 1.0) { id++ }
        assertEquals(2, slices.size)
        assertEquals(1_000_000L, slices[0].outUs)
    }

    @Test fun audibleRangesFindSpeechAndIgnoreShortDips() {
        val env = FloatArray(100)
        for (i in 10 until 30) env[i] = 0.5f
        for (i in 32 until 40) env[i] = 0.5f // 40 ms dip is kept
        for (i in 70 until 80) env[i] = 0.5f
        val r = AutoCut.audibleRanges(env, 20_000, -40.0, 150_000, 0)
        assertEquals(listOf(200_000L..800_000L, 1_400_000L..1_600_000L), r)
    }

    @Test fun trimSilenceClosesGaps() {
        var id = 10L
        val out = AutoCut.trimSilence(clip(start = 500_000), listOf(100_000L..600_000L, 1_000_000L..1_400_000L)) { id++ }
        assertEquals(2, out.size)
        assertEquals(1L, out[0].id)
        assertEquals(500_000L, out[0].startUs)
        assertEquals(1_000_000L, out[1].startUs)
        assertEquals(1_000_000L, out[1].inUs)
    }

    @Test fun trimSilenceOfSilentClipIsEmpty() {
        assertTrue(AutoCut.trimSilence(clip(), emptyList()) { 2 }.isEmpty())
    }

    @Test fun tapTempo() {
        val t = TapTempo()
        assertNull(t.tap(0))
        assertEquals(120.0, t.tap(500)!!, 0.01)
        assertEquals(120.0, t.tap(1000)!!, 0.01)
        assertNull(t.tap(9000))
    }

    @Test fun mixPlacesClipOnTimeline() {
        val c = clip(start = 1_000_000, out = 1_000_000)
        val out = FloatArray(200)
        AudioMix.mix(listOf(c), { Constant(1_000_000, 0.5f) }, 0, out, 100)
        assertEquals(0f, out[100], 0f)
        AudioMix.mix(listOf(c), { Constant(1_000_000, 0.5f) }, usToFrames(1_100_000), out, 100)
        assertEquals(0.5f, out[100], 0.0001f)
    }

    @Test fun octaveUpReadsTwiceAsFast() {
        val c = clip(out = 1_000_000, semi = 12)
        val out = FloatArray(1000)
        AudioMix.mix(listOf(c), { Ramp(1_000_000) }, 200, out, 400)
        assertEquals(400 / 100000f, out[0], 0.00002f)
        assertEquals(402 / 100000f, out[2], 0.00002f)
    }

    @Test fun softClipIsBoundedAndContinuous() {
        assertEquals(0.5f, AudioMix.softClip(0.5f), 0f)
        assertEquals(0.8f, AudioMix.softClip(0.8001f), 0.001f)
        assertTrue(AudioMix.softClip(50f) < 1f)
        assertTrue(AudioMix.softClip(-50f) > -1f)
    }

    @Test fun resamplerKeepsDurationAndValues() {
        var n = 0
        var last = 0f
        val r = StereoResampler(22050) { l, _ -> n++; last = l }
        for (i in 0..1000) r.push(0.25f, 0.25f)
        assertEquals(2000, n)
        assertEquals(0.25f, last, 0f)
    }

    @Test fun envelopeMeasuresRms() {
        val env = AudioMix.envelope(Constant(ENVELOPE_WINDOW_FRAMES * 3L, 0.5f))
        assertEquals(3, env.size)
        assertEquals(0.5f, env[2], 0.0001f)
    }
}
