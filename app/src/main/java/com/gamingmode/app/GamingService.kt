package com.gamingmode.app

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

    private val macroButtons = HashMap<String, View>()
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
            val tv = statusTv ?: return@post
            if (codeMode) {
                val s = tv.text.toString() + "\n" + t
                tv.text = if (s.length > 6000) s.takeLast(6000) else s
                statusScroll?.post { statusScroll?.fullScroll(View.FOCUS_DOWN) }
            } else {
                tv.text = t
            }
        }
    }

    override fun macroSaved(name: String) {
        main.post {
            val m = MacroStore.all(this).firstOrNull { it.name == name }
            if (m != null) addMacroButton(m)
        }
    }

    override fun overlays(visible: Boolean) {
        for (v in overlayViews) v.alpha = if (visible) 1f else 0f
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
        c.background = UI.bg(this, UI.BG, 18f)
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
    }

    private fun closePanel() {
        panel?.let { remOv(it) }
        panel = null
    }

    private fun drag(v: View, p: WindowManager.LayoutParams, onClick: () -> Unit, onLong: (() -> Unit)?) {
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
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (onLong != null && SystemClock.uptimeMillis() - downT > 600) onLong() else onClick()
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
        drag(v, p, { if (panel != null) closePanel() else showMenu() }, null)
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
        c.addView(UI.button(this, "📺 DPI increaser") {
            toast("DPI changing needs Shizuku, which is not part of this build. Real DPI values are numbers like 320, 420 or 480.")
        }, UI.match(this, 10))
        val sens = UI.button(this, sensLabel()) {}
        sens.setOnClickListener {
            toggleSens()
            sens.text = sensLabel()
        }
        c.addView(sens, UI.match(this))
        c.addView(UI.button(this, "🤖 AI Corp") { openAiCorp() }, UI.match(this))
        c.addView(UI.button(this, "🎬 Screen record macro") { showMacroMenu() }, UI.match(this))
        c.addView(UI.button(this, "🧠 AI memory") { showMemory() }, UI.match(this))
        c.addView(UI.button(this, "📩 Contact admin") { contactAdmin() }, UI.match(this))
        showPanel(c)
    }

    private fun sensLabel() =
        if (prefs.getBoolean("sens", false)) "⚡ Sensitivity booster: ON" else "⚡ Sensitivity booster: OFF"

    private fun toggleSens() {
        if (!Settings.System.canWrite(this)) {
            toast("Enable the 'Modify system settings' permission for this app first.")
            return
        }
        val on = !prefs.getBoolean("sens", false)
        try {
            Settings.System.putInt(contentResolver, "pointer_speed", if (on) 7 else 0)
            prefs.edit().putBoolean("sens", on).apply()
            toast(
                if (on) "Pointer speed set to maximum. This affects the mouse/gamepad pointer, not in-game touch sensitivity."
                else "Pointer speed reset."
            )
        } catch (e: Exception) {
            toast("Couldn't change the setting: " + e.message)
        }
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

    private fun visionModel(): String =
        (prefs.getString("m_vision", GroqClient.DEFAULT_VISION) ?: "").ifBlank { GroqClient.DEFAULT_VISION }

    private fun coderModel(): String =
        (prefs.getString("m_coder", GroqClient.DEFAULT_CODER) ?: "").ifBlank { GroqClient.DEFAULT_CODER }

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
        form.addView(UI.text(this, "AI persona (optional) - how the AI talks", 11f, UI.MUTED), UI.match(this, 8))
        val pe = UI.edit(this, "e.g. short, friendly gamer buddy", prefs.getString("persona", "") ?: "")
        form.addView(pe, UI.match(this, 2))

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
        input?.let { remOv(it) }
        inputMinimized = false
        input = null
        inputLp = null
        inputEt = null
        statusTv = null
        statusScroll = null
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
        val tv = UI.text(this, sub, 12f, UI.MUTED)
        sc.addView(tv)
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))

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
            if (task.lowercase().startsWith("remember ")) {
                AiMemory.add(this, task.substring(9))
                status("Saved to AI memory ✔")
                return
            }
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
            val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp2.marginStart = dp(2)
            lp2.marginEnd = dp(2)
            row2.addView(b, lp2)
        }
        val modeBtn = UI.button(this, "📸 ON", UI.ACCENT) {}
        modeBtn.setOnClickListener {
            codeMode = !codeMode
            modeBtn.text = if (codeMode) "📸 OFF" else "📸 ON"
            modeBtn.background = UI.bg(this, if (codeMode) UI.CARD else UI.ACCENT, 12f)
            if (codeMode) {
                tv.text = "Screenshot detection OFF - coding AI: reads the screen as text, taps, types, searches the web, runs JavaScript and reads / writes files."
                et.hint = "Tell me what to do or build…"
            } else {
                tv.text = "Screenshot detection ON - the AI looks at screenshots and taps, swipes and types."
                et.hint = "Type any instruction or question…"
            }
        }
        val liveBtn = UI.button(this, "👁") {}
        liveBtn.setOnClickListener {
            agent.live = !agent.live
            liveBtn.background = UI.bg(this, if (agent.live) UI.ACCENT else UI.CARD, 12f)
            toast(if (agent.live) "Live watch ON: the AI uses two frames and acts faster (about one decision every 2-3 seconds)." else "Live watch OFF")
        }
        cell(modeBtn)
        cell(liveBtn)
        cell(UI.button(this, "■", UI.RED) {
            agent.stop()
            coder.stop()
            status("Stopped.")
        })
        cell(UI.button(this, "⚙") { showKeyPanel() })
        cell(UI.button(this, "–") { minimizeInput() })
        cell(UI.button(this, "Hide") { hideIcon() })
        box.addView(row2, UI.match(this, 6))

        val p = lp((resources.displayMetrics.widthPixels * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, false)
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(40)
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        addOv(box, p)
        input = box
        inputLp = p
        inputEt = et
        statusTv = tv
        statusScroll = sc
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
