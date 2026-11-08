package io.github.wowmancode.spartaremixer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import io.github.wowmancode.spartaremixer.core.Clip
import io.github.wowmancode.spartaremixer.core.ENVELOPE_WINDOW_US
import io.github.wowmancode.spartaremixer.core.MIN_CLIP_US
import io.github.wowmancode.spartaremixer.core.Project
import io.github.wowmancode.spartaremixer.core.Timeline
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class TimelineView(context: Context) : View(context) {
    interface Listener {
        fun onSeek(timeUs: Long)
        fun onSelect(clip: Clip?)
        fun onEditStart()
        fun onClipEdited()
        fun onLayerTapped(index: Int)
    }

    var project = Project()
    var listener: Listener? = null
    var envelopes: Map<String, FloatArray> = emptyMap()
    var playheadUs = 0L
        set(value) {
            field = value
            invalidate()
        }
    var activeLayer = 0
        set(value) {
            field = value
            invalidate()
        }
    var selectedId: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rulerHeight = 30 * density
    private var pxPerSecond = 120f * density
    private var scrollPx = 0f
    private var scrollRowsPx = 0f
    private val gutter = 30 * density
    private val minRow = 36 * density

    private val background = fill(Color.rgb(18, 13, 13))
    private val rulerPaint = fill(Color.rgb(36, 24, 24))
    private val trackPaint = fill(Color.rgb(28, 20, 20))
    private val barPaint = line(Color.argb(150, 242, 193, 78), 1.2f)
    private val beatPaint = line(Color.argb(55, 255, 255, 255), 1f)
    private val clipPaint = fill(Color.rgb(142, 29, 43))
    private val invertedPaint = fill(Color.rgb(36, 118, 136))
    private val selectedPaint = line(Color.rgb(242, 193, 78), 2.5f)
    private val handlePaint = fill(Color.rgb(242, 193, 78))
    private val wavePaint = fill(Color.argb(130, 255, 255, 255))
    private val playheadPaint = line(Color.WHITE, 2f)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * density
    }
    private val rect = RectF()
    private val gutterPaint = fill(Color.rgb(46, 30, 30))
    private val activeGutterPaint = fill(Color.rgb(242, 193, 78))
    private val subdivisionPaint = line(Color.argb(30, 255, 255, 255), 1f)
    private val gutterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * density
        textAlign = Paint.Align.CENTER
    }

    private enum class Mode { NONE, SEEK, PAN, MOVE, TRIM_LEFT, TRIM_RIGHT }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var downRows = 0f
    private var dragging = false
    private var touchedClip: Clip? = null
    private var scaling = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                mode = Mode.NONE
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focusUs = xToTime(detector.focusX)
                pxPerSecond = (pxPerSecond * detector.scaleFactor).coerceIn(12f * density, 1600f * density)
                scrollPx = max(0f, focusUs / 1e6f * pxPerSecond - (detector.focusX - gutter))
                invalidate()
                return true
            }
        },
    )

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.FILL
    }

    private fun line(c: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = width * resources.displayMetrics.density
    }

    private fun timeToX(us: Long): Float = us / 1e6f * pxPerSecond - scrollPx + gutter
    private fun xToTime(x: Float): Long = max(0L, ((x - gutter + scrollPx) / pxPerSecond * 1e6).roundToLong())
    private val layerCount: Int get() = project.layers.size
    private val trackHeight: Float get() = max(minRow, (height - rulerHeight) / layerCount)
    private val maxRowScroll: Float get() = max(0f, layerCount * trackHeight - (height - rulerHeight))
    private fun trackTop(track: Int): Float = rulerHeight + track * trackHeight - scrollRowsPx.coerceAtMost(maxRowScroll)
    private fun trackAt(y: Float): Int = ((y - trackTop(0)) / trackHeight).toInt()

    /** Keeps the playhead on screen while the project plays. */
    fun follow() {
        val x = timeToX(playheadUs)
        if (x > width * 0.85f || x < gutter) scrollPx = max(0f, playheadUs / 1e6f * pxPerSecond - width * 0.2f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, rulerPaint)
        canvas.save()
        canvas.clipRect(0f, rulerHeight, width.toFloat(), height.toFloat())
        for (t in 0 until layerCount) {
            val top = trackTop(t)
            canvas.drawRect(0f, top + density, width.toFloat(), top + trackHeight - density, trackPaint)
        }
        canvas.restore()
        drawGrid(canvas)
        canvas.save()
        canvas.clipRect(gutter, rulerHeight, width.toFloat(), height.toFloat())
        project.clips.filter { it.id != selectedId }.forEach { drawClip(canvas, it) }
        project.clip(selectedId)?.let { drawClip(canvas, it) }
        canvas.restore()
        drawGutter(canvas)
        val px = timeToX(playheadUs)
        canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
    }

    private fun drawGutter(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, rulerHeight, gutter, height.toFloat())
        for (t in 0 until layerCount) {
            val top = trackTop(t)
            val active = t == activeLayer
            canvas.drawRect(0f, top + density, gutter, top + trackHeight - density, if (active) activeGutterPaint else gutterPaint)
            gutterText.color = if (active) Color.rgb(24, 12, 12) else Color.WHITE
            canvas.drawText("${t + 1}", gutter / 2, top + trackHeight / 2 + 5 * density, gutterText)
        }
        canvas.restore()
    }

    private fun drawSubdivisions(canvas: Canvas) {
        if (!project.snapEnabled || project.snapBeats >= 1.0) return
        val step = Timeline.beatUs(project.bpm) * project.snapBeats
        if (step / 1e6 * pxPerSecond < 9 * density) return
        var i = floor(xToTime(gutter) / step).toLong()
        val last = (xToTime(width.toFloat()) / step).toLong() + 1
        while (i <= last) {
            val x = timeToX((i * step).roundToLong())
            canvas.drawLine(x, rulerHeight, x, height.toFloat(), subdivisionPaint)
            i++
        }
    }

    private fun drawGrid(canvas: Canvas) {
        drawSubdivisions(canvas)
        val beat = Timeline.beatUs(project.bpm)
        val beatPx = beat / 1e6 * pxPerSecond
        val barsOnly = beatPx < 8 * density
        val first = max(0L, floor(xToTime(gutter) / beat).toLong())
        val last = (xToTime(width.toFloat()) / beat).toLong() + 1
        var b = first
        while (b <= last) {
            val isBar = b % 4 == 0L
            if (isBar || !barsOnly) {
                val x = timeToX((b * beat).roundToLong())
                canvas.drawLine(x, rulerHeight * if (isBar) 0.3f else 0.7f, x, height.toFloat(), if (isBar) barPaint else beatPaint)
                if (isBar && beatPx * 4 > 34 * density) canvas.drawText("${b / 4 + 1}", x + 4 * density, rulerHeight * 0.55f, textPaint)
            }
            b++
        }
    }

    private fun drawClip(canvas: Canvas, clip: Clip) {
        val left = timeToX(clip.startUs)
        val right = timeToX(clip.endUs)
        if (right < gutter || left > width) return
        val top = trackTop(clip.track) + 2 * density
        val bottom = top + trackHeight - 4 * density
        rect.set(left, top, right, bottom)
        val radius = 4 * density
        canvas.drawRoundRect(rect, radius, radius, if (clip.invert) invertedPaint else clipPaint)
        drawWave(canvas, clip, left, right, top, bottom)
        canvas.save()
        canvas.clipRect(rect)
        val label = (project.source(clip.sourceId)?.name ?: "missing") +
            if (clip.semitones != 0) "  ${"%+d".format(clip.semitones)} st${if (clip.keepLength) " fixed" else ""}" else ""
        canvas.drawText(label, left + 5 * density, top + 13 * density, textPaint)
        canvas.restore()
        if (clip.id == selectedId) {
            canvas.drawRoundRect(rect, radius, radius, selectedPaint)
            val handle = min(6 * density, (right - left) / 3)
            canvas.drawRect(left, top, left + handle, bottom, handlePaint)
            canvas.drawRect(right - handle, top, right, bottom, handlePaint)
        }
    }

    private fun drawWave(canvas: Canvas, clip: Clip, left: Float, right: Float, top: Float, bottom: Float) {
        val envelope = envelopes[clip.sourceId] ?: return
        val mid = (top + bottom) / 2 + 6 * density
        val maxHalf = (bottom - top) / 2 - 8 * density
        if (maxHalf <= 0) return
        val step = 2 * density
        var x = max(left, gutter)
        val end = min(right, width.toFloat())
        while (x < end) {
            val sourceUs = clip.sourceAt(((x - left) / pxPerSecond * 1e6).roundToLong() + clip.startUs)
            val i = (sourceUs / ENVELOPE_WINDOW_US).toInt()
            if (i in envelope.indices) {
                val h = min(1f, envelope[i] * 2.5f) * maxHalf
                canvas.drawRect(x, mid - h, x + density, mid + h, wavePaint)
            }
            x += step
        }
    }

    private fun clipAt(x: Float, y: Float): Clip? {
        if (y < rulerHeight) return null
        val track = trackAt(y)
        val time = xToTime(x)
        return project.clips.filter { it.track == track && time in it.startUs..it.endUs }
            .sortedByDescending { it.id == selectedId }
            .firstOrNull()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> if (!scaling) onMove(event)
            MotionEvent.ACTION_UP -> {
                if (!scaling) onUp(event)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun reset() {
        if (dragging && touchedClip != null) listener?.onClipEdited()
        mode = Mode.NONE
        dragging = false
        touchedClip = null
        scaling = false
    }

    private fun onDown(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        downX = event.x
        downY = event.y
        downScroll = scrollPx
        downRows = scrollRowsPx.coerceAtMost(maxRowScroll)
        dragging = false
        scaling = false
        touchedClip = null
        if (event.y < rulerHeight) {
            mode = Mode.SEEK
            listener?.onSeek(xToTime(event.x))
            return
        }
        if (event.x < gutter) {
            mode = Mode.NONE
            val track = trackAt(event.y)
            if (track in 0 until layerCount) listener?.onLayerTapped(track)
            return
        }
        val clip = clipAt(event.x, event.y)
        if (clip == null) {
            mode = Mode.PAN
            return
        }
        touchedClip = clip
        val zone = min(20 * density, (timeToX(clip.endUs) - timeToX(clip.startUs)) / 3)
        mode = when {
            clip.id == selectedId && event.x - timeToX(clip.startUs) < zone -> Mode.TRIM_LEFT
            clip.id == selectedId && timeToX(clip.endUs) - event.x < zone -> Mode.TRIM_RIGHT
            else -> Mode.MOVE
        }
    }

    private fun onMove(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY
        if (!dragging && abs(dx) < slop && abs(dy) < slop) return
        val first = !dragging
        dragging = true
        val clip = touchedClip
        when (mode) {
            Mode.SEEK -> listener?.onSeek(xToTime(event.x))
            Mode.PAN -> {
                scrollPx = max(0f, downScroll - dx)
                scrollRowsPx = (downRows - dy).coerceIn(0f, maxRowScroll)
                invalidate()
            }
            Mode.MOVE, Mode.TRIM_LEFT, Mode.TRIM_RIGHT -> if (clip != null) {
                if (first) {
                    listener?.onEditStart()
                    if (selectedId != clip.id) {
                        selectedId = clip.id
                        listener?.onSelect(clip)
                    }
                }
                val deltaUs = (dx / pxPerSecond * 1e6).roundToLong()
                project.replace(
                    when (mode) {
                        Mode.MOVE -> moved(clip, deltaUs, event.y)
                        Mode.TRIM_LEFT -> trimmedLeft(clip, deltaUs)
                        else -> trimmedRight(clip, deltaUs)
                    },
                )
                invalidate()
            }
            Mode.NONE -> Unit
        }
    }

    private fun moved(clip: Clip, deltaUs: Long, y: Float): Clip {
        val track = trackAt(y).coerceIn(0, layerCount - 1)
        return clip.copy(startUs = project.snapped(clip.startUs + deltaUs), track = track)
    }

    private fun trimmedLeft(clip: Clip, deltaUs: Long): Clip {
        val start = project.snapped(clip.startUs + deltaUs)
        val newIn = (clip.inUs + ((start - clip.startUs) * clip.speed).roundToLong())
            .coerceIn(0L, max(0L, clip.outUs - MIN_CLIP_US))
        val newStart = clip.startUs + ((newIn - clip.inUs) / clip.speed).roundToLong()
        return clip.copy(startUs = max(0L, newStart), inUs = newIn)
    }

    private fun trimmedRight(clip: Clip, deltaUs: Long): Clip {
        val end = project.snapped(clip.endUs + deltaUs)
        val duration = project.source(clip.sourceId)?.durationUs ?: clip.outUs
        val newOut = (clip.inUs + ((end - clip.startUs) * clip.speed).roundToLong())
            .coerceIn(clip.inUs + MIN_CLIP_US, max(clip.inUs + MIN_CLIP_US, duration))
        return clip.copy(outUs = newOut)
    }

    private fun onUp(event: MotionEvent) {
        if (dragging) return
        if (mode == Mode.SEEK || event.x < gutter) return
        val clip = touchedClip
        selectedId = clip?.id
        listener?.onSelect(clip)
        listener?.onSeek(xToTime(event.x))
    }
}
