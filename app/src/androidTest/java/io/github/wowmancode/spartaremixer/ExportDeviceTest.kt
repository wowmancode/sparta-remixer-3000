package io.github.wowmancode.spartaremixer

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.BitmapFactory
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.Layer
import io.github.wowmancode.spartaremixer.core.PcmReader
import io.github.wowmancode.spartaremixer.core.Source
import io.github.wowmancode.spartaremixer.media.ExportSettings
import io.github.wowmancode.spartaremixer.media.FrameProxy
import io.github.wowmancode.spartaremixer.media.MediaLibrary
import io.github.wowmancode.spartaremixer.media.VideoExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val layers3 = List(3) { Layer() }

    private fun importSample(name: String = "sample.mp4"): Pair<Source, PcmReader> {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val source = MediaLibrary.probe(context, Uri.fromFile(file))
        return source to MediaLibrary.loadAudio(context, source).reader
    }

    @Test
    fun importDecodesVideoAndAudio() {
        val (source, reader) = importSample()
        assertEquals(320, source.displayWidth)
        assertEquals(240, source.displayHeight)
        assertTrue("frames=${reader.frames}", reader.frames in 80_000L..95_000L)
        var peak = 0f
        for (i in 20_000L until 21_000L) peak = maxOf(peak, reader.sample(i, 0))
        assertTrue("peak=$peak", peak > 0.05f)
    }

    @Test
    fun exportAppliesInvertAndPitchAcrossClips() {
        val (source, reader) = importSample()
        val clips = listOf(
            Clip(1, source.id, 0, 0, 0, 1_000_000, invert = true),
            Clip(2, source.id, 0, 1_000_000, 0, 2_000_000, semitones = 12),
            Clip(3, source.id, 1, 2_000_000, 500_000, 1_000_000),
        )
        val output = File(context.cacheDir, "out.mp4").apply { delete() }
        var progress = 0f
        VideoExporter(
            context, clips, layers3, mapOf(source.id to source), mapOf(source.id to reader),
            ExportSettings(shortSide = 360), { progress = it }, { false },
        ).export(output)
        assertEquals(1f, progress, 0f)

        val extractor = MediaExtractor()
        extractor.setDataSource(output.path)
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        extractor.release()
        assertTrue(mimes.toString(), "video/avc" in mimes && "audio/mp4a-latm" in mimes)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("duration=$duration", duration in 2300L..2700L)

        fun centerAt(us: Long): Int {
            val frame = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!
            return frame.getPixel(frame.width / 2, frame.height / 2)
        }
        val inverted = centerAt(500_000)
        assertTrue("inverted=${Integer.toHexString(inverted)}", Color.red(inverted) < 90 && Color.green(inverted) > 170)
        val normal = centerAt(1_500_000)
        assertTrue("normal=${Integer.toHexString(normal)}", Color.red(normal) > 170 && Color.green(normal) < 90)
        retriever.release()
    }

    @Test
    fun gapsExportAsBlack() {
        val (source, reader) = importSample()
        val clips = listOf(Clip(1, source.id, 0, 1_000_000, 0, 500_000))
        val output = File(context.cacheDir, "gap.mp4").apply { delete() }
        VideoExporter(
            context, clips, layers3, mapOf(source.id to source), mapOf(source.id to reader),
            ExportSettings(shortSide = 360), {}, { false },
        ).export(output)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.path)
        val frame = retriever.getFrameAtTime(400_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        val c = frame.getPixel(frame.width / 2, frame.height / 2)
        assertTrue(Integer.toHexString(c), Color.red(c) < 50 && Color.green(c) < 50)
        retriever.release()
    }

    @Test
    fun layersCompositeWithScalePositionAndFlip() {
        val (back, backPcm) = importSample()
        val (front, frontPcm) = importSample("split.mp4")
        val clips = listOf(
            Clip(1, front.id, 0, 0, 0, 1_500_000),
            Clip(2, back.id, 1, 0, 0, 1_500_000),
        )
        val layers = listOf(Layer(scale = 0.5f, x = -0.5f, flipH = true), Layer())
        val output = File(context.cacheDir, "layers.mp4").apply { delete() }
        VideoExporter(
            context, clips, layers, mapOf(front.id to front, back.id to back),
            mapOf(front.id to frontPcm, back.id to backPcm), ExportSettings(shortSide = 360), {}, { false },
        ).export(output)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.path)
        val frame = retriever.getFrameAtTime(700_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        // The front layer is half size on the left and mirrored, so blue comes first.
        val blue = frame.getPixel(100, 180)
        assertTrue(Integer.toHexString(blue), Color.blue(blue) > 170 && Color.red(blue) < 90)
        val red = frame.getPixel(220, 180)
        assertTrue(Integer.toHexString(red), Color.red(red) > 170 && Color.blue(red) < 90)
        // The back layer stays visible around it.
        val backPixel = frame.getPixel(400, 300)
        assertTrue(Integer.toHexString(backPixel), Color.red(backPixel) > 170 && Color.green(backPixel) < 90)
        val letterbox = frame.getPixel(20, 180)
        assertTrue(Integer.toHexString(letterbox), Color.red(letterbox) < 50)
    }

    @Test
    fun proxyStoresEveryFrameForInstantLookup() {
        val (source, _) = importSample()
        val proxy = FrameProxy(context, source)
        proxy.build(context) { false }
        assertTrue(proxy.complete)
        val slot = proxy.resolve(proxy.slotFor(1_000_000))
        assertEquals(30, slot)
        val bytes = proxy.read(slot)!!
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val c = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue(Integer.toHexString(c), Color.red(c) > 170 && Color.green(c) < 90)
        assertTrue("reopened", FrameProxy(context, source).complete)
    }
}
