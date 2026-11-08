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

    @Test fun stackIsBackToFrontWithOneClipPerLayer() {
        val back = clip(id = 1, track = 2, out = 3_000_000)
        val front = clip(id = 2, track = 0, out = 3_000_000)
        val earlier = clip(id = 3, track = 1, start = 0, out = 3_000_000)
        val later = clip(id = 4, track = 1, start = 500_000, out = 3_000_000)
        val all = listOf(front, later, earlier, back)
        assertEquals(listOf(1L, 4L, 2L), Timeline.stack(all, 1_000_000, 3) { true }.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), Timeline.stack(all, 100_000, 3) { true }.map { it.id })
        assertTrue(Timeline.stack(all, 9_000_000, 3) { true }.isEmpty())
    }

    @Test fun placementFitsThenAppliesLayer() {
        val fit = Composition.place(320, 240, 640, 360, Layer())
        assertEquals(0.75f, fit.scaleX, 0.001f)
        assertEquals(1f, fit.scaleY, 0.001f)
        val moved = Composition.place(640, 360, 640, 360, Layer(scale = 0.5f, x = 0.25f, y = 0.5f, flipH = true, flipV = true))
        assertEquals(-0.5f, moved.scaleX, 0.001f)
        assertEquals(-0.5f, moved.scaleY, 0.001f)
        assertEquals(0.25f, moved.centerX, 0.001f)
        assertEquals(-0.5f, moved.centerY, 0.001f)
    }

    @Test fun renumberingALayerKeepsItsClips() {
        val p = Project()
        p.clips += clip(id = 1, track = 0)
        p.clips += clip(id = 2, track = 2)
        p.layers[0] = Layer(scale = 0.5f)
        p.moveLayer(0, 2)
        assertEquals(0.5f, p.layers[2].scale, 0f)
        assertEquals(2, p.clip(1)!!.track)
        assertEquals(1, p.clip(2)!!.track)
    }

    @Test fun removingALayerShiftsLaterOnes() {
        val p = Project()
        p.clips += clip(id = 1, track = 0)
        p.clips += clip(id = 2, track = 1)
        p.clips += clip(id = 3, track = 2)
        assertTrue(p.removeLayer(1))
        assertEquals(listOf(0, 1), p.clips.map { it.track })
        assertEquals(listOf(1L, 3L), p.clips.map { it.id })
    }

    @Test fun layerVolumeScalesTheMix() {
        val c = clip(track = 1, out = 1_000_000)
        val out = FloatArray(800)
        AudioMix.mix(listOf(c), { Constant(1_000_000, 0.4f) }, 1000, out, 400, floatArrayOf(1f, 0.5f))
        assertEquals(0.2f, out[100], 0.0001f)
    }

    private class Sine(val hz: Double) : PcmReader {
        override val frames = 200_000L
        override fun sample(frame: Long, channel: Int) =
            (0.5 * kotlin.math.sin(2 * Math.PI * hz * frame / SAMPLE_RATE)).toFloat()
    }

    @Test fun keepLengthRaisesPitchWithoutChangingDuration() {
        val c = clip(out = 2_000_000, semi = 12).copy(keepLength = true)
        assertEquals(2_000_000L, c.durationUs)
        val frames = SAMPLE_RATE
        val out = FloatArray(frames * 2)
        AudioMix.mix(listOf(c), { Sine(441.0) }, 20_000, out, frames)
        var crossings = 0
        for (i in 1 until frames) if (out[(i - 1) * 2] < 0f && out[i * 2] >= 0f) crossings++
        assertTrue("crossings=$crossings", crossings in 860..904)
        var peak = 0f
        for (i in 0 until frames) peak = maxOf(peak, out[i * 2])
        assertTrue("peak=$peak", peak in 0.4f..0.6f)
    }

    @Test fun keepLengthGrainsStayInsideTheClip() {
        val c = clip(inUs = 500_000, out = 1_000_000, semi = 7).copy(keepLength = true)
        val grains = PitchShift.grains(c, Sine(200.0))
        val from = usToFrames(500_000)
        assertTrue(grains.all { it >= from })
        assertTrue(grains.all { it + kotlin.math.ceil(c.pitchRatio * PitchShift.GRAIN).toLong() <= usToFrames(1_000_000) })
    }

    @Test fun divisionsIncludeSixthsAndEighths() {
        assertEquals("1/6 beat", Divisions.name(1.0 / 6.0))
        assertEquals("1/8 beat", Divisions.name(0.125))
        assertEquals(62_500L, Timeline.snap(70_000, 120.0, 0.125))
        assertEquals(83_333L, Timeline.snap(80_000, 120.0, 1.0 / 6.0))
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
