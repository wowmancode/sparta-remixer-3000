package io.github.wowmancode.spartaremixer.core

/** Everything the user is editing. Clips are immutable, so undo only has to copy the list. */
class Project {
    val sources = ArrayList<Source>()
    val clips = ArrayList<Clip>()
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

    fun snapped(timeUs: Long): Long =
        if (snapEnabled) Timeline.snap(timeUs, bpm, snapBeats) else maxOf(0L, timeUs)
}
