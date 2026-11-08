package io.github.wowmancode.spartaremixer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.wowmancode.spartaremixer.core.Composition
import io.github.wowmancode.spartaremixer.core.Layer
import io.github.wowmancode.spartaremixer.media.FrameProxy
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** Composites proxy frames on its own thread, so playback never waits on the UI. */
class PreviewSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    interface Listener {
        fun onGestureStart()
        fun onMove(dx: Float, dy: Float)
        fun onScale(factor: Float)
        fun onGestureEnd()
    }

    class Item(val proxy: FrameProxy, val slot: Int, val layer: Layer, val invert: Boolean)
    class Scene(val items: List<Item>, val width: Int, val height: Int)

    var listener: Listener? = null

    private val thread = HandlerThread("preview-render").apply { start() }
    private val worker = Handler(thread.looper)
    private val pending = AtomicReference<Scene?>(null)
    @Volatile private var latest: Scene? = null
    @Volatile private var surfaceReady = false

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val decodeOptions = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    private val matrixTransform = Matrix()
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val invertPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    private var lastX = 0f
    private var lastY = 0f
    private var gesture = false
    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                listener?.onScale(detector.scaleFactor)
                return true
            }
        },
    )

    init {
        holder.addCallback(this)
    }

    fun show(scene: Scene) {
        latest = scene
        if (pending.getAndSet(scene) == null) worker.post(::work)
    }

    fun clearCache() {
        worker.post { cache.evictAll() }
    }

    private fun work() {
        val scene = pending.getAndSet(null) ?: return
        if (surfaceReady) render(scene)
    }

    private fun frame(item: Item): Bitmap? {
        val slot = item.proxy.resolve(item.slot)
        if (slot < 0) return null
        val key = "${item.proxy.source.id}:$slot"
        cache.get(key)?.let { return it }
        val bytes = try {
            item.proxy.read(slot)
        } catch (_: java.io.IOException) {
            null
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun render(scene: Scene) {
        val bitmaps = scene.items.map { frame(it) }
        val canvas: Canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: RuntimeException) {
            holder.lockCanvas()
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val fit = min(canvas.width.toFloat() / scene.width, canvas.height.toFloat() / scene.height)
            val frameWidth = scene.width * fit
            val frameHeight = scene.height * fit
            val left = (canvas.width - frameWidth) / 2
            val top = (canvas.height - frameHeight) / 2
            canvas.save()
            canvas.clipRect(left, top, left + frameWidth, top + frameHeight)
            scene.items.forEachIndexed { i, item ->
                val bitmap = bitmaps[i] ?: return@forEachIndexed
                val source = item.proxy.source
                val p = Composition.place(source.displayWidth, source.displayHeight, scene.width, scene.height, item.layer)
                matrixTransform.setTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                matrixTransform.postScale(p.scaleX * frameWidth / bitmap.width, p.scaleY * frameHeight / bitmap.height)
                matrixTransform.postTranslate(
                    left + (1 + p.centerX) / 2 * frameWidth,
                    top + (1 - p.centerY) / 2 * frameHeight,
                )
                canvas.drawBitmap(bitmap, matrixTransform, if (item.invert) invertPaint else plainPaint)
            }
            canvas.restore()
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val scene = latest ?: return true
        val fit = min(width.toFloat() / scene.width, height.toFloat() / scene.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                gesture = false
                scaling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gesture) {
                    gesture = true
                    listener?.onGestureStart()
                }
                if (!scaling && event.pointerCount == 1) {
                    listener?.onMove(
                        (event.x - lastX) / (scene.width * fit / 2),
                        (event.y - lastY) / (scene.height * fit / 2),
                    )
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> scaling = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (gesture) listener?.onGestureEnd()
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        latest?.let { show(it) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // Wait for any frame in progress, since the surface is gone after this returns.
        val done = java.util.concurrent.CountDownLatch(1)
        worker.post { done.countDown() }
        done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun release() {
        worker.post {
            cache.evictAll()
            thread.quitSafely()
        }
    }

    private companion object {
        const val CACHE_BYTES = 128 * 1024 * 1024
    }
}
