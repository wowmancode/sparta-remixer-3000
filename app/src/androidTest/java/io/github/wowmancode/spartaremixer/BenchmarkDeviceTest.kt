package io.github.wowmancode.spartaremixer

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.media.ExportSettings
import io.github.wowmancode.spartaremixer.media.MediaLibrary
import io.github.wowmancode.spartaremixer.media.VideoExporter
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Times a Sparta-style project of fast repeated chops. Results go to logcat as SpartaBench. */
@RunWith(AndroidJUnit4::class)
class BenchmarkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val chopPoints = longArrayOf(500_000, 2_000_000, 500_000, 5_000_000, 500_000, 3_500_000, 6_500_000, 2_000_000)

    @Test
    fun chopProject() {
        val file = File(context.cacheDir, "bench.mp4")
        instrumentation.context.assets.open("bench.mp4").use { i -> file.outputStream().use { i.copyTo(it) } }
        val source = MediaLibrary.probe(context, Uri.fromFile(file))
        val reader = MediaLibrary.loadAudio(context, source).reader
        val clips = List(96) { i ->
            val inUs = chopPoints[i % chopPoints.size]
            Clip(i + 1L, source.id, 0, i * 125_000L, inUs, inUs + 125_000L)
        }
        val output = File(context.cacheDir, "bench_out.mp4").apply { delete() }
        val exportStart = SystemClock.elapsedRealtime()
        VideoExporter(
            context, clips, mapOf(source.id to source), mapOf(source.id to reader),
            ExportSettings(shortSide = 720), {}, { false },
        ).export(output)
        Log.i("SpartaBench", "export_ms=${SystemClock.elapsedRealtime() - exportStart}")

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, Uri.fromFile(file))
        val previewStart = SystemClock.elapsedRealtime()
        for (i in 0 until 48) {
            retriever.getScaledFrameAtTime(
                chopPoints[i % chopPoints.size] + i * 3_000L, MediaMetadataRetriever.OPTION_CLOSEST, 640, 360,
            )
        }
        Log.i("SpartaBench", "preview_frame_ms=${(SystemClock.elapsedRealtime() - previewStart) / 48}")
        retriever.release()
    }
}
