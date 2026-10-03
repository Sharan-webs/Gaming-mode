package com.gamingmode.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class GamingService : Service(), Agent.Hooks {

    companion object {
        @Volatile
        var running = false
        const val CH = "gm_run"
        const val CH_MACRO = "gm_macro"
        const val ADMIN_URL = "https://www.instagram.com/sharan.in__"
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var agent: Agent
    private lateinit var coder: Coder
    private val main = Handler(Looper.getMainLooper())

    private val overlayViews = ArrayList<View>()
    private var icon: View? = null
    private var iconLp: WindowManager.LayoutParams? = null
    private var iconAttached = false
    private var inputMinimized = false
    private var panel: View? = null
    private var input: View? = null
    private var inputLp: WindowManager.LayoutParams? = null
    private var inputEt: EditText? = null
    private var statusTv: TextView? = null
    private var statusScroll: ScrollView? = null
    private var codeMode = false
    private var chatCol: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var liveTv: TextView? = null
    private var liveAnim: ObjectAnimator? = null
    private var featStatus: TextView? = null
    private val feed = ArrayList<String>()
    private var consoleProc: Process? = null
    private var liveView: LiveView? = null

    private val macroButtons = HashMap<String, View>()
    private val scriptButtons = HashMap<String, View>()
    private val runners = HashMap<String, ScriptRunner>()
    private val playing = HashSet<String>()

    private var recCapture: View? = null
    private var recCaptureLp: WindowManager.LayoutParams? = null
    private var recStop: View? = null
    private var recStrokes = ArrayList<Stroke>()
    private var recStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SHOW_ICON") showIcon()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("gm", MODE_PRIVATE)
        agent = Agent(this, this)
        coder = Coder(this, this)
        createChannels()
        Keys.openrouter = prefs.getString("or_key", "") ?: ""
        Keys.gemini = prefs.getString("g_key", "") ?: ""
        Keys.anthropic = prefs.getString("a_key", "") ?: ""
        Master.enableAccessibility(this)
        Thread { if (Shell.ready()) Shell.grantSelf(this) }.start()
        val showPi = PendingIntent.getService(
            this, 0, Intent(this, GamingService::class.java).setAction("SHOW_ICON"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CH)
            .setContentTitle("Gaming Mode is running")
            .setContentText("Tap the floating 🎮 icon")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_menu_view, "Show icon", showPi)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        running = true
        addIcon()
    }

    override fun onDestroy() {
        agent.stop()
        coder.stop()
        for (r in runners.values) r.stop()
        liveView?.stop()
        for (v in ArrayList(overlayViews)) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        overlayViews.clear()
        getSystemService(NotificationManager::class.java).cancel(2)
        running = false
        super.onDestroy()
    }

    // ---------- Agent hooks ----------

    override fun status(t: String) {
        main.post {
            feed.add(t)
            if (feed.size > 300) feed.removeAt(0)
            val terminal = listOf("Done", "✅", "AI:", "Stopped", "Can't", "The accessibility", "All models", "Rate limit:", "Saved", "🧩")
            val isTerm = terminal.any { t.startsWith(it) }
            if (isTerm) {
                val clean = t.removePrefix("Done:").removePrefix("✅").removePrefix("AI:").trim()
                bubble(if (clean.isEmpty()) "Done." else clean, false)
                live("")
                featStatus?.text = clean
            } else {
                live(t)
                featStatus?.text = "● $t"
            }
        }
    }

    private fun bubble(text: String, mine: Boolean) {
        val col = chatCol ?: return
        val tv = UI.text(this, text, 13f, Color.WHITE)
        tv.background = UI.bg(this, if (mine) UI.ACCENT else UI.CARD, 14f)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.gravity = if (mine) Gravity.END else Gravity.START
        lp2.topMargin = dp(6)
        if (mine) lp2.marginStart = dp(40) else lp2.marginEnd = dp(40)
        if (col.childCount > 30) col.removeViewAt(0)
        tv.alpha = 0f
        tv.translationY = dp(8).toFloat()
        col.addView(tv, lp2)
        tv.animate().alpha(1f).translationY(0f).setDuration(180).start()
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun live(text: String) {
        val tv = liveTv ?: return
        liveAnim?.cancel()
        if (text.isEmpty()) {
            tv.text = ""
            tv.alpha = 1f
            return
        }
        tv.text = "● $text"
        val an = ObjectAnimator.ofFloat(tv, "alpha", 0.35f, 1f)
        an.duration = 700
        an.repeatMode = ValueAnimator.REVERSE
        an.repeatCount = ValueAnimator.INFINITE
        an.start()
        liveAnim = an
    }

    override fun addButton(label: String, script: String) {
        ScriptStore.save(this, Script(label, script))
        main.post {
            addScriptButton(Script(label, script))
            if (featStatus != null) showFeatures()
        }
    }

    override fun addMenu(title: String, items: String) {
        val s = Script(title, "#menu\n" + items)
        ScriptStore.save(this, s)
        main.post {
            addScriptButton(s)
            if (featStatus != null) showFeatures()
        }
    }

    private fun showScriptMenu(s: Script) {
        val c = card()
        c.addView(header("🧩 " + s.name))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        for (line in s.body.lines().drop(1)) {
            val i = line.indexOf("::")
            if (i <= 0) continue
            val label = line.substring(0, i).trim()
            val script = line.substring(i + 2).split(";").joinToString("\n") { it.trim() }
            val key = s.name + "/" + label
            col.addView(UI.button(this, label) {
                val r = runners.getOrPut(key) { ScriptRunner(this) }
                if (r.running) r.stop() else r.start(script) {}
            }, UI.match(this, 8))
        }
        val sv = ScrollView(this)
        sv.addView(col)
        c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showPanel(c)
    }

    override fun macroSaved(name: String) {
        main.post {
            val m = MacroStore.all(this).firstOrNull { it.name == name }
            if (m != null) addMacroButton(m)
        }
    }

    override fun overlays(visible: Boolean) {
        for (v in overlayViews) v.alpha = if (visible) 1f else 0f
        liveView?.setAlpha(if (visible) 1f else 0f)
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = UI.dp(this, v)

    private fun toast(m: String) {
        main.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Gaming Mode", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MACRO, "Macro recorder", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notifyMacro(title: String, text: String) {
        val n = Notification.Builder(this, CH_MACRO)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun lp(w: Int, h: Int, focusable: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        return p
    }

    private fun addOv(v: View, p: WindowManager.LayoutParams) {
        wm.addView(v, p)
        overlayViews.add(v)
    }

    private fun remOv(v: View) {
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        overlayViews.remove(v)
    }

    private fun card(pad: Int = 16): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = UI.panel(this)
        c.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        return c
    }

    private fun header(title: String): LinearLayout {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.addView(UI.text(this, title, 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        h.addView(UI.button(this, "–") { closePanel() })
        h.addView(UI.button(this, "Hide") { hideIcon() })
        return h
    }

    private fun showPanel(content: View, focusable: Boolean = false) {
        closePanel()
        val w = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        val p = lp(w, ViewGroup.LayoutParams.WRAP_CONTENT, focusable)
        if (focusable) {
            p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            p.y = dp(70)
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        } else {
            p.gravity = Gravity.CENTER
        }
        addOv(content, p)
        panel = content
        content.alpha = 0f
        content.scaleX = 0.94f
        content.scaleY = 0.94f
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
    }

    private fun closePanel() {
        featStatus = null
        panel?.let { remOv(it) }
        panel = null
    }

    private fun drag(v: View, p: WindowManager.LayoutParams, onClick: () -> Unit, onLong: (() -> Unit)?, snap: Boolean = false) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        var downT = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x
                    sy = p.y
                    tx = e.rawX
                    ty = e.rawY
                    moved = false
                    downT = SystemClock.uptimeMillis()
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        p.x = sx + dx
                        p.y = sy + dy
                        try {
                            wm.updateViewLayout(v, p)
                        } catch (ex: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (!moved) {
                            if (onLong != null && SystemClock.uptimeMillis() - downT > 600) onLong() else onClick()
                        } else if (snap) {
                            val sw = resources.displayMetrics.widthPixels
                            val target = if (p.x + v.width / 2 < sw / 2) 0 else sw - v.width
                            val an = ValueAnimator.ofInt(p.x, target)
                            an.duration = 200
                            an.addUpdateListener {
                                p.x = it.animatedValue as Int
                                try {
                                    wm.updateViewLayout(v, p)
                                } catch (ex: Exception) {
                                }
                            }
                            an.start()
                        }
                    }
                }
            }
            true
        }
    }

    // ---------- floating icon + main menu ----------

    private fun addIcon() {
        val v = TextView(this)
        v.text = "🎮"
        v.textSize = 24f
        v.gravity = Gravity.CENTER
        v.background = UI.oval(UI.ACCENT)
        val s = dp(56)
        val p = lp(s, s)
        p.x = 0
        p.y = dp(200)
        drag(v, p, { if (panel != null) closePanel() else showMenu() }, null, true)
        addOv(v, p)
        icon = v
        iconLp = p
        iconAttached = true
    }

    private fun hideIcon() {
        closePanel()
        minimizeInput()
        val v = icon
        if (v != null && iconAttached) remOv(v)
        iconAttached = false
        toast("Hidden. Use 'Show icon' in the notification, or open the app.")
    }

    private fun showIcon() {
        val v = icon ?: return
        val p = iconLp ?: return
        if (!iconAttached) {
            addOv(v, p)
            iconAttached = true
        }
    }

    private fun minimizeInput() {
        val v = input ?: return
        if (!inputMinimized) {
            remOv(v)
            inputMinimized = true
        }
    }

    private fun showMenu() {
        val c = card()
        c.addView(header("🎮 Gaming Mode"))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sens = UI.button(this, sensLabel()) {}
        sens.setOnClickListener { toggleSens { sens.text = sensLabel() } }
        col.addView(sens, UI.match(this, 8))
        col.addView(UI.button(this, "🤖 AI Corp") { openAiCorp() }, UI.match(this))
        col.addView(UI.button(this, "📺 Live view") { toggleLive() }, UI.match(this))
        col.addView(UI.button(this, "👁 Detect screen") { detectScreen() }, UI.match(this))
        col.addView(UI.button(this, "🎬 Screen record macro") { showMacroMenu() }, UI.match(this))
        col.addView(UI.button(this, "🧠 AI memory") { showMemory() }, UI.match(this))
        col.addView(UI.button(this, "💻 Console") { showConsole() }, UI.match(this))
        col.addView(UI.button(this, "🛡 Shizuku tools") { showShizuku() }, UI.match(this))
        col.addView(UI.button(this, "🧪 Touch test") { touchTest() }, UI.match(this))
        col.addView(UI.button(this, "✨ Features") { showFeatures() }, UI.match(this))
        col.addView(UI.button(this, "📩 Contact admin") { contactAdmin() }, UI.match(this))
        val sv = ScrollView(this)
        sv.addView(col)
        c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.62f).toInt()))
        showPanel(c)
    }

    // ---------- Shizuku tools ----------

    private fun shellTapLabel() =
        if (prefs.getBoolean("shell_input", true)) "👆 Shizuku taps: ON" else "👆 Shizuku taps: OFF"

    private fun showShizuku() {
        val c = card()
        c.addView(header("🛡 Shizuku tools"))
        val ok = Shell.ready()
        c.addView(
            UI.text(this, if (ok) "Shizuku: CONNECTED" else "Shizuku: not running. Start it in the Shizuku app and allow this app.", 13f, if (ok) UI.GREEN else UI.RED, true),
            UI.match(this, 8)
        )
        c.addView(UI.button(this, "🔓 Give this app every permission") {
            Thread { toast(Shell.grantSelf(this).take(250)) }.start()
        }, UI.match(this, 10))
        val tapBtn = UI.button(this, shellTapLabel()) {}
        tapBtn.setOnClickListener {
            prefs.edit().putBoolean("shell_input", !prefs.getBoolean("shell_input", true)).apply()
            tapBtn.text = shellTapLabel()
        }
        c.addView(tapBtn, UI.match(this))
        c.addView(UI.button(this, "💻 Console") { showConsole() }, UI.match(this))
        showPanel(c)
    }

    private fun showTextPanel(title: String, text: String) {
        val c = card()
        c.addView(header(title))
        val tv = UI.text(this, text, 12f, Color.WHITE)
        val sv = ScrollView(this)
        sv.addView(tv)
        c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        showPanel(c)
    }

    private fun toggleLive() {
        closePanel()
        val lv = liveView
        if (lv != null) {
            lv.stop()
            liveView = null
            return
        }
        if (GameAccessibilityService.instance == null) {
            toast("Turn the accessibility service on first. It takes the screenshots for the live view.")
            return
        }
        val v = LiveView(this, wm, { groqKey() }, { visionModel() }, { toast(it) }, { liveView = null })
        liveView = v
        v.start()
    }

    private fun detectScreen() {
        closePanel()
        toast("Looking at the screen…")
        agent.describe(groqKey(), visionModel()) { result, err ->
            showTextPanel("👁 Screen detection", result ?: ("Couldn't detect: " + err))
        }
    }

    private fun showConsole() {
        val c = card()
        c.addView(header("💻 Console (live)"))
        val out = UI.text(this, "Live shell output appears here. Try:  ls /sdcard/Android/data", 12f, UI.MUTED)
        out.typeface = Typeface.MONOSPACE
        val sc = ScrollView(this)
        sc.addView(out)
        c.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        fun append(t: String) {
            main.post {
                out.text = (out.text.toString() + t).takeLast(8000)
                sc.post { sc.fullScroll(View.FOCUS_DOWN) }
            }
        }
        val et = UI.edit(this, "command…")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(UI.button(this, "Run", UI.ACCENT) {
            val cmd = et.text.toString().trim()
            if (cmd.isNotEmpty()) {
                consoleProc?.destroy()
                append("\n$ " + cmd + "\n")
                et.setText("")
                Thread { consoleProc = Shell.stream(cmd) { append(it) } }.start()
            }
        })
        row.addView(UI.button(this, "■", UI.RED) {
            consoleProc?.destroy()
            append("\n[stopped]\n")
        })
        c.addView(row, UI.match(this, 8))
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(UI.button(this, "📡 AI feed") {
            out.text = if (feed.isEmpty()) "(no AI activity yet)" else feed.takeLast(80).joinToString("\n")
            sc.post { sc.fullScroll(View.FOCUS_DOWN) }
        })
        row2.addView(UI.button(this, "Clear") { out.text = "" })
        c.addView(row2, UI.match(this, 6))
        showPanel(c, true)
    }

    private fun touchTest() {
        closePanel()
        val svc = GameAccessibilityService.instance
        val size = dp(90)
        val dot = TextView(this)
        dot.text = "TAP"
        dot.textSize = 16f
        dot.setTextColor(Color.WHITE)
        dot.gravity = Gravity.CENTER
        dot.typeface = Typeface.DEFAULT_BOLD
        dot.background = UI.oval(UI.GREEN)
        val p = lp(size, size)
        p.gravity = Gravity.CENTER
        var hits = 0
        dot.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) hits++
            true
        }
        addOv(dot, p)
        toast("Touch test running…")
        dot.postDelayed({
            val loc = IntArray(2)
            dot.getLocationOnScreen(loc)
            val cx = loc[0] + size / 2f
            val cy = loc[1] + size / 2f
            Thread {
                val rep = StringBuilder()
                if (svc == null) {
                    rep.append("Accessibility: not connected ✗\n")
                } else {
                    hits = 0
                    var accepted = false
                    main.post { accepted = svc.tapChecked(cx, cy) }
                    Thread.sleep(900)
                    rep.append("Accessibility tap: ")
                        .append(if (hits > 0) "works ✓" else if (accepted) "sent, but the screen ignored it ✗" else "refused by Android ✗")
                        .append("\n")
                }
                if (Shell.ready()) {
                    hits = 0
                    Shell.run("input tap " + cx.toInt() + " " + cy.toInt())
                    Thread.sleep(500)
                    rep.append("Shizuku tap: ").append(if (hits > 0) "works ✓" else "not received ✗")
                } else {
                    rep.append("Shizuku: not connected")
                }
                main.post {
                    remOv(dot)
                    showTextPanel(
                        "🧪 Touch test",
                        rep.toString() + "\n\nA ✓ means that method can tap in normal apps. If a test passes but a game still ignores taps, that game blocks injected touches."
                    )
                }
            }.start()
        }, 400)
    }

    // ---------- Features: ask the AI, get a working button or menu ----------

    private fun showFeatures() {
        val c = card()
        c.addView(header("✨ Features"))
        c.addView(UI.text(this, "Describe a feature. The AI builds it as a floating button or menu that really works.", 11f, UI.MUTED), UI.match(this, 6))
        val et = UI.edit(this, "e.g. a button that taps my reload spot every 5 seconds", "", true)
        c.addView(et, UI.match(this, 6))
        val st = UI.text(this, "", 11f, UI.MUTED)
        c.addView(UI.button(this, "✨ Create", UI.ACCENT) {
            val t = et.text.toString().trim()
            if (t.isEmpty()) {
                toast("Describe the feature first")
                return@button
            }
            if (coder.running) {
                toast("Already building one. Wait, or press Stop in the AI box.")
                return@button
            }
            st.text = "● building…"
            coder.run(
                groqKey(), coderModel(),
                "Build this as a floating button or menu using the button or menu action, with a script that really works. Request: $t"
            )
        }, UI.match(this, 8))
        c.addView(st, UI.match(this, 4))
        val list = ScriptStore.all(this)
        if (list.isNotEmpty()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            for (s in list) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(UI.text(this, s.name, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(UI.button(this, if (s.body.startsWith("#menu")) "Open" else "Run", UI.ACCENT) {
                    if (s.body.startsWith("#menu")) {
                        showScriptMenu(s)
                    } else {
                        closePanel()
                        val r = runners.getOrPut(s.name) { ScriptRunner(this) }
                        if (r.running) r.stop() else r.start(s.body) {}
                    }
                })
                row.addView(UI.button(this, "Show") {
                    closePanel()
                    addScriptButton(s)
                })
                row.addView(UI.button(this, "✕", UI.RED) {
                    ScriptStore.delete(this, s.name)
                    scriptButtons.remove(s.name)?.let { remOv(it) }
                    showFeatures()
                })
                col.addView(row, UI.match(this, 6))
            }
            val sv = ScrollView(this)
            sv.addView(col)
            c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (list.size > 3) dp(180) else ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        showPanel(c, true)
        featStatus = st
    }

    // ---------- Touch response boost ----------

    private fun sensLabel() =
        if (prefs.getBoolean("sens", false)) "⚡ Touch response boost: ON" else "⚡ Touch response boost: OFF"

    private fun ensureWriteSettings(): Boolean {
        if (Settings.System.canWrite(this)) return true
        if (Shell.ready()) {
            Shell.run("appops set $packageName WRITE_SETTINGS allow")
            if (Settings.System.canWrite(this)) return true
        }
        return false
    }

    private fun toggleSens(onDone: () -> Unit) {
        Thread {
            if (!ensureWriteSettings()) {
                main.post {
                    toast("Android needs your OK once: switch on 'Allow modifying system settings' for Gaming Mode, then press this again.")
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                }
                return@Thread
            }
            val on = !prefs.getBoolean("sens", false)
            val ok = ArrayList<String>()
            val fail = ArrayList<String>()
            fun set(ns: String, key: String, v: String) {
                if (PhoneCtl.setSetting(this, ns, key, v).startsWith("OK")) ok.add(key) else fail.add(key)
            }
            set("system", "pointer_speed", if (on) "7" else "0")
            set("secure", "long_press_timeout", if (on) "250" else "400")
            set("secure", "multi_press_timeout", if (on) "250" else "300")
            set("global", "window_animation_scale", if (on) "0.5" else "1.0")
            set("global", "transition_animation_scale", if (on) "0.5" else "1.0")
            set("global", "animator_duration_scale", if (on) "0.5" else "1.0")
            prefs.edit().putBoolean("sens", on).apply()
            main.post {
                toast(
                    (if (on) "Touch response boost ON" else "Touch response boost OFF") +
                        " (" + ok.size + " settings changed" + (if (fail.isNotEmpty()) ", " + fail.size + " need Master access" else "") + "). " +
                        "Faster long-press, animations and pointer. In-game sensitivity is only inside the game's own settings."
                )
                onDone()
            }
        }.start()
    }

    private fun contactAdmin() {
        closePanel()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ADMIN_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Couldn't open Instagram")
        }
    }

    // ---------- AI memory ----------

    private fun showMemory() {
        val c = card()
        c.addView(header("🧠 AI memory"))
        val items = AiMemory.all(this)
        if (items.isEmpty()) {
            c.addView(
                UI.text(this, "Nothing saved yet. In the AI box type:  remember <instruction>  — or just tell the AI to always do something and it will save it.", 12f, UI.MUTED),
                UI.match(this, 10)
            )
        } else {
            val list = LinearLayout(this)
            list.orientation = LinearLayout.VERTICAL
            for ((i, s) in items.withIndex()) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(UI.text(this, s, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(UI.button(this, "✕") {
                    AiMemory.remove(this, i)
                    showMemory()
                })
                list.addView(row, UI.match(this, 6))
            }
            val sv = ScrollView(this)
            sv.addView(list)
            val h = if (items.size > 5) dp(240) else ViewGroup.LayoutParams.WRAP_CONTENT
            c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            c.addView(UI.button(this, "Clear all", UI.RED) {
                AiMemory.clear(this)
                showMemory()
            }, UI.match(this, 10))
        }
        showPanel(c)
    }

    // ---------- AI Corp ----------

    private fun visionModel(): String {
        val s = (prefs.getString("m_vision", "") ?: "").trim()
        return if (s.isEmpty() || s == "qwen/qwen3.8-27b") GroqClient.DEFAULT_VISION else s
    }

    private fun coderModel(): String {
        val s = (prefs.getString("m_coder", "") ?: "").trim()
        return if (s.isEmpty() || s == "openai/gpt-oss-120b" || s == "openai/gpt-oss-120s") GroqClient.DEFAULT_CODER else s
    }

    private fun groqKey(): String = prefs.getString("groq_key", "") ?: ""

    private fun openAiCorp() {
        val ib = input
        val il = inputLp
        if (ib != null && il != null) {
            if (inputMinimized) {
                addOv(ib, il)
                inputMinimized = false
            }
            closePanel()
            return
        }
        if (groqKey().isBlank()) showKeyPanel() else {
            closePanel()
            startInjection()
        }
    }

    private fun showKeyPanel() {
        val c = card()
        c.addView(header("🤖 AI Corp"))
        val hasKey = groqKey().isNotBlank()

        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.addView(
            UI.text(this, if (hasKey) "A key is saved. Paste a new one to replace it, or leave empty." else "Paste your Groq API key (free at console.groq.com/keys)", 12f, UI.MUTED),
            UI.match(this, 4)
        )
        val k = UI.edit(this, "gsk_…")
        form.addView(k, UI.match(this))
        form.addView(UI.button(this, "📋 Paste from clipboard") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text
            if (t != null) k.setText(t.toString().trim())
        }, UI.match(this))
        form.addView(UI.text(this, "Vision model (sees the screen)", 11f, UI.MUTED), UI.match(this, 10))
        val vm = UI.edit(this, GroqClient.DEFAULT_VISION, visionModel())
        form.addView(vm, UI.match(this, 2))
        form.addView(UI.text(this, "Coding model", 11f, UI.MUTED), UI.match(this, 8))
        val cm2 = UI.edit(this, GroqClient.DEFAULT_CODER, coderModel())
        form.addView(cm2, UI.match(this, 2))
        form.addView(UI.text(this, "Persona (optional) - paste your own. Empty = the AI uses its own default. Max 2000 characters.", 11f, UI.MUTED), UI.match(this, 10))
        val pe = UI.edit(this, "Paste your persona / instructions here", prefs.getString("persona", "") ?: "", true)
        pe.maxLines = 6
        form.addView(pe, UI.match(this, 4))
        form.addView(UI.button(this, "📋 Paste persona from clipboard") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text
            if (t != null) pe.setText(t.toString().trim())
        }, UI.match(this, 4))
        form.addView(UI.text(this, "More AI (optional). Put a model in the lists above with a prefix:  g: Gemini   a: Claude   or: OpenRouter", 11f, UI.MUTED), UI.match(this, 10))
        val ork = UI.edit(this, "OpenRouter key", prefs.getString("or_key", "") ?: "")
        form.addView(ork, UI.match(this, 4))
        val gk = UI.edit(this, "Gemini key (aistudio.google.com)", prefs.getString("g_key", "") ?: "")
        form.addView(gk, UI.match(this, 4))
        val ak = UI.edit(this, "Claude key (console.anthropic.com)", prefs.getString("a_key", "") ?: "")
        form.addView(ak, UI.match(this, 4))

        val go = UI.button(this, "🚀 Let's go", UI.ACCENT) {
            val key = k.text.toString().trim()
            if (key.isEmpty() && !hasKey) {
                toast("Paste the key first")
                return@button
            }
            val e = prefs.edit()
            if (key.isNotEmpty()) e.putString("groq_key", key)
            e.putString("m_vision", vm.text.toString().trim())
            e.putString("m_coder", cm2.text.toString().trim())
            e.putString("persona", pe.text.toString().trim())
            e.putString("or_key", ork.text.toString().trim())
            e.putString("g_key", gk.text.toString().trim())
            e.putString("a_key", ak.text.toString().trim())
            Keys.openrouter = ork.text.toString().trim()
            Keys.gemini = gk.text.toString().trim()
            Keys.anthropic = ak.text.toString().trim()
            e.apply()
            closePanel()
            startInjection()
        }
        // Let's go sits right under the title so it is always visible, even in landscape games.
        c.addView(go, UI.match(this, 8))

        val sv = ScrollView(this)
        sv.addView(form)
        c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45f).toInt()))
        showPanel(c, true)
    }

    private fun startInjection() {
        toast("Injection started")
        agent.detect(groqKey(), visionModel()) { result, err ->
            if (err != null) {
                if (err.contains("401")) {
                    prefs.edit().remove("groq_key").apply()
                    toast("Groq rejected the key. Paste it again.")
                    showKeyPanel()
                } else {
                    showInputBox("Couldn't read the screen: $err\nYou can still type instructions.")
                }
            } else {
                showInputBox("Detected: $result\nScreenshot detection is ON - tell me what to do. Tap 📸 to switch it OFF (coding AI).")
            }
        }
    }

    private fun closeInput() {
        liveAnim?.cancel()
        input?.let { remOv(it) }
        inputMinimized = false
        input = null
        inputLp = null
        inputEt = null
        chatCol = null
        chatScroll = null
        liveTv = null
    }

    private fun setInputFocusable(f: Boolean) {
        val p = inputLp ?: return
        val v = input ?: return
        p.flags = if (f) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (f) {
            main.postDelayed({
                inputEt?.requestFocus()
                imm.showSoftInput(inputEt, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        } else {
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    private fun showInputBox(sub: String) {
        closeInput()
        codeMode = false
        agent.live = false
        val box = card(10)
        val sc = ScrollView(this)
        sc.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        sc.addView(col)
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        val lv = UI.text(this, "", 11f, UI.MUTED)
        box.addView(lv, UI.match(this, 2))
        chatCol = col
        chatScroll = sc
        liveTv = lv
        bubble(sub, false)

        val et = UI.edit(this, "Type any instruction or question…", "", true)
        et.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) setInputFocusable(true)
            false
        }

        fun send() {
            val task = et.text.toString().trim()
            if (task.isEmpty()) return
            setInputFocusable(false)
            et.setText("")
            bubble(task, true)
            if (task.lowercase().startsWith("remember ")) {
                AiMemory.add(this, task.substring(9))
                bubble("Saved to memory ✔", false)
                return
            }
            live("Thinking…")
            if (codeMode) {
                if (coder.running) toast("Already working. Press ■ to stop.") else coder.run(groqKey(), coderModel(), task)
            } else {
                if (agent.running) toast("Already working. Press ■ to stop.") else agent.run(groqKey(), visionModel(), task)
            }
        }

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        row1.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(UI.button(this, "➤", UI.ACCENT) { send() })
        box.addView(row1, UI.match(this, 8))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        fun cell(b: View) {
            if (b is android.widget.Button) {
                b.textSize = 12f
                b.maxLines = 1
                b.setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp2.marginStart = dp(2)
            lp2.marginEnd = dp(2)
            row2.addView(b, lp2)
        }
        val modeBtn = UI.button(this, "📸 ON", UI.ACCENT) {}
        modeBtn.setOnClickListener {
            codeMode = !codeMode
            modeBtn.text = if (codeMode) "📸 OFF" else "📸 ON"
            modeBtn.background = if (codeMode) UI.bg(this, UI.CARD, 12f) else UI.grad(this, 0xFF7C4DFF.toInt(), 0xFF4F7CFF.toInt(), 12f)
            if (codeMode) {
                bubble("Screenshot detection is OFF. I'm now the coding AI: I read the screen as text, tap, type, search the web, run code, change settings and read or write files.", false)
                et.hint = "Tell me what to do or build…"
            } else {
                bubble("Screenshot detection is ON. I look at the screen and tap, swipe and type.", false)
                et.hint = "Type any instruction or question…"
            }
        }
        val liveBtn = UI.button(this, "👁") {}
        liveBtn.setOnClickListener {
            agent.live = !agent.live
            liveBtn.background = if (agent.live) UI.grad(this, 0xFF7C4DFF.toInt(), 0xFF4F7CFF.toInt(), 12f) else UI.bg(this, UI.CARD, 12f)
            toast(if (agent.live) "Live watch ON: two frames per step and faster actions." else "Live watch OFF")
        }
        cell(modeBtn)
        cell(liveBtn)
        cell(UI.button(this, "■", UI.RED) {
            agent.stop()
            coder.stop()
            live("")
            bubble("Stopped.", false)
        })
        cell(UI.button(this, "⚙") { showKeyPanel() })
        cell(UI.button(this, "–") { minimizeInput() })
        cell(UI.button(this, "Hide") { hideIcon() })
        box.addView(row2, UI.match(this, 6))

        val p = lp(minOf(dp(370), (resources.displayMetrics.widthPixels * 0.94f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT, false)
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(40)
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        addOv(box, p)
        box.alpha = 0f
        box.scaleX = 0.95f
        box.scaleY = 0.95f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        input = box
        inputLp = p
        inputEt = et
    }

    // ---------- macros ----------

    private fun showMacroMenu() {
        val c = card()
        c.addView(header("🎬 Screen record macro"))
        c.addView(UI.button(this, "● Quick record (no lag)", UI.RED) { startRecording(false) }, UI.match(this, 10))
        c.addView(UI.text(this, "Tap where each action should happen, in order. Taps don't reach the game while recording.", 11f, UI.MUTED), UI.match(this, 2))
        c.addView(UI.button(this, "● Live record (passes touches)") { startRecording(true) }, UI.match(this, 10))
        c.addView(UI.button(this, "📂 Recorded macros") { showMacroList() }, UI.match(this))
        showPanel(c)
    }

    private fun setCaptureTouchable(touchable: Boolean) {
        val p = recCaptureLp ?: return
        val v = recCapture ?: return
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    private fun startRecording(relay: Boolean) {
        val svc = GameAccessibilityService.instance
        if (svc == null) {
            toast("Turn on the accessibility permission first.")
            return
        }
        closePanel()
        recStrokes = ArrayList()
        recStart = SystemClock.uptimeMillis()

        val cap = View(this)
        cap.setBackgroundColor(0x22FF3B30)
        var downT = 0L
        var pts = ArrayList<Pair<Float, Float>>()
        cap.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downT = SystemClock.uptimeMillis()
                    pts = ArrayList()
                    pts.add(Pair(e.rawX, e.rawY))
                }
                MotionEvent.ACTION_MOVE -> {
                    val l = pts.lastOrNull()
                    if (l != null && pts.size < 300 && hypot(e.rawX - l.first, e.rawY - l.second) > 8f) {
                        pts.add(Pair(e.rawX, e.rawY))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pts.isNotEmpty()) {
                        val first = pts[0]
                        val isTap = pts.all { hypot(it.first - first.first, it.second - first.second) < 20f }
                        val path: List<Pair<Float, Float>> =
                            if (isTap) listOf(first) else pts + Pair(e.rawX, e.rawY)
                        val dur = max(40L, SystemClock.uptimeMillis() - downT)
                        val s = Stroke(downT - recStart, dur, ArrayList(path))
                        recStrokes.add(s)
                        (recStop as? TextView)?.text = "■ STOP (${recStrokes.size})"
                        if (relay) {
                            // pass the touch on to the game, then listen again
                            setCaptureTouchable(false)
                            val sv = GameAccessibilityService.instance
                            if (sv == null) setCaptureTouchable(true)
                            else sv.stroke(s.pts, s.durMs) { main.post { setCaptureTouchable(true) } }
                        }
                    }
                }
            }
            true
        }
        val cp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        addOv(cap, cp)
        recCapture = cap
        recCaptureLp = cp

        val stop = TextView(this)
        stop.text = "■ STOP"
        stop.setTextColor(Color.WHITE)
        stop.textSize = 14f
        stop.typeface = Typeface.DEFAULT_BOLD
        stop.gravity = Gravity.CENTER
        stop.background = UI.bg(this, UI.RED, 24f)
        stop.setPadding(dp(16), dp(10), dp(16), dp(10))
        val sp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sp.x = dp(20)
        sp.y = dp(120)
        drag(stop, sp, { stopRecording() }, null)
        addOv(stop, sp)
        recStop = stop
        notifyMacro("Macro recording started", "Do your actions, then press STOP.")
        toast(if (relay) "Recording… do your actions, then press STOP." else "Quick record: tap where each action should happen, in order, then press STOP.")
    }

    private fun stopRecording() {
        recCapture?.let { remOv(it) }
        recCapture = null
        recCaptureLp = null
        recStop?.let { remOv(it) }
        recStop = null
        getSystemService(NotificationManager::class.java).cancel(2)
        if (recStrokes.isEmpty()) {
            toast("Nothing was recorded.")
            return
        }
        notifyMacro("Macro recording stopped", "${recStrokes.size} actions captured.")
        showNamePanel()
    }

    private fun showNamePanel() {
        val c = card()
        c.addView(header("Name your macro"))
        val name = UI.edit(this, "e.g. reload + switch")
        c.addView(name, UI.match(this, 10))
        c.addView(UI.button(this, "Save", UI.GREEN) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                toast("Type a name")
                return@button
            }
            MacroStore.save(this, Macro(n, ArrayList(recStrokes)))
            recStrokes = ArrayList()
            closePanel()
            toast("Saved '$n'")
        }, UI.match(this, 10))
        showPanel(c, true)
    }

    private fun showMacroList() {
        val c = card()
        c.addView(header("📂 Recorded macros"))
        val macros = MacroStore.all(this)
        if (macros.isEmpty()) {
            c.addView(UI.text(this, "No macros yet. Record one first.", 13f, UI.MUTED), UI.match(this, 10))
        } else {
            val list = LinearLayout(this)
            list.orientation = LinearLayout.VERTICAL
            for (m in macros) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(UI.text(this, m.name, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(UI.button(this, "Add button", UI.ACCENT) { addMacroButton(m) })
                row.addView(UI.button(this, "Delete", UI.RED) {
                    MacroStore.delete(this, m.name)
                    macroButtons.remove(m.name)?.let { remOv(it) }
                    showMacroList()
                })
                list.addView(row, UI.match(this, 8))
            }
            val sv = ScrollView(this)
            sv.addView(list)
            val h = if (macros.size > 4) dp(260) else ViewGroup.LayoutParams.WRAP_CONTENT
            c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        }
        showPanel(c)
    }

    private fun addMacroButton(m: Macro) {
        if (macroButtons.containsKey(m.name)) {
            toast("That button is already on screen.")
            return
        }
        val v = TextView(this)
        v.text = m.name.take(2).uppercase()
        v.textSize = 15f
        v.setTextColor(Color.WHITE)
        v.gravity = Gravity.CENTER
        v.typeface = Typeface.DEFAULT_BOLD
        v.background = UI.oval(0xFFFF8A00.toInt())
        val s = dp(52)
        val p = lp(s, s)
        p.x = dp(90) + macroButtons.size * dp(12)
        p.y = dp(300) + macroButtons.size * dp(60)
        drag(v, p, { play(m) }, {
            remOv(v)
            macroButtons.remove(m.name)
        })
        addOv(v, p)
        macroButtons[m.name] = v
        closePanel()
        toast("Button added. Press it to run '${m.name}'. Long-press it to remove the button.")
    }

    // ---------- AI-made floating buttons ----------

    private fun addScriptButton(s: Script) {
        scriptButtons.remove(s.name)?.let { remOv(it) }
        val v = TextView(this)
        v.text = s.name.take(3)
        v.textSize = 13f
        v.setTextColor(Color.WHITE)
        v.gravity = Gravity.CENTER
        v.typeface = Typeface.DEFAULT_BOLD
        v.background = UI.oval(UI.GREEN)
        val size = dp(54)
        val p = lp(size, size)
        p.x = dp(90) + scriptButtons.size * dp(14)
        p.y = dp(380) + scriptButtons.size * dp(62)
        val runner = runners.getOrPut(s.name) { ScriptRunner(this) }
        drag(v, p, {
            if (s.body.startsWith("#menu")) {
                showScriptMenu(s)
            } else if (runner.running) {
                runner.stop()
                v.alpha = 1f
            } else {
                v.alpha = 0.6f
                runner.start(s.body) { v.alpha = 1f }
            }
        }, {
            runner.stop()
            remOv(v)
            scriptButtons.remove(s.name)
        })
        addOv(v, p)
        scriptButtons[s.name] = v
        toast("Button '${s.name}' is on screen. Press to run (press again to stop). Long-press to remove it.")
    }

    private fun showScriptList() {
        val c = card()
        c.addView(header("🧩 My buttons"))
        val list = ScriptStore.all(this)
        if (list.isEmpty()) {
            c.addView(
                UI.text(this, "No buttons yet. Turn 📸 OFF in the AI box and ask the AI, e.g. \"make a button that taps 900 600 five times\".", 12f, UI.MUTED),
                UI.match(this, 10)
            )
        } else {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            for (s in list) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(UI.text(this, s.name, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(UI.button(this, "Show", UI.ACCENT) {
                    closePanel()
                    addScriptButton(s)
                })
                row.addView(UI.button(this, "Delete", UI.RED) {
                    ScriptStore.delete(this, s.name)
                    scriptButtons.remove(s.name)?.let { remOv(it) }
                    showScriptList()
                })
                col.addView(row, UI.match(this, 8))
            }
            val sv = ScrollView(this)
            sv.addView(col)
            val h = if (list.size > 4) dp(260) else ViewGroup.LayoutParams.WRAP_CONTENT
            c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        }
        showPanel(c)
    }

    private fun play(m: Macro) {
        val svc = GameAccessibilityService.instance
        if (svc == null) {
            toast("The accessibility permission is off.")
            return
        }
        if (!playing.add(m.name)) return
        var total = 0L
        for (s in m.strokes) {
            total = max(total, s.startMs + s.durMs)
            main.postDelayed({ svc.stroke(s.pts, s.durMs, null) }, s.startMs)
        }
        main.postDelayed({ playing.remove(m.name) }, total + 300)
    }
}
