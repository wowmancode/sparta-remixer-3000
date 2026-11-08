package io.github.wowmancode.spartaremixer.media

import android.content.Context
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.Layer
import io.github.wowmancode.spartaremixer.core.Project
import io.github.wowmancode.spartaremixer.core.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Autosaves the single working project as JSON in private storage. */
object ProjectStore {
    private fun file(context: Context) = File(context.filesDir, "project.json")

    fun save(context: Context, project: Project) {
        val sources = JSONArray()
        project.sources.forEach {
            sources.put(
                JSONObject()
                    .put("id", it.id).put("uri", it.uri).put("name", it.name)
                    .put("durationUs", it.durationUs)
                    .put("width", it.displayWidth).put("height", it.displayHeight),
            )
        }
        val clips = JSONArray()
        project.clips.forEach {
            clips.put(
                JSONObject()
                    .put("id", it.id).put("sourceId", it.sourceId).put("track", it.track)
                    .put("startUs", it.startUs).put("inUs", it.inUs).put("outUs", it.outUs)
                    .put("semitones", it.semitones).put("invert", it.invert)
                    .put("keepLength", it.keepLength),
            )
        }
        val layers = JSONArray()
        project.layers.forEach {
            layers.put(
                JSONObject()
                    .put("scale", it.scale.toDouble()).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                    .put("flipH", it.flipH).put("flipV", it.flipV).put("volume", it.volume.toDouble()),
            )
        }
        val root = JSONObject()
            .put("bpm", project.bpm).put("snapEnabled", project.snapEnabled)
            .put("snapBeats", project.snapBeats).put("nextId", project.nextId)
            .put("sources", sources).put("clips", clips).put("layers", layers)
        val target = file(context)
        val temp = File(target.parentFile, "project.tmp")
        temp.writeText(root.toString())
        temp.renameTo(target)
    }

    fun load(context: Context): Project? {
        val target = file(context)
        if (!target.exists()) return null
        return try {
            val root = JSONObject(target.readText())
            Project().apply {
                bpm = root.getDouble("bpm")
                snapEnabled = root.getBoolean("snapEnabled")
                snapBeats = root.getDouble("snapBeats")
                nextId = root.getLong("nextId")
                val s = root.getJSONArray("sources")
                for (i in 0 until s.length()) {
                    val o = s.getJSONObject(i)
                    sources += Source(
                        o.getString("id"), o.getString("uri"), o.getString("name"),
                        o.getLong("durationUs"), o.getInt("width"), o.getInt("height"),
                    )
                }
                val c = root.getJSONArray("clips")
                for (i in 0 until c.length()) {
                    val o = c.getJSONObject(i)
                    clips += Clip(
                        o.getLong("id"), o.getString("sourceId"), o.getInt("track"),
                        o.getLong("startUs"), o.getLong("inUs"), o.getLong("outUs"),
                        o.getInt("semitones"), o.getBoolean("invert"), o.optBoolean("keepLength", false),
                    )
                }
                root.optJSONArray("layers")?.let { l ->
                    layers.clear()
                    for (i in 0 until l.length()) {
                        val o = l.getJSONObject(i)
                        layers += Layer(
                            o.getDouble("scale").toFloat(), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                            o.getBoolean("flipH"), o.getBoolean("flipV"), o.getDouble("volume").toFloat(),
                        )
                    }
                }
            }
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
