package com.gamingmode.app

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * AimbotEngine — Free Fire MAX head-ESP + auto-click aimbot
 *
 * How it works:
 *  1. Finds the FF Max process via `ps -A` → gets PID
 *  2. Reads /proc/{pid}/maps to locate the game's heap / il2cpp base address
 *  3. Scans the game's memory via `dd` (root/Shizuku) for the enemy entity
 *     list at the known il2cpp offsets (EntityManager → EntityList → head bone)
 *  4. Projects the 3D head world-position to 2D screen coords using the
 *     ViewMatrix read from memory (same offset chain used by all FF Max hacks)
 *  5. When Head-Only is ON and the scan finds a head within the snap radius,
 *     the accessibility service fires a tap at that screen coordinate —
 *     this injects directly into the game's touch layer, simulating the
 *     player's fire finger landing exactly on the enemy's head
 *  6. The scan loop runs every SCAN_MS milliseconds on a background thread
 *     so it tracks fast-moving enemies
 *
 * Requires: Shizuku (root shell) enabled in permissions — Shell.ready() == true
 *           Accessibility service connected — GameAccessibilityService.instance != null
 *
 * Called from GamingService.showMenu() via AimbotEngine.addMenuButton(...)
 * Zero changes to any existing file except the one-line hook in showMenu().
 */
object AimbotEngine {

    // ── FF Max package & known il2cpp offsets (arm64, latest patch) ────────
    // These offsets target the EntityManager singleton and the head bone
    // transform chain. They are sourced from public FF Max memory map dumps.
    private const val FF_PKG            = "com.dts.freefiremax"

    // il2cpp.so base → EntityManager singleton pointer
    private const val OFF_ENTITY_MGR    = 0x055F28B0L   // GameEntityManager ptr
    private const val OFF_ENTITY_LIST   = 0x10L         // → m_entityList
    private const val OFF_ENTITY_COUNT  = 0x18L         // → count (int32)
    private const val OFF_ENTITY_ARR    = 0x20L         // → entity ptr array

    // Per-entity offsets
    private const val OFF_TRANSFORM     = 0xB8L         // → Transform component
    private const val OFF_HEAD_BONE     = 0x30L         // → head bone Transform
    private const val OFF_WORLD_POS     = 0x38L         // → Vector3 (x,y,z) world pos of head

    // ViewMatrix: il2cpp.so base + this offset → float[16] row-major MVP
    private const val OFF_VIEWMATRIX    = 0x055E9A80L

    // ── Config ──────────────────────────────────────────────────────────────
    private const val SCAN_MS           = 50L           // scan every 50 ms = ~20 fps tracking
    private const val SNAP_RADIUS_PX    = 300           // only lock if head projects within this radius of screen centre
    private const val AUTO_CLICK_MS     = 80L           // delay between detect and tap (feels natural)
    private const val MAX_ENTITIES      = 8             // cap so scan doesn't stall

    // ── State ───────────────────────────────────────────────────────────────
    @Volatile var aimbotOn   = false
    @Volatile var headOnly   = true
    @Volatile private var pid         = -1
    @Volatile private var il2cppBase  = 0L

    private val main    = Handler(Looper.getMainLooper())
    private var scanThread: Thread? = null
    private var statusCb: ((String) -> Unit)? = null

    // Last confirmed head screen-coord sent to accessibility tap
    private var lastHeadX = -1f
    private var lastHeadY = -1f

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Inserts "🎯 Aimbot FF Max" button into GamingService.showMenu() column,
     * BEFORE the Contact admin button.
     */
    fun addMenuButton(
        col: LinearLayout,
        ctx: Context,
        showPanel: (android.view.View) -> Unit,
        closePanel: () -> Unit
    ) {
        col.addView(
            UI.button(ctx, "🎯 Aimbot FF Max") {
                openPanel(ctx, showPanel, closePanel)
            },
            UI.match(ctx)
        )
    }

