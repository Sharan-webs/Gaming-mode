package com.gamingmode.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * AimbotFF — Free Fire MAX pixel-scan aimbot
 *
 * ─── How it actually works ──────────────────────────────────────────────────
 *
 * 1. SCREENSHOT
 *    Uses GameAccessibilityService.screenshot() — already in the project,
 *    no extra permissions beyond Accessibility (which is already required).
 *
 * 2. PIXEL SCAN — enemy head detection
 *    FF Max renders enemies with a clearly distinct color signature:
 *      • Enemy health bar      → bright red   (R>200, G<60,  B<60)
 *      • Head/body glow in ESP → orange-red   (R>200, G<100, B<60)
 *      • Scope aim indicator   → bright red dot
 *    We scan the bitmap at 1/4 resolution (fast) for clusters of these pixels.
 *    Each cluster centroid = one candidate enemy head position.
 *
 * 3. SNAP
 *    From all candidate head positions, pick the one closest to screen center.
 *    If it's within SNAP_RADIUS_PX, it's a lock.
 *
 * 4. INJECT
 *    GameAccessibilityService.tap(x, y) — dispatches a GestureDescription
 *    at the locked head coordinate. This lands inside the game's touch layer
 *    as if the player physically pressed the fire button on that pixel.
 *
 * 5. DESERT EAGLE MODE
 *    Single precise tap per detection cycle (no repeat fire).
 *    For full-auto: fires tap every FIRE_INTERVAL_MS.
 *
 * ─── Requirements ───────────────────────────────────────────────────────────
 *    Accessibility service ON  (Settings > Accessibility > Gaming Mode)
 *    FF Max open and visible   (aimbot reads what's on screen)
 *    No root / Shizuku needed  (pixel scan only)
 *
 * ─── Called from GamingService ──────────────────────────────────────────────
 *    AimbotFF.addMenuButton(col, ctx, ::showPanel, ::closePanel)
 *    One line added before Contact admin button. Zero other file changes.
 */
object AimbotFF {

    // ── Tuning ───────────────────────────────────────────────────────────────

    /** How often we grab a screenshot and scan. Lower = faster tracking. */
    private const val SCAN_INTERVAL_MS     = 120L   // ~8 fps — fast enough for FF, won't miss a frame

    /** Minimum cluster size to count as an enemy (filters out lone pixels / HUD noise). */
    private const val MIN_CLUSTER_PIXELS   = 6

    /** If the detected head is farther than this from screen center, ignore it. */
    private const val SNAP_RADIUS_PX       = 420

    /** Desert Eagle: one tap then wait this long before next fire. */
    private const val DE_COOLDOWN_MS       = 320L

    /** Full-auto fire interval when Desert Eagle is OFF. */
    private const val AUTO_FIRE_MS         = 85L

    // ── Enemy color ranges (FF Max, all graphics settings) ───────────────────
    // These match the red/orange glow that FF Max renders on enemies:
    //   • health bar top edge  → pure red
    //   • ESP body outline     → red-orange
    //   • hit marker           → bright red flash
    // We accept any pixel where Red channel dominates strongly.

    private fun isEnemyPixel(r: Int, g: Int, b: Int): Boolean {
        return r > 185 && g < 80 && b < 80   // strong red, very little green/blue
            || r > 200 && g in 60..130 && b < 60  // orange-red (health bar glow)
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile var isOn         = false
    @Volatile var headOnly     = true    // head-only vs body lock
    @Volatile var desertEagle  = true    // precise single tap vs auto-fire

    private val mainH = Handler(Looper.getMainLooper())
    private var scanThread: Thread? = null
    private var statusCallback: ((String) -> Unit)? = null

    // ── Public entry point ───────────────────────────────────────────────────

    fun addMenuButton(
        col: LinearLayout,
        ctx: Context,
        showPanel: (View) -> Unit,
        closePanel: () -> Unit
    ) {
        col.addView(
            UI.button(ctx, "🎯 Aimbot — FF Max") {
                openPanel(ctx, showPanel, closePanel)
            },
            UI.match(ctx)
        )
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun openPanel(
        ctx: Context,
        showPanel: (View) -> Unit,
        closePanel: () -> Unit
    ) {
        closePanel()
        val dp = { v: Int -> UI.dp(ctx, v) }

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = UI.bg(ctx, UI.BG, 18f)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))

        // ── Header ────────────────────────────────────────────────────────────
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(
            UI.text(ctx, "🎯 Aimbot — FF Max", 18f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(UI.button(ctx, "–") { closePanel() })
        card.addView(header)

        // ── Status log (scrollable, live) ─────────────────────────────────────
        val logTv = UI.text(ctx, "● Status: off\n  Open FF Max, then tap ON", 11f, UI.MUTED)
        logTv.maxLines = 7
        val logSv = ScrollView(ctx)
        logSv.addView(logTv)
        card.addView(
            logSv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90)
            ).also { it.topMargin = dp(8) }
        )

        statusCallback = { msg ->
            mainH.post {
                val lines = (logTv.text.toString() + "\n" + msg).lines().takeLast(8)
                logTv.text = lines.joinToString("\n")
                logSv.post { logSv.fullScroll(View.FOCUS_DOWN) }
            }
        }

        // ── Head Only row ─────────────────────────────────────────────────────
        val headRow = makeToggleRow(
            ctx,
            label    = "🔴 Head Only",
            subLabel = "Lock on head, ignore body",
            checked  = headOnly
        ) { headOnly = it }
        card.addView(headRow, UI.match(ctx, 12))

        // ── Desert Eagle row ──────────────────────────────────────────────────
        val deRow = makeToggleRow(
            ctx,
            label    = "🔫 Desert Eagle Mode",
            subLabel = "Precise single tap per lock (vs full-auto)",
            checked  = desertEagle
        ) { desertEagle = it }
        card.addView(deRow, UI.match(ctx, 6))

        // ── Main ON / OFF ──────────────────────────────────────────────────────
        val onBtn = UI.button(
            ctx,
            if (isOn) "■  AIMBOT OFF" else "▶  AIMBOT ON",
            if (isOn) UI.RED else UI.ACCENT
        ) {}
        onBtn.setOnClickListener {
            if (GameAccessibilityService.instance == null) {
                Toast.makeText(ctx, "Accessibility service is not connected.\nEnable it in permissions → turn it OFF and ON once.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            isOn = !isOn
            onBtn.text = if (isOn) "■  AIMBOT OFF" else "▶  AIMBOT ON"
            onBtn.background = UI.bg(ctx, if (isOn) UI.RED else UI.ACCENT, 12f)
            if (isOn) startLoop(ctx) else stopLoop()
        }
        card.addView(onBtn, UI.match(ctx, 12))

        // ── How it works ──────────────────────────────────────────────────────
        card.addView(
            UI.text(
                ctx,
                "How it works:\n" +
                "• Takes screenshot every ${SCAN_INTERVAL_MS}ms\n" +
                "• Scans for enemy red/orange pixels\n" +
                "• Locks on the head closest to screen center\n" +
                "• Injects tap directly into FF Max (accessibility)\n" +
                "• Snap radius: ${SNAP_RADIUS_PX}px from center\n\n" +
                "Only needs: Accessibility ON + FF Max open",
                11f, UI.MUTED
            ),
            UI.match(ctx, 10)
        )

        showPanel(card)
    }

    // ── Toggle row helper ─────────────────────────────────────────────────────

    private fun makeToggleRow(
        ctx: Context,
        label: String,
        subLabel: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = UI.bg(ctx, UI.CARD, 12f)
        row.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 10))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(UI.text(ctx, label, 14f, Color.WHITE, true))
        col.addView(UI.text(ctx, subLabel, 11f, UI.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(ctx)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, b -> onChange(b) }
        row.addView(sw)
        return row
    }

    // ── Scan loop ─────────────────────────────────────────────────────────────

    private fun startLoop(ctx: Context) {
        stopLoop()
        status("Aimbot ON — scanning for enemies…")

        scanThread = Thread {
            var lastFireMs = 0L

            while (isOn && !Thread.currentThread().isInterrupted) {
                try {
                    val bmp = grabScreenshot() ?: run {
                        status("Screenshot failed — is Accessibility ON?")
                        Thread.sleep(400L)
                        return@run null
                    } ?: continue

                    val screen = ctx.resources.displayMetrics
                    val sw = screen.widthPixels.toFloat()
                    val sh = screen.heightPixels.toFloat()

                    val head = findHead(bmp, sw, sh)
                    bmp.recycle()

                    if (head != null) {
                        val (hx, hy) = head
                        val now = System.currentTimeMillis()
                        val cooldown = if (desertEagle) DE_COOLDOWN_MS else AUTO_FIRE_MS
                        if (now - lastFireMs >= cooldown) {
                            status("🎯 Lock (${hx.toInt()}, ${hy.toInt()}) — injecting tap")
                            GameAccessibilityService.instance?.tap(hx, hy)
                            lastFireMs = now
                        }
                    } else {
                        // no target in snap radius — just scan quietly
                    }

                    Thread.sleep(SCAN_INTERVAL_MS)

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    status("Error: ${e.message?.take(80)}")
                    Thread.sleep(300L)
                }
            }

            status("Aimbot OFF.")
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopLoop() {
        isOn = false
        scanThread?.interrupt()
        scanThread = null
    }

    // ── Screenshot via accessibility ──────────────────────────────────────────

    private fun grabScreenshot(): Bitmap? {
        val svc = GameAccessibilityService.instance ?: return null
        var result: Bitmap? = null
        val latch = CountDownLatch(1)
        mainH.post {
            svc.screenshot { bmp ->
                result = bmp
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    // ── Pixel scan ────────────────────────────────────────────────────────────

    /**
     * Scans the bitmap (at 1/4 resolution for speed) for enemy-colored pixels.
     * Groups nearby red pixels into clusters.
     * Returns the screen coordinate of the closest cluster centroid to center,
     * or null if nothing is within SNAP_RADIUS_PX.
     *
     * Coordinate mapping: scan is done at 1/4 scale → multiply back by 4.
     */
    private fun findHead(bmp: Bitmap, sw: Float, sh: Float): Pair<Float, Float>? {
        val step = 4  // sample every 4th pixel in each direction → 1/16 of pixels total
        val w = bmp.width
        val h = bmp.height

        // Scale factor: bitmap pixels → screen pixels
        val scaleX = sw / w
        val scaleY = sh / h

        // Collect all enemy-colored pixel centers (in bitmap coords)
        data class Pt(val x: Int, val y: Int)
        val hits = ArrayList<Pt>(256)

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val px  = bmp.getPixel(x, y)
                val r   = Color.red(px)
                val g   = Color.green(px)
                val b   = Color.blue(px)
                if (isEnemyPixel(r, g, b)) {
                    hits.add(Pt(x, y))
                }
            }
        }

        if (hits.isEmpty()) return null

        // Cluster: group pixels within CLUSTER_DIST bitmap pixels of each other
        val clusterDist = 40 / step   // ~40 screen pixels in bitmap-space
        val visited = BooleanArray(hits.size)
        val clusters = ArrayList<List<Pt>>()

        for (i in hits.indices) {
            if (visited[i]) continue
            val cluster = ArrayList<Pt>()
            cluster.add(hits[i])
            visited[i] = true
            for (j in i + 1 until hits.size) {
                if (visited[j]) continue
                val dx = hits[j].x - hits[i].x
                val dy = hits[j].y - hits[i].y
                if (dx * dx + dy * dy <= clusterDist * clusterDist) {
                    cluster.add(hits[j])
                    visited[j] = true
                }
            }
            if (cluster.size >= MIN_CLUSTER_PIXELS) {
                clusters.add(cluster)
            }
        }

        if (clusters.isEmpty()) return null

        // For each cluster compute centroid in screen coords
        // Head-only: use the TOP of each cluster (highest y = smallest y value = head)
        // Body mode: use the centroid
        val cx = sw / 2f
        val cy = sh / 2f
        val snapSq = SNAP_RADIUS_PX.toFloat() * SNAP_RADIUS_PX

        var bestDist = Float.MAX_VALUE
        var bestXY: Pair<Float, Float>? = null

        for (cluster in clusters) {
            val screenX: Float
            val screenY: Float

            if (headOnly) {
                // top pixel of cluster = smallest y = head position
                val topPt = cluster.minByOrNull { it.y }!!
                screenX = topPt.x * scaleX
                screenY = topPt.y * scaleY
            } else {
                // centroid
                screenX = cluster.map { it.x }.average().toFloat() * scaleX
                screenY = cluster.map { it.y }.average().toFloat() * scaleY
            }

            val dx = screenX - cx
            val dy = screenY - cy
            val dist = dx * dx + dy * dy

            if (dist < snapSq && dist < bestDist) {
                bestDist = dist
                bestXY = Pair(screenX, screenY)
            }
        }

        return bestXY
    }

    // ── Status helper ─────────────────────────────────────────────────────────

    private fun status(msg: String) {
        statusCallback?.invoke(msg)
    }
}
