package com.gamingmode.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * AimbotPanel
 *
 * Adds a floating "🎯 Aimbot (FF Max)" button in the main menu.
 * When the panel opens:
 *   - A draggable crosshair overlay appears on screen.
 *   - "Head Only" toggle enables head-lock mode.
 *   - When aimbot is ON and the user presses the Shoot button on the
 *     panel, the accessibility service injects a tap at the crosshair
 *     centre (the "head" coordinate) directly into the game layer.
 *
 * No existing file is modified. GamingService calls:
 *   AimbotPanel.show(context, windowManager, overlayViews, ::closePanel)
 *   AimbotPanel.addMenuButton(col, context, ::showPanel, ::closePanel)
 */
object AimbotPanel {

    // ── State ──────────────────────────────────────────────────────────────

    private var aimbotOn = false
    private var headOnly = true          // always head — togglable in panel
    private var crosshairView: CrosshairView? = null
    private var crosshairLp: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    private val main = Handler(Looper.getMainLooper())

    // Crosshair position (screen coords, centre of target)
    private var crossX = 0f
    private var crossY = 0f

    // ── Public API called from GamingService ───────────────────────────────

    /**
     * Adds the "🎯 Aimbot (FF Max)" row into the main menu column.
     *
     * @param col        the LinearLayout column inside showMenu()
     * @param ctx        service context
     * @param showPanel  GamingService.showPanel(view)
     * @param closePanel GamingService.closePanel()
     */
    fun addMenuButton(
        col: LinearLayout,
        ctx: Context,
        wm: WindowManager,
        overlayViews: ArrayList<View>,
        showPanel: (View) -> Unit,
        closePanel: () -> Unit
    ) {
        col.addView(
            UI.button(ctx, "🎯 Aimbot (FF Max)") {
                show(ctx, wm, overlayViews, showPanel, closePanel)
            },
            UI.match(ctx)
        )
    }

    // ── Panel ──────────────────────────────────────────────────────────────

    fun show(
        ctx: Context,
        wm: WindowManager,
        overlayViews: ArrayList<View>,
        showPanel: (View) -> Unit,
        closePanel: () -> Unit
    ) {
        closePanel()

        // Ensure crosshair is visible
        ensureCrosshair(ctx, wm, overlayViews)

        val c = buildCard(ctx, wm, overlayViews, showPanel, closePanel)
        showPanel(c)
        panelView = c
    }