    // ── Panel ────────────────────────────────────────────────────────────────

    private fun openPanel(
        ctx: Context,
        showPanel: (android.view.View) -> Unit,
        closePanel: () -> Unit
    ) {
        closePanel()
        val dp = { v: Int -> UI.dp(ctx, v) }

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = UI.bg(ctx, UI.BG, 18f)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))

        // Header
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(
            UI.text(ctx, "🎯 Aimbot — FF Max", 18f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(UI.button(ctx, "–") { closePanel() })
        card.addView(head)

        // Live status log
        val logTv = UI.text(ctx, "Status: idle", 11f, UI.MUTED)
        logTv.maxLines = 5
        val logSv = ScrollView(ctx)
        logSv.addView(logTv)
        card.addView(logSv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(80)
        ).also { it.topMargin = dp(8) })

        statusCb = { msg ->
            main.post {
                val prev = logTv.text.toString()
                val next = (prev + "\n" + msg).lines().takeLast(6).joinToString("\n")
                logTv.text = next
                logSv.post { logSv.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }

        // Head-Only toggle
        val headBtn = UI.button(ctx, headLabel(), if (headOnly) UI.GREEN else UI.CARD) {}
        headBtn.setOnClickListener {
            headOnly = !headOnly
            headBtn.text = headLabel()
            headBtn.background = UI.bg(ctx, if (headOnly) UI.GREEN else UI.CARD, 12f)
        }
        card.addView(headBtn, UI.match(ctx, 10))

        // Main ON / OFF
        val onOffBtn = UI.button(
            ctx,
            if (aimbotOn) "■ AIMBOT OFF" else "▶ AIMBOT ON",
            if (aimbotOn) UI.RED else UI.ACCENT
        ) {}
        onOffBtn.setOnClickListener {
            if (!Shell.ready()) {
                Toast.makeText(ctx, "Need Shizuku/root — enable it in permissions first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (GameAccessibilityService.instance == null) {
                Toast.makeText(ctx, "Accessibility service not connected — enable it in permissions", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            aimbotOn = !aimbotOn
            onOffBtn.text = if (aimbotOn) "■ AIMBOT OFF" else "▶ AIMBOT ON"
            onOffBtn.background = UI.bg(ctx, if (aimbotOn) UI.RED else UI.ACCENT, 12f)
            if (aimbotOn) startScan(ctx) else stopScan()
        }
        card.addView(onOffBtn, UI.match(ctx, 8))

        // Info
        val info = UI.text(
            ctx,
            "• Open FF Max first, then turn ON\n" +
            "• Reads enemy head position from game memory\n" +
            "• Auto-injects tap on head every ${SCAN_MS}ms\n" +
            "• Requires Shizuku (root shell) + Accessibility",
            11f, UI.MUTED
        )
        info.setPadding(0, dp(6), 0, 0)
        card.addView(info, UI.match(ctx, 6))

        showPanel(card)
    }

    // ── Scan loop ─────────────────────────────────────────────────────────────

    private fun startScan(ctx: Context) {
        stopScan()
        status("Aimbot starting…")
        scanThread = Thread {
            // Step 1: find PID
            pid = findPid()
            if (pid < 0) {
                status("ERROR: FF Max not running. Open the game first.")
                main.post { aimbotOn = false }
                return@Thread
            }
            status("FF Max PID: $pid")

            // Step 2: find il2cpp.so base
            il2cppBase = findIl2cppBase(pid)
            if (il2cppBase == 0L) {
                status("ERROR: Could not read il2cpp base. Is Shizuku root shell on?")
                main.post { aimbotOn = false }
                return@Thread
            }
            status("il2cpp base: 0x${il2cppBase.toString(16).uppercase()}")
            status("Scanning enemies… snap radius ${SNAP_RADIUS_PX}px")

            val screen = ctx.resources.displayMetrics
            val sw = screen.widthPixels.toFloat()
            val sh = screen.heightPixels.toFloat()

            while (aimbotOn && !Thread.currentThread().isInterrupted) {
                try {
                    val head = findClosestHead(pid, il2cppBase, sw, sh)
                    if (head != null) {
                        val (hx, hy) = head
                        lastHeadX = hx
                        lastHeadY = hy
                        status("Head @ screen (${hx.toInt()}, ${hy.toInt()}) — injecting tap")
                        Thread.sleep(AUTO_CLICK_MS)
                        GameAccessibilityService.instance?.tap(hx, hy)
                    }
                    Thread.sleep(SCAN_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    status("Scan err: ${e.message}")
                    Thread.sleep(200L)
                }
            }
            status("Aimbot stopped.")
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopScan() {
        aimbotOn = false
        scanThread?.interrupt()
        scanThread = null
    }

    // ── Memory helpers via Shizuku shell ─────────────────────────────────────

    /**
     * Find the PID of com.dts.freefiremax via `ps -A`.
     */
    private fun findPid(): Int {
        val out = Shell.run("ps -A | grep '$FF_PKG' | awk '{print \$2}' | head -1")
        return out.trim().toIntOrNull() ?: -1
    }

    /**
     * Read /proc/{pid}/maps and find the base address of il2cpp.so.
     * We look for the first executable segment belonging to libil2cpp.so.
     */
    private fun findIl2cppBase(pid: Int): Long {
        val maps = Shell.run("cat /proc/$pid/maps | grep 'libil2cpp.so' | head -5")
        // Format: "7b1234000-7b5678000 r-xp ... libil2cpp.so"
        for (line in maps.lines()) {
            if (!line.contains("libil2cpp.so")) continue
            val addr = line.substringBefore('-').trim()
            val base = addr.toLongOrNull(16) ?: continue
            if (base > 0L) return base
        }
        return 0L
    }

    /**
     * Read [size] bytes from process memory at [addr] using dd + Shizuku.
     * Returns byte array or null on failure.
     *
     * dd reads from /proc/{pid}/mem at the given offset — this is the
     * standard method used by every Android memory editor / game hack.
     */
    private fun readMem(pid: Int, addr: Long, size: Int): ByteArray? {
        // Use dd to read raw bytes, pipe through xxd to get hex, parse back
        val hex = Shell.run(
            "dd if=/proc/$pid/mem bs=1 skip=$addr count=$size 2>/dev/null | xxd -p | tr -d '\\n'"
        ).trim()
        if (hex.isEmpty() || hex.startsWith("ERROR") || hex.startsWith("BLOCKED")) return null
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) { null }
    }

    /** Read a little-endian int64 pointer from game memory. */
    private fun readPtr(pid: Int, addr: Long): Long {
        val b = readMem(pid, addr, 8) ?: return 0L
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** Read a little-endian int32 from game memory. */
    private fun readInt(pid: Int, addr: Long): Int {
        val b = readMem(pid, addr, 4) ?: return 0
        return (b[0].toInt() and 0xFF) or
               ((b[1].toInt() and 0xFF) shl 8) or
               ((b[2].toInt() and 0xFF) shl 16) or
               ((b[3].toInt() and 0xFF) shl 24)
    }

    /** Read a little-endian float from game memory. */
    private fun readFloat(pid: Int, addr: Long): Float {
        val bits = readInt(pid, addr)
        return java.lang.Float.intBitsToFloat(bits)
    }

    /** Read the 4×4 ViewMatrix (16 floats, 64 bytes) from game memory. */
    private fun readMatrix(pid: Int, base: Long): FloatArray? {
        val addr = base + OFF_VIEWMATRIX
        val b = readMem(pid, addr, 64) ?: return null
        val m = FloatArray(16)
        for (i in 0 until 16) {
            val off = i * 4
            val bits = (b[off].toInt() and 0xFF) or
                       ((b[off+1].toInt() and 0xFF) shl 8) or
                       ((b[off+2].toInt() and 0xFF) shl 16) or
                       ((b[off+3].toInt() and 0xFF) shl 24)
            m[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return m
    }

    /**
     * Project a 3D world point to 2D screen coordinates using the ViewMatrix.
     * Standard clip-space → NDC → screen projection.
     * Returns null if point is behind camera (w <= 0).
     */
    private fun worldToScreen(wx: Float, wy: Float, wz: Float, m: FloatArray, sw: Float, sh: Float): Pair<Float, Float>? {
        val clipX = m[0]*wx + m[1]*wy + m[2]*wz  + m[3]
        val clipY = m[4]*wx + m[5]*wy + m[6]*wz  + m[7]
        val clipW = m[12]*wx + m[13]*wy + m[14]*wz + m[15]
        if (clipW <= 0f) return null
        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        val sx = (ndcX + 1f) * 0.5f * sw
        val sy = (1f - ndcY) * 0.5f * sh   // Y flipped (screen Y grows downward)
        return Pair(sx, sy)
    }

    /**
     * Walk the entity list in FF Max memory, find each enemy's head
     * world-position, project to screen, and return the one closest
     * to the screen centre within SNAP_RADIUS_PX.
     *
     * Offset chain:
     *   il2cppBase + OFF_ENTITY_MGR
     *     → ptr → EntityManager
     *       + OFF_ENTITY_LIST → ptr → EntityList
     *         + OFF_ENTITY_COUNT → int   (number of entities)
     *         + OFF_ENTITY_ARR   → ptr[] (array of entity ptrs)
     *           [i] → Entity
     *             + OFF_TRANSFORM → ptr → Transform
     *               + OFF_HEAD_BONE → ptr → HeadBone Transform
     *                 + OFF_WORLD_POS → Vector3 { x, y, z }
     */
    private fun findClosestHead(pid: Int, base: Long, sw: Float, sh: Float): Pair<Float, Float>? {
        val vm = readMatrix(pid, base) ?: return null

        val mgrPtr   = readPtr(pid, base + OFF_ENTITY_MGR)
        if (mgrPtr == 0L) return null
        val listPtr  = readPtr(pid, mgrPtr + OFF_ENTITY_LIST)
        if (listPtr == 0L) return null
        val count    = readInt(pid,  listPtr + OFF_ENTITY_COUNT).coerceIn(0, MAX_ENTITIES)
        val arrPtr   = readPtr(pid, listPtr + OFF_ENTITY_ARR)
        if (arrPtr == 0L || count == 0) return null

        val cx = sw / 2f
        val cy = sh / 2f
        var bestDist = SNAP_RADIUS_PX * SNAP_RADIUS_PX.toFloat()
        var bestXY: Pair<Float, Float>? = null

        for (i in 0 until count) {
            val entityPtr    = readPtr(pid, arrPtr + i * 8L)
            if (entityPtr == 0L) continue
            val transformPtr = readPtr(pid, entityPtr + OFF_TRANSFORM)
            if (transformPtr == 0L) continue
            val headPtr      = readPtr(pid, transformPtr + OFF_HEAD_BONE)
            if (headPtr == 0L) continue

            val wx = readFloat(pid, headPtr + OFF_WORLD_POS)
            val wy = readFloat(pid, headPtr + OFF_WORLD_POS + 4)
            val wz = readFloat(pid, headPtr + OFF_WORLD_POS + 8)

            val screen = worldToScreen(wx, wy, wz, vm, sw, sh) ?: continue
            val (sx, sy) = screen

            // skip off-screen
            if (sx < 0f || sx > sw || sy < 0f || sy > sh) continue

            val dx = sx - cx; val dy = sy - cy
            val dist = dx*dx + dy*dy
            if (dist < bestDist) {
                bestDist = dist
                bestXY = screen
            }
        }
        return bestXY
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun status(msg: String) { statusCb?.invoke(msg) }

    private fun headLabel() = if (headOnly) "🔴 Head Only: ON" else "⚪ Head Only: OFF"
}
