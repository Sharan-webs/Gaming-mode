package com.gamingmode.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A floating "TV": a live, moving picture of the screen (about 3 frames a second) with AI detection boxes on top.
 * Touch the TV with the target button on and it taps the real screen at that spot.
 */
class LiveView(
    private val ctx: Context,
    private val wm: WindowManager,
    private val key: () -> String,
    private val models: () -> String,
    private val say: (String) -> Unit,
    private val onClosed: () -> Unit
) {
    private class Box(val label: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private class FrameView(c: Context) : View(c) {
        var frame: Bitmap? = null
        var boxes: List<Box> = emptyList()
        private val d = c.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(0xFF2EB67D.toInt(), 0xFFFFB020.toInt(), 0xFF4F7CFF.toInt(), 0xFFE5484D.toInt(), 0xFFB05CFF.toInt())

        init {
            txt.color = Color.WHITE
            txt.textSize = 10f * d
        }

        override fun onDraw(canvas: Canvas) {
            val f = frame
            if (f != null) canvas.drawBitmap(f, null, Rect(0, 0, width, height), null) else canvas.drawColor(0xFF000000.toInt())
            for ((i, b) in boxes.withIndex()) {
                val l = b.x1 / 1000f * width
                val t = b.y1 / 1000f * height
                val r = b.x2 / 1000f * width
                val bt = b.y2 / 1000f * height
                paint.color = colors[i % colors.size]
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                canvas.drawRect(l, t, r, bt, paint)
                paint.style = Paint.Style.FILL
                val ty = maxOf(t, txt.textSize + 4f * d)
                canvas.drawRect(l, ty - txt.textSize - 3f * d, l + txt.measureText(b.label) + 8f * d, ty + 2f * d, paint)
                canvas.drawText(b.label, l + 4f * d, ty - 2f * d, txt)
            }
        }
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var autoDetect = false

    @Volatile
    private var detectNow = false

    @Volatile
    private var detecting = false
    private var control = false
    private var root: LinearLayout? = null
    private var frameView: FrameView? = null

    private val system = """
You detect objects on a phone or game screenshot. Reply ONLY with JSON:
{"items":[{"label":"short name","x1":0,"y1":0,"x2":1000,"y2":1000}]}
Coordinates are 0-1000 relative to the image (0,0 = top-left). List every distinct character, enemy, item, chest, button, icon and readable text block. Max 25 items.
""".trimIndent()

    private fun small(label: String, color: Int = UI.CARD, onClick: () -> Unit): Button {
        val b = UI.button(ctx, label, color, onClick)
        b.textSize = 11f
        b.setPadding(UI.dp(ctx, 8), UI.dp(ctx, 6), UI.dp(ctx, 8), UI.dp(ctx, 6))
        return b
    }

    private fun mark(b: Button, on: Boolean) {
        b.background = if (on) UI.grad(ctx, 0xFF7C4DFF.toInt(), 0xFF4F7CFF.toInt(), 12f) else UI.bg(ctx, UI.CARD, 12f)
    }

    fun setAlpha(a: Float) {
        root?.alpha = a
    }

    fun start() {
        val dm = ctx.resources.displayMetrics
        val w = minOf((dm.widthPixels * 0.5f).toInt(), UI.dp(ctx, 320))
        val h = (w * dm.heightPixels.toFloat() / dm.widthPixels).toInt()
        val pad = UI.dp(ctx, 6)

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.background = UI.panel(ctx)
        box.setPadding(pad, pad, pad, pad)

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = UI.dp(ctx, 10)
        lp.y = UI.dp(ctx, 60)

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = UI.text(ctx, "● LIVE", 12f, UI.RED, true)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        title.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x
                    sy = lp.y
                    tx = e.rawX
                    ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    try {
                        wm.updateViewLayout(box, lp)
                    } catch (ex: Exception) {
                    }
                }
            }
            true
        }

        val autoBtn = small("Auto") {}
        autoBtn.setOnClickListener {
            autoDetect = !autoDetect
            mark(autoBtn, autoDetect)
            if (autoDetect) detectNow = true
        }
        val ctlBtn = small("🎯") {}
        ctlBtn.setOnClickListener {
            control = !control
            mark(ctlBtn, control)
            say(if (control) "Remote ON: touching the TV taps the real screen." else "Remote OFF")
        }
        head.addView(small("🔍") { detectNow = true }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(autoBtn)
        head.addView(ctlBtn)
        head.addView(small("✕", UI.RED) { stop() })
        box.addView(head)

        val fv = FrameView(ctx)
        fv.setOnTouchListener { v, e ->
            if (control && e.actionMasked == MotionEvent.ACTION_UP) {
                val m = ctx.resources.displayMetrics
                val px = e.x / v.width * m.widthPixels
                val py = e.y / v.height * m.heightPixels
                GameAccessibilityService.instance?.stroke(listOf(Pair(px, py)), 60L, null)
            }
            true
        }
        val fl = LinearLayout.LayoutParams(w, h)
        fl.topMargin = pad
        box.addView(fv, fl)

        wm.addView(box, lp)
        root = box
        frameView = fv
        running = true
        Thread { loop() }.start()
    }

    fun stop() {
        if (!running && root == null) return
        running = false
        val r = root
        root = null
        frameView = null
        if (r != null) {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
            }
        }
        onClosed()
    }

    private fun loop() {
        var lastDetect = 0L
        while (running) {
            val svc = GameAccessibilityService.instance
            if (svc == null) {
                Thread.sleep(500)
                continue
            }
            val latch = CountDownLatch(1)
            var shot: Bitmap? = null
            ui.post {
                svc.screenshot { b ->
                    shot = b
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
            val b = shot
            if (b != null) {
                val w = 640
                val h = (b.height * (w.toFloat() / b.width)).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(b, w, h, true)
                b.recycle()
                val now = SystemClock.uptimeMillis()
                var jpg: ByteArray? = null
                if (!detecting && (detectNow || (autoDetect && now - lastDetect > 15000L))) {
                    detectNow = false
                    lastDetect = now
                    val out = ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    jpg = out.toByteArray()
                }
                ui.post {
                    val fv = frameView
                    if (fv != null) {
                        val old = fv.frame
                        fv.frame = small
                        fv.invalidate()
                        old?.recycle()
                    } else {
                        small.recycle()
                    }
                }
                if (jpg != null) {
                    detecting = true
                    val data = jpg
                    Thread { detect(data) }.start()
                }
            }
            Thread.sleep(40)
        }
    }

    private fun parse(reply: String): List<Box> {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        if (a < 0 || b <= a) return emptyList()
        val out = ArrayList<Box>()
        try {
            val arr = JSONObject(reply.substring(a, b + 1)).getJSONArray("items")
            for (i in 0 until minOf(arr.length(), 30)) {
                val o = arr.getJSONObject(i)
                out.add(
                    Box(
                        o.optString("label", "?").take(24),
                        o.optDouble("x1", 0.0).toFloat().coerceIn(0f, 1000f),
                        o.optDouble("y1", 0.0).toFloat().coerceIn(0f, 1000f),
                        o.optDouble("x2", 0.0).toFloat().coerceIn(0f, 1000f),
                        o.optDouble("y2", 0.0).toFloat().coerceIn(0f, 1000f)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun detect(jpg: ByteArray) {
        try {
            var reply: String? = null
            var err: Exception? = null
            for (m in models().split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                try {
                    reply = GroqClient.chat(key(), m, system, "Detect everything.", listOf(jpg), 900)
                    break
                } catch (e: Exception) {
                    err = e
                }
            }
            if (reply == null) {
                ui.post { say("Detection failed: " + (err?.message ?: "no model").take(120)) }
                return
            }
            val boxes = parse(reply)
            ui.post {
                frameView?.boxes = boxes
                frameView?.invalidate()
                if (boxes.isEmpty()) say("Nothing detected.")
            }
        } finally {
            detecting = false
        }
    }
}
