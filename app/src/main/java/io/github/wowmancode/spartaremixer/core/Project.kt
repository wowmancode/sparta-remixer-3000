package io.github.wowmancode.spartaremixer.core

/** Everything the user is editing. Clips are immutable, so undo only has to copy the list. */
class Project {
    val sources = ArrayList<Source>()
    val clips = ArrayList<Clip>()
    val layers = ArrayList<Layer>().apply { repeat(DEFAULT_LAYERS) { add(Layer()) } }
    var bpm = 120.0
    var snapEnabled = true
    var snapBeats = 1.0
    var nextId = 1L

    fun allocateId(): Long = nextId++

    fun source(id: String): Source? = sources.firstOrNull { it.id == id }

    fun clip(id: Long?): Clip? = clips.firstOrNull { it.id == id }

    fun replace(clip: Clip) {
        val i = clips.indexOfFirst { it.id == clip.id }
        if (i >= 0) clips[i] = clip
    }

    fun addLayer(): Boolean {
        if (layers.size >= MAX_LAYERS) return false
        layers += Layer()
        return true
    }

    /** Removes a layer and its clips. The last remaining layer cannot be removed. */
    fun removeLayer(index: Int): Boolean {
        if (layers.size <= 1 || index !in layers.indices) return false
        layers.removeAt(index)
        val kept = clips.filter { it.track != index }.map { if (it.track > index) it.copy(track = it.track - 1) else it }
        clips.clear()
        clips.addAll(kept)
        return true
    }

    /** Changes a layer's number; its clips travel with it. */
    fun moveLayer(from: Int, to: Int) {
        if (from !in layers.indices || to !in layers.indices || from == to) return
        val order = layers.indices.toMutableList()
        order.add(to, order.removeAt(from))
        val reordered = order.map { layers[it] }
        layers.clear()
        layers.addAll(reordered)
        val moved = clips.map { it.copy(track = order.indexOf(it.track)) }
        clips.clear()
        clips.addAll(moved)
    }

    fun snapped(timeUs: Long): Long =
        if (snapEnabled) Timeline.snap(timeUs, bpm, snapBeats) else maxOf(0L, timeUs)
}
