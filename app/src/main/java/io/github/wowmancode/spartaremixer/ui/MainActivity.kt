package io.github.wowmancode.spartaremixer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.wowmancode.spartaremixer.core.AutoCut
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.ENVELOPE_WINDOW_US
import io.github.wowmancode.spartaremixer.core.PcmReader
import io.github.wowmancode.spartaremixer.core.Project
import io.github.wowmancode.spartaremixer.core.Source
import io.github.wowmancode.spartaremixer.core.TapTempo
import io.github.wowmancode.spartaremixer.core.Timeline
import io.github.wowmancode.spartaremixer.media.AudioPreview
import io.github.wowmancode.spartaremixer.media.ExportCancelled
import io.github.wowmancode.spartaremixer.media.ExportSettings
import io.github.wowmancode.spartaremixer.media.FramePreviewer
import io.github.wowmancode.spartaremixer.media.MediaLibrary
import io.github.wowmancode.spartaremixer.media.ProjectStore
import io.github.wowmancode.spartaremixer.media.VideoExporter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), TimelineView.Listener {
    private class Snapshot(val clips: List<Clip>, val bpm: Double, val selected: Long?)

    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private var project = Project()
    private val readers = HashMap<String, PcmReader>()
    private val envelopes = HashMap<String, FloatArray>()
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val tapTempo = TapTempo()

    private var selectedId: Long? = null
    private var playheadUs = 0L
    private var player: AudioPreview? = null
    private var pendingExport: ExportSettings? = null
    @Volatile private var exportCancelled = false
    private var busyCount = 0

    private lateinit var previewer: FramePreviewer
    private lateinit var timeline: TimelineView
    private lateinit var previewImage: ImageView
    private lateinit var hint: TextView
    private lateinit var playButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var bpmField: EditText
    private lateinit var snapButton: Button
    private lateinit var divisionButton: Button
    private lateinit var timeLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var invertButton: Button
    private lateinit var clipBar: View
    private lateinit var busyBar: ProgressBar

    private val invertFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewer = FramePreviewer(this) { previewImage.setImageBitmap(it) }
        setContentView(buildUi())
        ProjectStore.load(this)?.let { project = it }
        timeline.project = project
        timeline.envelopes = envelopes
        loadAllAudio()
        refresh()
    }

    private fun button(text: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = dp(44)
        minimumWidth = dp(44)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(if (accent) Color.rgb(24, 12, 12) else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (accent) Color.rgb(242, 193, 78) else Color.rgb(64, 38, 38))
        }
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40),
        ).apply { marginEnd = dp(5) }
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(6), 0, dp(8), 0)
    }

    private fun row(vararg views: View): HorizontalScrollView {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        views.forEach { line.addView(it) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(line)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 8, 8))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
            insets
        }

        undoButton = button("Undo") { undo() }
        redoButton = button("Redo") { redo() }
        root.addView(
            row(
                button("Import", accent = true) { pickMedia() },
                button("Library") { showLibrary() },
                undoButton,
                redoButton,
                button("Export", accent = true) { showExportDialog() },
                button("New") { confirmNewProject() },
            ),
        )

        val previewFrame = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        hint = TextView(this).apply {
            text = "Import a video to start.\nPinch the timeline to zoom, drag clips to move them."
            setTextColor(Color.rgb(190, 170, 170))
            gravity = Gravity.CENTER
            textSize = 14f
        }
        previewFrame.addView(previewImage, android.widget.FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(hint, android.widget.FrameLayout.LayoutParams(-1, -1))
        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(busyBar, LinearLayout.LayoutParams(-1, dp(4)))

        playButton = button("Play", accent = true) { togglePlay() }
        timeLabel = label("0:00.00")
        bpmField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            textSize = 14f
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(44))
            setOnEditorActionListener { _, _, _ ->
                commitBpm()
                clearFocus()
                false
            }
            setOnFocusChangeListener { _, focused -> if (!focused) commitBpm() }
        }
        snapButton = button("Snap") { toggleSnap() }
        divisionButton = button("1 beat") { cycleDivision() }
        root.addView(
            row(
                playButton, timeLabel, label("BPM"), bpmField,
                button("Tap") { onTap() }, snapButton, divisionButton,
            ),
        )

        pitchLabel = label("0 st")
        invertButton = button("Invert") { edit { it.copy(invert = !it.invert) } }
        clipBar = row(
            button("Split") { splitAtPlayhead() },
            button("Copy") { duplicateSelected() },
            button("Delete") { deleteSelected() },
            invertButton,
            label("Pitch"),
            button("-12") { pitch(-12) },
            button("-1") { pitch(-1) },
            pitchLabel,
            button("+1") { pitch(1) },
            button("+12") { pitch(12) },
            button("Reset") { edit { it.copy(semitones = 0) } },
            button("Slice to BPM") { showSliceDialog() },
            button("Trim silence") { showTrimDialog() },
        )
        root.addView(clipBar)

        timeline = TimelineView(this).apply { listener = this@MainActivity }
        root.addView(timeline, LinearLayout.LayoutParams(-1, dp(190)))
        return root
    }

    // --- State and undo ---

    private fun selected(): Clip? = project.clip(selectedId)

    private fun pushUndo() {
        undoStack.addLast(Snapshot(project.clips.toList(), project.bpm, selectedId))
        while (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(s: Snapshot) {
        project.clips.clear()
        project.clips.addAll(s.clips)
        project.bpm = s.bpm
        selectedId = s.selected
        refresh()
    }

    private fun current() = Snapshot(project.clips.toList(), project.bpm, selectedId)

    private fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        stopPlayback()
        redoStack.addLast(current())
        restore(s)
    }

    private fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        stopPlayback()
        undoStack.addLast(current())
        restore(s)
    }

    private fun edit(change: (Clip) -> Clip) {
        val clip = selected() ?: return
        pushUndo()
        project.replace(change(clip))
        refresh()
    }

    private fun replaceSelected(pieces: List<Clip>) {
        val clip = selected() ?: return
        pushUndo()
        val i = project.clips.indexOfFirst { it.id == clip.id }
        project.clips.removeAt(i)
        project.clips.addAll(i, pieces)
        selectedId = pieces.firstOrNull()?.id
        refresh()
    }

    private fun refresh() {
        timeline.selectedId = selectedId
        timeline.playheadUs = playheadUs
        timeline.invalidate()
        val clip = selected()
        clipBar.alpha = if (clip != null) 1f else 0.35f
        pitchLabel.text = "%+d st".format(clip?.semitones ?: 0)
        invertButton.text = if (clip?.invert == true) "Invert: on" else "Invert"
        undoButton.alpha = if (undoStack.isEmpty()) 0.45f else 1f
        redoButton.alpha = if (redoStack.isEmpty()) 0.45f else 1f
        if (!bpmField.hasFocus()) bpmField.setText(formatBpm(project.bpm))
        snapButton.text = if (project.snapEnabled) "Snap: on" else "Snap: off"
        divisionButton.text = divisionName(project.snapBeats)
        hint.visibility = if (project.clips.isEmpty()) View.VISIBLE else View.GONE
        updateTimeLabel()
        updatePreviewFrame()
    }

    private fun formatBpm(bpm: Double) =
        if (bpm % 1.0 == 0.0) bpm.toInt().toString() else String.format(Locale.US, "%.1f", bpm)

    private fun updateTimeLabel() {
        val cs = playheadUs / 10_000
        timeLabel.text = String.format(Locale.US, "%d:%02d.%02d", cs / 6000, cs / 100 % 60, cs % 100)
    }

    private fun updatePreviewFrame() {
        val clip = Timeline.visibleClip(project.clips, playheadUs) { project.source(it.sourceId)?.hasVideo == true }
        if (clip == null) {
            previewImage.setImageBitmap(null)
            return
        }
        previewImage.colorFilter = if (clip.invert) invertFilter else null
        project.source(clip.sourceId)?.let { previewer.show(it, clip.sourceAt(playheadUs)) }
    }

    // --- Timeline callbacks ---

    override fun onSeek(timeUs: Long) {
        stopPlayback()
        playheadUs = timeUs
        timeline.playheadUs = timeUs
        updateTimeLabel()
        updatePreviewFrame()
    }

    override fun onSelect(clip: Clip?) {
        selectedId = clip?.id
        refresh()
    }

    override fun onEditStart() {
        stopPlayback()
        pushUndo()
    }

    override fun onClipEdited() = refresh()

    // --- Clip tools ---

    private fun requireSelection(): Clip? {
        val clip = selected()
        if (clip == null) toast("Tap a clip first")
        return clip
    }

    private fun pitch(delta: Int) {
        val clip = requireSelection() ?: return
        val next = (clip.semitones + delta).coerceIn(-36, 36)
        if (next != clip.semitones) edit { it.copy(semitones = next) }
    }

    private fun splitAtPlayhead() {
        val clip = requireSelection() ?: return
        val pair = clip.splitAt(playheadUs, project.allocateId())
        if (pair == null) {
            toast("Move the playhead inside the selected clip")
        } else {
            replaceSelected(listOf(pair.first, pair.second))
            selectedId = pair.second.id
            refresh()
        }
    }

    private fun duplicateSelected() {
        val clip = requireSelection() ?: return
        pushUndo()
        val copy = clip.copy(id = project.allocateId(), startUs = clip.endUs)
        project.clips += copy
        selectedId = copy.id
        refresh()
    }

    private fun deleteSelected() {
        val clip = requireSelection() ?: return
        pushUndo()
        project.clips.remove(clip)
        selectedId = null
        refresh()
    }

    private fun showSliceDialog() {
        val clip = requireSelection() ?: return
        val names = arrayOf("Every bar (4 beats)", "Every 2 beats", "Every beat", "Every 1/2 beat", "Every 1/4 beat")
        val beats = doubleArrayOf(4.0, 2.0, 1.0, 0.5, 0.25)
        AlertDialog.Builder(this)
            .setTitle("Slice at ${formatBpm(project.bpm)} BPM")
            .setItems(names) { _, which ->
                val slices = AutoCut.sliceOnGrid(clip, project.bpm, beats[which]) { project.allocateId() }
                if (slices.size < 2) toast("The clip is shorter than one slice") else {
                    replaceSelected(slices)
                    toast("Made ${slices.size} slices")
                }
            }
            .show()
    }

    private fun showTrimDialog() {
        val clip = requireSelection() ?: return
        val envelope = envelopes[clip.sourceId]
        if (envelope == null) {
            toast("Still analysing the audio")
            return
        }
        val names = arrayOf("Gentle (-50 dB)", "Normal (-40 dB)", "Aggressive (-30 dB)")
        val thresholds = doubleArrayOf(-50.0, -40.0, -30.0)
        AlertDialog.Builder(this)
            .setTitle("Trim silence")
            .setItems(names) { _, which ->
                val ranges = AutoCut.audibleRanges(envelope, ENVELOPE_WINDOW_US, thresholds[which])
                val pieces = AutoCut.trimSilence(clip, ranges) { project.allocateId() }
                if (pieces.isEmpty()) toast("This clip is entirely silent, so nothing was changed") else {
                    replaceSelected(pieces)
                    toast(if (pieces.size == 1) "Trimmed" else "Trimmed into ${pieces.size} pieces")
                }
            }
            .show()
    }

    // --- BPM ---

    private fun commitBpm() {
        val value = bpmField.text.toString().replace(',', '.').toDoubleOrNull()
        if (value != null && value in 20.0..400.0 && value != project.bpm) {
            pushUndo()
            project.bpm = value
        }
        bpmField.setText(formatBpm(project.bpm))
        timeline.invalidate()
    }

    private fun onTap() {
        val bpm = tapTempo.tap(SystemClock.uptimeMillis()) ?: return
        if (bpm !in 20.0..400.0) return
        project.bpm = (bpm * 10).roundToInt() / 10.0
        bpmField.clearFocus()
        refresh()
    }

    private fun toggleSnap() {
        project.snapEnabled = !project.snapEnabled
        refresh()
    }

    private fun cycleDivision() {
        val options = listOf(4.0, 2.0, 1.0, 0.5, 0.25)
        val i = options.indexOf(project.snapBeats)
        project.snapBeats = options[(i + 1) % options.size]
        refresh()
    }

    private fun divisionName(beats: Double) = when (beats) {
        4.0 -> "1 bar"
        2.0 -> "2 beats"
        1.0 -> "1 beat"
        0.5 -> "1/2 beat"
        else -> "1/4 beat"
    }

    // --- Playback ---

    private val playTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            playheadUs = p.positionUs
            if (playheadUs >= Timeline.durationUs(project.clips)) {
                stopPlayback()
                playheadUs = 0
                refresh()
                return
            }
            timeline.playheadUs = playheadUs
            timeline.follow()
            updateTimeLabel()
            updatePreviewFrame()
            main.postDelayed(this, 16)
        }
    }

    private fun togglePlay() {
        if (player != null) {
            stopPlayback()
            return
        }
        if (project.clips.isEmpty()) {
            toast("Import a video first")
            return
        }
        if (playheadUs >= Timeline.durationUs(project.clips)) playheadUs = 0
        player = AudioPreview(playheadUs, project.clips.toList(), HashMap(readers)).also { it.start() }
        playButton.text = "Stop"
        main.post(playTick)
    }

    private fun stopPlayback() {
        val p = player ?: return
        player = null
        main.removeCallbacks(playTick)
        p.stop()
        playButton.text = "Play"
    }

    // --- Import ---

    private fun pickMedia() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQUEST_IMPORT,
        )
    }

    private fun setBusy(delta: Int) {
        busyCount += delta
        busyBar.visibility = if (busyCount > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun importUris(uris: List<Uri>) {
        setBusy(1)
        background.execute {
            uris.forEach { uri ->
                try {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        // Some providers only grant temporary access; the import still works now.
                    }
                    val source = MediaLibrary.probe(this, uri)
                    val audio = MediaLibrary.loadAudio(this, source)
                    main.post {
                        readers[source.id] = audio.reader
                        envelopes[source.id] = audio.envelope
                        addImported(source)
                    }
                } catch (e: Exception) {
                    main.post { toast("Could not import: ${e.message ?: uri.lastPathSegment}") }
                }
            }
            main.post { setBusy(-1) }
        }
    }

    private fun addImported(source: Source) {
        pushUndo()
        project.sources += source
        addClip(source, Timeline.durationUs(project.clips))
    }

    private fun addClip(source: Source, atUs: Long) {
        val clip = Clip(project.allocateId(), source.id, 0, atUs, 0, source.durationUs)
        project.clips += clip
        selectedId = clip.id
        refresh()
    }

    private fun showLibrary() {
        if (project.sources.isEmpty()) {
            toast("Nothing imported yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add at playhead")
            .setItems(project.sources.map { it.name }.toTypedArray()) { _, which ->
                pushUndo()
                addClip(project.sources[which], playheadUs)
            }
            .show()
    }

    private fun loadAllAudio() {
        val sources = project.sources.toList()
        if (sources.isEmpty()) return
        setBusy(1)
        background.execute {
            val failed = ArrayList<String>()
            sources.forEach { source ->
                try {
                    val audio = MediaLibrary.loadAudio(this, source)
                    main.post {
                        readers[source.id] = audio.reader
                        envelopes[source.id] = audio.envelope
                        timeline.invalidate()
                    }
                } catch (_: Exception) {
                    failed += source.name
                }
            }
            main.post {
                setBusy(-1)
                if (failed.isNotEmpty()) toast("Could not reopen: ${failed.joinToString()}")
            }
        }
    }

    private fun confirmNewProject() {
        AlertDialog.Builder(this)
            .setMessage("Start a new project? The current one will be cleared.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("New project") { _, _ ->
                stopPlayback()
                project.sources.forEach { MediaLibrary.deleteCached(this, it) }
                project = Project()
                timeline.project = project
                readers.clear()
                envelopes.clear()
                undoStack.clear()
                redoStack.clear()
                selectedId = null
                playheadUs = 0
                refresh()
            }
            .show()
    }

    // --- Export ---

    private fun showExportDialog() {
        if (project.clips.isEmpty()) {
            toast("Add a clip before exporting")
            return
        }
        val missing = project.clips.map { it.sourceId }.toSet().filter { it !in readers }
        if (missing.isNotEmpty()) {
            toast("Still loading the audio, try again in a moment")
            return
        }
        stopPlayback()
        val sizes = intArrayOf(480, 720, 1080)
        AlertDialog.Builder(this)
            .setTitle("Export MP4 (30 fps)")
            .setItems(arrayOf("480p", "720p", "1080p")) { _, which ->
                pendingExport = ExportSettings(shortSide = sizes[which])
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_TITLE, "sparta_remix.mp4")
                    },
                    REQUEST_EXPORT,
                )
            }
            .show()
    }

    private fun runExport(target: Uri, settings: ExportSettings) {
        exportCancelled = false
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Exporting")
            .setView(bar)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> exportCancelled = true }
            .show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val clips = project.clips.toList()
        val sources = project.sources.associateBy { it.id }
        val pcm = HashMap(readers)
        val temp = File(cacheDir, "export.mp4")
        background.execute {
            val message = try {
                temp.delete()
                VideoExporter(
                    this, clips, sources, pcm, settings,
                    { p -> main.post { bar.progress = (p * 1000).roundToInt() } },
                    { exportCancelled },
                ).export(temp)
                val out = contentResolver.openOutputStream(target, "wt")
                    ?: throw IllegalStateException("Could not open the destination file")
                out.use { o -> temp.inputStream().use { it.copyTo(o) } }
                "Exported"
            } catch (_: ExportCancelled) {
                android.provider.DocumentsContract.deleteDocument(contentResolver, target)
                "Export cancelled"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            } finally {
                temp.delete()
            }
            main.post {
                dialog.dismiss()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                toast(message)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_IMPORT -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                importUris(uris)
            }
            REQUEST_EXPORT -> {
                val target = data.data
                val settings = pendingExport
                if (target != null && settings != null) runExport(target, settings)
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onPause() {
        super.onPause()
        stopPlayback()
        ProjectStore.save(this, project)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        previewer.release()
        background.shutdown()
    }

    private companion object {
        const val REQUEST_IMPORT = 1
        const val REQUEST_EXPORT = 2
    }
}
