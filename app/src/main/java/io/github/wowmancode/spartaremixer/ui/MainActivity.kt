package io.github.wowmancode.spartaremixer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.wowmancode.spartaremixer.core.AutoCut
import io.github.wowmancode.spartaremixer.core.Composition
import io.github.wowmancode.spartaremixer.core.Divisions
import io.github.wowmancode.spartaremixer.core.Layer
import io.github.wowmancode.spartaremixer.core.MAX_LAYERS
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
import io.github.wowmancode.spartaremixer.media.FrameProxy
import io.github.wowmancode.spartaremixer.media.MediaLibrary
import io.github.wowmancode.spartaremixer.media.ProjectStore
import io.github.wowmancode.spartaremixer.media.VideoExporter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), TimelineView.Listener {
    private class Snapshot(val clips: List<Clip>, val layers: List<Layer>, val bpm: Double, val selected: Long?)

    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private val proxyWorker = Executors.newSingleThreadExecutor()
    private val proxies = HashMap<String, FrameProxy>()
    @Volatile private var closing = false
    private var project = Project()
    private val readers = HashMap<String, PcmReader>()
    private val envelopes = HashMap<String, FloatArray>()
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val tapTempo = TapTempo()

    private var selectedId: Long? = null
    private var activeLayer = 0
    private var playheadUs = 0L
    private var player: AudioPreview? = null
    private var pendingExport: ExportSettings? = null
    @Volatile private var exportCancelled = false
    private var busyCount = 0

    private lateinit var timeline: TimelineView
    private lateinit var preview: PreviewSurface
    private lateinit var proxyStatus: TextView
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
    private lateinit var keepLengthButton: Button
    private lateinit var layerPanel: View
    private lateinit var layerLabel: TextView
    private lateinit var flipHButton: Button
    private lateinit var flipVButton: Button
    private lateinit var scaleSlider: Slider
    private lateinit var volumeSlider: Slider
    private lateinit var xSlider: Slider
    private lateinit var ySlider: Slider
    private lateinit var clipBar: View
    private lateinit var busyBar: ProgressBar

    private class Slider(val view: View, val set: (Float) -> Unit)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ProjectStore.load(this)?.let { project = it }
        timeline.project = project
        timeline.envelopes = envelopes
        loadAllAudio()
        project.sources.forEach { queueProxy(it) }
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
        preview = PreviewSurface(this).apply { listener = previewGestures }
        proxyStatus = label("").apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            visibility = View.GONE
        }
        hint = TextView(this).apply {
            text = "Import a video to start.\nPinch the timeline to zoom, drag clips to move them."
            setTextColor(Color.rgb(190, 170, 170))
            gravity = Gravity.CENTER
            textSize = 14f
        }
        previewFrame.addView(preview, android.widget.FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(hint, android.widget.FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(proxyStatus, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
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
        keepLengthButton = button("Keep length") { edit { it.copy(keepLength = !it.keepLength) } }
        clipBar = row(
            button("Layer settings", accent = true) { showLayerPanel(true) },
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
            keepLengthButton,
            button("Slice to BPM") { showSliceDialog() },
            button("Trim silence") { showTrimDialog() },
        )
        root.addView(clipBar)
        layerPanel = buildLayerPanel().apply { visibility = View.GONE }
        root.addView(layerPanel)

        timeline = TimelineView(this).apply { listener = this@MainActivity }
        root.addView(timeline, LinearLayout.LayoutParams(-1, dp(190)))
        return root
    }

    private fun slider(
        name: String, min: Float, max: Float, format: (Float) -> String, onChange: (Float) -> Unit,
    ): Slider {
        val steps = ((max - min) * 100).roundToInt()
        val text = label("$name ${format(min)}").apply {
            textSize = 11f
            gravity = Gravity.START
        }
        val bar = SeekBar(this).apply {
            this.max = steps
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    pushUndo()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) = refresh()

                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress / 100f
                    text.text = "$name ${format(value)}"
                    if (fromUser) onChange(value)
                }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(bar)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        return Slider(box) { bar.progress = ((it - min) * 100).roundToInt().coerceIn(0, steps) }
    }

    private fun buildLayerPanel(): View {
        val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
        scaleSlider = slider("Scale", 0.1f, 3f, percent) { v -> changeLayer { it.copy(scale = v) } }
        volumeSlider = slider("Volume", 0f, 2f, percent) { v -> changeLayer { it.copy(volume = v) } }
        xSlider = slider("X", -1.5f, 1.5f, percent) { v -> changeLayer { it.copy(x = v) } }
        ySlider = slider("Y", -1.5f, 1.5f, percent) { v -> changeLayer { it.copy(y = v) } }
        layerLabel = label("Layer 1 of 3")
        flipHButton = button("Flip left/right") { toggleLayer { it.copy(flipH = !it.flipH) } }
        flipVButton = button("Flip up/down") { toggleLayer { it.copy(flipV = !it.flipV) } }
        val pair = { a: View, b: View ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), 0, dp(4), 0)
                addView(a)
                addView(b)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                row(
                    button("Clip tools", accent = true) { showLayerPanel(false) },
                    layerLabel,
                    button("Number -") { renumberLayer(-1) },
                    button("Number +") { renumberLayer(1) },
                    flipHButton,
                    flipVButton,
                    button("Reset") { toggleLayer { Layer() } },
                    button("Add layer") { addLayer() },
                    button("Remove") { confirmRemoveLayer() },
                ),
            )
            addView(pair(scaleSlider.view, volumeSlider.view))
            addView(pair(xSlider.view, ySlider.view))
        }
    }

    private fun showLayerPanel(show: Boolean) {
        layerPanel.visibility = if (show) View.VISIBLE else View.GONE
        clipBar.visibility = if (show) View.GONE else View.VISIBLE
    }

    private val layer: Layer get() = project.layers[activeLayer]

    /** A live slider or drag change; the caller has already saved the undo state. */
    private fun changeLayer(change: (Layer) -> Layer) {
        project.layers[activeLayer] = change(layer)
        player?.update(project.clips.toList(), layerGains())
        updatePreviewFrame()
    }

    private fun toggleLayer(change: (Layer) -> Layer) {
        pushUndo()
        changeLayer(change)
        refresh()
    }

    private fun renumberLayer(delta: Int) {
        val to = activeLayer + delta
        if (to !in project.layers.indices) return
        pushUndo()
        project.moveLayer(activeLayer, to)
        activeLayer = to
        refresh()
    }

    private fun addLayer() {
        if (project.layers.size >= MAX_LAYERS) {
            toast("The limit is $MAX_LAYERS layers")
            return
        }
        pushUndo()
        project.addLayer()
        activeLayer = project.layers.lastIndex
        refresh()
    }

    private fun confirmRemoveLayer() {
        if (project.layers.size <= 1) {
            toast("A project needs at least one layer")
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Remove layer ${activeLayer + 1} and its clips?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                pushUndo()
                project.removeLayer(activeLayer)
                if (selected() == null) selectedId = null
                activeLayer = activeLayer.coerceAtMost(project.layers.lastIndex)
                refresh()
            }
            .show()
    }

    private fun layerGains() = FloatArray(project.layers.size) { project.layers[it].volume }

    private val previewGestures = object : PreviewSurface.Listener {
        override fun onGestureStart() = pushUndo()

        override fun onMove(dx: Float, dy: Float) = changeLayer {
            it.copy(x = (it.x + dx).coerceIn(-1.5f, 1.5f), y = (it.y + dy).coerceIn(-1.5f, 1.5f))
        }

        override fun onScale(factor: Float) = changeLayer { it.copy(scale = (it.scale * factor).coerceIn(0.1f, 3f)) }

        override fun onGestureEnd() = refresh()
    }

    // --- State and undo ---

    private fun selected(): Clip? = project.clip(selectedId)

    private fun pushUndo() {
        undoStack.addLast(current())
        while (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(s: Snapshot) {
        project.clips.clear()
        project.clips.addAll(s.clips)
        project.layers.clear()
        project.layers.addAll(s.layers)
        activeLayer = activeLayer.coerceAtMost(project.layers.lastIndex)
        project.bpm = s.bpm
        selectedId = s.selected
        refresh()
    }

    private fun current() = Snapshot(project.clips.toList(), project.layers.toList(), project.bpm, selectedId)

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
        timeline.activeLayer = activeLayer
        val l = layer
        layerLabel.text = "Layer ${activeLayer + 1} of ${project.layers.size}"
        scaleSlider.set(l.scale)
        volumeSlider.set(l.volume)
        xSlider.set(l.x)
        ySlider.set(l.y)
        flipHButton.text = if (l.flipH) "Left/right: flipped" else "Flip left/right"
        flipVButton.text = if (l.flipV) "Up/down: flipped" else "Flip up/down"
        keepLengthButton.text = if (clip?.keepLength == true) "Keep length: on" else "Keep length: off"
        player?.update(project.clips.toList(), layerGains())
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
        val (width, height) = Composition.outputSize(PREVIEW_SHORT_SIDE, project.clips) { project.source(it) }
        val stack = Timeline.stack(project.clips, playheadUs, project.layers.size) {
            project.source(it.sourceId)?.hasVideo == true
        }
        val items = stack.mapNotNull { clip ->
            val proxy = proxies[clip.sourceId] ?: return@mapNotNull null
            val sourceUs = clip.sourceAt(playheadUs).coerceIn(clip.inUs, clip.outUs)
            PreviewSurface.Item(proxy, proxy.slotFor(sourceUs), project.layers[clip.track], clip.invert)
        }
        preview.show(PreviewSurface.Scene(items, width, height))
    }

    // --- Timeline callbacks ---

    override fun onSeek(timeUs: Long) {
        stopPlayback()
        playheadUs = timeUs
        timeline.playheadUs = timeUs
        updateTimeLabel()
        updatePreviewFrame()
    }

    override fun onLayerTapped(index: Int) {
        activeLayer = index
        showLayerPanel(true)
        refresh()
    }

    override fun onSelect(clip: Clip?) {
        selectedId = clip?.id
        if (clip != null) activeLayer = clip.track.coerceIn(0, project.layers.lastIndex)
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
        val names = Divisions.all.map { "Every ${it.second}" }.toTypedArray()
        val beats = Divisions.all.map { it.first }
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
        val options = Divisions.all.map { it.first }
        val i = options.indexOfFirst { kotlin.math.abs(it - project.snapBeats) < 1e-9 }
        project.snapBeats = options[(i + 1) % options.size]
        refresh()
    }

    private fun divisionName(beats: Double) = Divisions.name(beats)

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
        player = AudioPreview(playheadUs, project.clips.toList(), HashMap(readers), layerGains()).also { it.start() }
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
        queueProxy(source)
        addClip(source, Timeline.durationUs(project.clips.filter { it.track == activeLayer }))
    }

    private fun addClip(source: Source, atUs: Long) {
        val clip = Clip(project.allocateId(), source.id, activeLayer, atUs, 0, source.durationUs)
        project.clips += clip
        selectedId = clip.id
        refresh()
    }

    /** Builds preview frames in the background, one video at a time, showing them as they arrive. */
    private fun queueProxy(source: Source) {
        if (!source.hasVideo || proxies.containsKey(source.id)) return
        val proxy = FrameProxy(this, source)
        proxies[source.id] = proxy
        if (proxy.complete) return
        proxyWorker.execute {
            val watcher = object : Runnable {
                override fun run() {
                    if (closing || proxy.complete) return
                    proxyStatus.visibility = View.VISIBLE
                    proxyStatus.text = "Preparing preview ${(proxy.progress * 100).roundToInt()}%"
                    if (player == null) updatePreviewFrame()
                    main.postDelayed(this, 400)
                }
            }
            main.post(watcher)
            try {
                proxy.build(this) { closing }
            } catch (e: Exception) {
                main.post { toast("Preview unavailable for ${source.name}: ${e.message}") }
            }
            main.removeCallbacks(watcher)
            main.post {
                proxyStatus.visibility = View.GONE
                if (player == null) updatePreviewFrame()
            }
        }
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
                proxies.values.forEach { it.close() }
                proxies.clear()
                preview.clearCache()
                project = Project()
                activeLayer = 0
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
        val layers = project.layers.toList()
        val sources = project.sources.associateBy { it.id }
        val pcm = HashMap(readers)
        val temp = File(cacheDir, "export.mp4")
        background.execute {
            val message = try {
                temp.delete()
                VideoExporter(
                    this, clips, layers, sources, pcm, settings,
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
        closing = true
        preview.release()
        background.shutdown()
        proxyWorker.shutdown()
    }

    private companion object {
        const val REQUEST_IMPORT = 1
        const val REQUEST_EXPORT = 2
        const val PREVIEW_SHORT_SIDE = 720
    }
}