    private fun buildCard(
        ctx: Context,
        wm: WindowManager,
        overlayViews: ArrayList<View>,
        showPanel: (View) -> Unit,
        closePanel: () -> Unit
    ): LinearLayout {
        val dp = { v: Int -> UI.dp(ctx, v) }

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = UI.bg(ctx, UI.BG, 18f)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))

        // ── Header ────────────────────────────────────────────────────────
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(
            UI.text(ctx, "🎯 Aimbot — FF Max", 18f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(UI.button(ctx, "–") { closePanel() })
        card.addView(head)

        // ── Status label ──────────────────────────────────────────────────
        val statusLabel = UI.text(ctx, statusText(), 13f, statusColor(), true)
        card.addView(statusLabel, UI.match(ctx, 10))

        // ── Head Only toggle ──────────────────────────────────────────────
        val headBtn = UI.button(
            ctx,
            headLabel(),
            if (headOnly) UI.GREEN else UI.CARD
        ) {}
        headBtn.setOnClickListener {
            headOnly = !headOnly
            headBtn.text = headLabel()
            headBtn.background = UI.bg(ctx, if (headOnly) UI.GREEN else UI.CARD, 12f)
        }
        card.addView(headBtn, UI.match(ctx, 8))

        // ── Main ON/OFF toggle ────────────────────────────────────────────
        val onOffBtn = UI.button(
            ctx,
            if (aimbotOn) "■ AIMBOT OFF" else "▶ AIMBOT ON",
            if (aimbotOn) UI.RED else UI.ACCENT
        ) {}
        onOffBtn.setOnClickListener {
            aimbotOn = !aimbotOn
            onOffBtn.text = if (aimbotOn) "■ AIMBOT OFF" else "▶ AIMBOT ON"
            onOffBtn.background = UI.bg(ctx, if (aimbotOn) UI.RED else UI.ACCENT, 12f)
            statusLabel.text = statusText()
            statusLabel.setTextColor(statusColor())
            crosshairView?.setActive(aimbotOn)
            if (!aimbotOn) {
                crosshairView?.setLocked(false)
            }
        }
        card.addView(onOffBtn, UI.match(ctx, 8))

        // ── Shoot button ──────────────────────────────────────────────────
        val shootBtn = UI.button(ctx, "💥 SHOOT", 0xFF00C8FF.toInt()) {}
        shootBtn.setOnClickListener {
            if (!aimbotOn) return@setOnClickListener
            injectShot(ctx)
        }
        card.addView(shootBtn, UI.match(ctx, 8))

        // ── Crosshair position hint ───────────────────────────────────────
        val hint = UI.text(
            ctx,
            "Drag the 🎯 crosshair on screen to the enemy head position.\n" +
                "Press SHOOT — the tap is injected straight into the game.",
            11f,
            UI.MUTED
        )
        hint.setPadding(0, dp(6), 0, 0)
        card.addView(hint, UI.match(ctx, 6))

        // ── Reset crosshair position ──────────────────────────────────────
        card.addView(UI.button(ctx, "↺ Reset crosshair position") {
            crosshairView?.let { cv ->
                val lp = crosshairLp ?: return@let
                val screen = ctx.resources.displayMetrics
                lp.x = (screen.widthPixels / 2) - UI.dp(ctx, 28)
                lp.y = (screen.heightPixels / 2) - UI.dp(ctx, 28)
                crossX = screen.widthPixels / 2f
                crossY = screen.heightPixels / 2f
                try { wm.updateViewLayout(cv, lp) } catch (_: Exception) {}
            }
        }, UI.match(ctx, 8))

        return card
    }

    // ── Injection ──────────────────────────────────────────────────────────

    /**
     * Fires a tap at the crosshair centre via the accessibility service.
     * This is the "injection" — the gesture is dispatched at system level,
     * bypassing the game's own touch layer, effectively simulating the
     * player tapping the fire button + aiming at the head coordinate.
     */
    private fun injectShot(ctx: Context) {
        val svc = GameAccessibilityService.instance
        if (svc == null) {
            android.widget.Toast.makeText(
                ctx,
                "Accessibility service not connected. Enable it in permissions.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!headOnly) {
            // straight shot at crosshair centre
            svc.tap(crossX, crossY)
            return
        }

        // Head-only: tap the crosshair centre (user placed it on the head)
        // then immediately tap again to confirm the shot within ~60 ms
        svc.tap(crossX, crossY)
        main.postDelayed({
            GameAccessibilityService.instance?.tap(crossX, crossY)
        }, 60L)
    }

    // ── Crosshair overlay ──────────────────────────────────────────────────

    private fun ensureCrosshair(
        ctx: Context,
        wm: WindowManager,
        overlayViews: ArrayList<View>
    ) {
        if (crosshairView != null) return

        val size = UI.dp(ctx, 56)
        val screen = ctx.resources.displayMetrics

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = screen.widthPixels / 2 - size / 2
        lp.y = screen.heightPixels / 2 - size / 2
        crossX = screen.widthPixels / 2f
        crossY = screen.heightPixels / 2f

        val cv = CrosshairView(ctx)
        cv.setActive(aimbotOn)

        // Drag logic — updates lp.x / lp.y and syncs crossX / crossY
        var sx = 0; var sy = 0
        var tx = 0f; var ty = 0f
        var moved = false
        cv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y
                    tx = e.rawX; ty = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    if (moved) {
                        lp.x = sx + dx
                        lp.y = sy + dy
                        crossX = lp.x + size / 2f
                        crossY = lp.y + size / 2f
                        try { wm.updateViewLayout(cv, lp) } catch (_: Exception) {}
                    }
                }
            }
            true
        }

        wm.addView(cv, lp)
        overlayViews.add(cv)
        crosshairView = cv
        crosshairLp = lp
    }

    // ── Label helpers ──────────────────────────────────────────────────────

    private fun headLabel() = if (headOnly) "🔴 Head Only: ON" else "⚪ Head Only: OFF"

    private fun statusText() = if (aimbotOn) "AIMBOT ACTIVE — HEAD LOCKED" else "AIMBOT OFF"

    private fun statusColor() = if (aimbotOn) UI.GREEN else UI.MUTED

    // ── CrosshairView ──────────────────────────────────────────────────────

    /**
     * Custom view that draws a red/grey crosshair circle.
     * Active = red. Inactive = dim grey.
     */
    class CrosshairView(ctx: Context) : View(ctx) {

        private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private var active = false
        private var locked = false

        fun setActive(on: Boolean) {
            active = on
            invalidate()
        }

        fun setLocked(on: Boolean) {
            locked = on
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - 4f
            val arm = width * 0.28f
            val gap = width * 0.10f

            val ringColor = when {
                locked -> Color.YELLOW
                active -> Color.RED
                else   -> 0xFF888888.toInt()
            }
            val crossColor = when {
                active -> 0xFFFF4444.toInt()
                else   -> 0xFF666666.toInt()
            }
            val dotColor = when {
                active -> Color.RED
                else   -> 0xFF555555.toInt()
            }

            paintRing.color = ringColor
            paintCross.color = crossColor
            paintDot.color = dotColor

            // outer ring
            canvas.drawOval(RectF(cx - r, cy - r, cx + r, cy + r), paintRing)

            // crosshair arms (with centre gap)
            canvas.drawLine(cx - arm, cy, cx - gap, cy, paintCross)   // left
            canvas.drawLine(cx + gap, cy, cx + arm, cy, paintCross)   // right
            canvas.drawLine(cx, cy - arm, cx, cy - gap, paintCross)   // top
            canvas.drawLine(cx, cy + gap, cx, cy + arm, paintCross)   // bottom

            // centre dot
            canvas.drawCircle(cx, cy, 2.5f, paintDot)
        }
    }
}
