package com.gamingmode.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Extra provider key (OpenRouter) used for models written as  or:model-name  */
object Keys {
    @Volatile
    var openrouter = ""
}

/** Phone-wide actions shared by the coding AI, the screen AI and button scripts. */
object PhoneCtl {

    fun setSetting(ctx: Context, ns: String, key: String, value: String): String {
        if (key.isBlank()) return "ERROR: missing KEY"
        return try {
            val ok = when (ns.lowercase()) {
                "system" -> {
                    if (!Settings.System.canWrite(ctx)) return "ERROR: enable the 'Modify system settings' permission"
                    Settings.System.putString(ctx.contentResolver, key, value)
                }
                "secure" -> Settings.Secure.putString(ctx.contentResolver, key, value)
                "global" -> Settings.Global.putString(ctx.contentResolver, key, value)
                else -> return "ERROR: NS must be system, secure or global"
            }
            if (ok) "OK set $ns $key = $value" else "ERROR: Android refused this setting"
        } catch (e: SecurityException) {
            "ERROR: needs Master access (WRITE_SECURE_SETTINGS) for secure/global settings"
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    fun getSetting(ctx: Context, ns: String, key: String): String {
        return try {
            val v = when (ns.lowercase()) {
                "system" -> Settings.System.getString(ctx.contentResolver, key)
                "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
                "global" -> Settings.Global.getString(ctx.contentResolver, key)
                else -> return "ERROR: NS must be system, secure or global"
            }
            "$ns $key = $v"
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val q = name.trim().lowercase()
        if (q.isEmpty()) return "ERROR: missing NAME"
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "ERROR: no app matches '$name'"
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "ERROR: can't launch that app"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(launch)
            "OK opened " + hit.loadLabel(pm)
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    fun brightness(ctx: Context, v: Int): String {
        if (!Settings.System.canWrite(ctx)) return "ERROR: enable the 'Modify system settings' permission"
        return try {
            val b = v.coerceIn(0, 255)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, b)
            "OK brightness $b"
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    fun volume(ctx: Context, pct: Int): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, pct.coerceIn(0, 100) * max / 100, 0)
            "OK volume ${pct.coerceIn(0, 100)}%"
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }
}

/** "Master access" = the WRITE_SECURE_SETTINGS permission, granted once with ADB (see the app). */
object Master {
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    private const val ENABLED = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    fun has(ctx: Context): Boolean = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun component(ctx: Context) = ctx.packageName + "/" + GameAccessibilityService::class.java.name

    fun accessibilityListed(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, ENABLED) ?: ""
        return s.contains(ctx.packageName) && s.contains("GameAccessibilityService")
    }

    /** Turns the accessibility service on by itself. */
    fun enableAccessibility(ctx: Context): Boolean {
        if (!has(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            if (!accessibilityListed(ctx)) {
                val cur = Settings.Secure.getString(cr, ENABLED) ?: ""
                Settings.Secure.putString(cr, ENABLED, if (cur.isEmpty()) component(ctx) else cur + ":" + component(ctx))
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Off then on again, which makes Android reconnect a service that says "enabled" but is not running. */
    fun restartAccessibility(ctx: Context): Boolean {
        if (!has(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, ENABLED) ?: ""
            val without = cur.split(":").filter { it.isNotEmpty() && !it.contains("GameAccessibilityService") }.joinToString(":")
            Settings.Secure.putString(cr, ENABLED, without)
            Handler(Looper.getMainLooper()).postDelayed({ enableAccessibility(ctx) }, 700)
            true
        } catch (e: Exception) {
            false
        }
    }
}

class Script(val name: String, val body: String)

object ScriptStore {
    private fun prefs(c: Context) = c.getSharedPreferences("scripts", Context.MODE_PRIVATE)

    fun all(c: Context): List<Script> {
        val out = ArrayList<Script>()
        try {
            val arr = JSONArray(prefs(c).getString("data", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Script(o.getString("name"), o.getString("body")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(c: Context, list: List<Script>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("name", s.name).put("body", s.body))
        prefs(c).edit().putString("data", arr.toString()).apply()
    }

    fun save(c: Context, s: Script) = write(c, all(c).filter { it.name != s.name } + s)

    fun delete(c: Context, name: String) = write(c, all(c).filter { it.name != name })
}

/** Runs a button script: tap / hold / swipe / wait / key / type / open / setting / brightness / volume / repeat / forever. */
class ScriptRunner(private val ctx: Context) {

    private abstract class Node
    private class Cmd(val t: List<String>, val raw: String) : Node()
    private class Loop(val count: Int, val body: MutableList<Node>) : Node()

    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    var running = false
        private set
    private var thread: Thread? = null

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun parse(script: String): List<Node> {
        val root = ArrayList<Node>()
        val stack = ArrayList<MutableList<Node>>()
        stack.add(root)
        for (raw in script.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val t = line.split(Regex("\\s+"))
            when (t[0].lowercase()) {
                "repeat" -> {
                    val l = Loop((t.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 100000), ArrayList())
                    stack.last().add(l)
                    stack.add(l.body)
                }
                "forever" -> {
                    val l = Loop(-1, ArrayList())
                    stack.last().add(l)
                    stack.add(l.body)
                }
                "end" -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                else -> stack.last().add(Cmd(t, line))
            }
        }
        return root
    }

    private fun nap(ms: Long) {
        var left = ms
        while (running && left > 0) {
            val s = minOf(100L, left)
            Thread.sleep(s)
            left -= s
        }
    }

    private fun exec(nodes: List<Node>) {
        for (n in nodes) {
            if (!running) return
            if (n is Cmd) {
                doCmd(n.t, n.raw)
            } else if (n is Loop) {
                var i = 0
                while (running && (n.count < 0 || i < n.count)) {
                    exec(n.body)
                    i++
                    if (n.count < 0) nap(20)
                }
            }
        }
    }

    private fun doCmd(t: List<String>, raw: String) {
        val svc = GameAccessibilityService.instance
        fun num(i: Int): Float = t.getOrNull(i)?.toFloatOrNull() ?: 0f
        when (t[0].lowercase()) {
            "tap" -> {
                val x = num(1)
                val y = num(2)
                if (svc != null) ui.post { svc.stroke(listOf(Pair(x, y)), 50L, null) }
                nap(90)
            }
            "hold" -> {
                val x = num(1)
                val y = num(2)
                val ms = num(3).toLong().coerceAtLeast(50L)
                if (svc != null) ui.post { svc.stroke(listOf(Pair(x, y)), ms, null) }
                nap(ms + 40)
            }
            "swipe" -> {
                val a = Pair(num(1), num(2))
                val b = Pair(num(3), num(4))
                val ms = if (t.size > 5) num(5).toLong().coerceAtLeast(50L) else 300L
                if (svc != null) ui.post { svc.stroke(listOf(a, b), ms, null) }
                nap(ms + 60)
            }
            "wait" -> nap(num(1).toLong())
            "key" -> {
                val code = when ((t.getOrNull(1) ?: "").lowercase()) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                    else -> -1
                }
                if (svc != null && code >= 0) ui.post { svc.performGlobalAction(code) }
                nap(500)
            }
            "type" -> {
                val txt = raw.substringAfter(' ', "")
                if (svc != null) ui.post { svc.typeText(txt) }
                nap(400)
            }
            "open" -> {
                PhoneCtl.openApp(ctx, raw.substringAfter(' ', ""))
                nap(900)
            }
            "setting" -> {
                val p = raw.split(Regex("\\s+"), 4)
                PhoneCtl.setSetting(ctx, p.getOrNull(1) ?: "", p.getOrNull(2) ?: "", p.getOrNull(3) ?: "")
            }
            "shell" -> Shell.run(raw.substringAfter(' ', ""))
            "dpi" -> {
                val v = t.getOrNull(1) ?: "reset"
                if (v == "reset" || v.all { it.isDigit() }) Shell.run("wm density $v")
            }
            "brightness" -> PhoneCtl.brightness(ctx, num(1).toInt())
            "volume" -> PhoneCtl.volume(ctx, num(1).toInt())
        }
    }

    fun start(body: String, onEnd: () -> Unit) {
        if (running) return
        running = true
        val nodes = parse(body)
        thread = Thread {
            try {
                exec(nodes)
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
            } finally {
                running = false
                ui.post { onEnd() }
            }
        }
        thread!!.start()
    }
}
