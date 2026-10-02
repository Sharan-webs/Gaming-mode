package com.gamingmode.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The "control" AI: looks at the screen through screenshots and acts with touch / typing. */
class Agent(private val ctx: Context, private val hooks: Hooks) {

    interface Hooks {
        fun status(t: String)
        fun overlays(visible: Boolean)
        fun macroSaved(name: String) {}
        fun addButton(label: String, script: String) {}
        fun addMenu(title: String, items: String) {}
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    var running = false
        private set
    @Volatile
    var live = false
    private var prevImg: ByteArray? = null
    private var thread: Thread? = null
    private var lastShot = 0L
    private var shotW = 1080
    private var shotH = 2400

    private val system = """
You control an Android phone from a screenshot. Reply with ONE JSON object only.
x,y are 0-1000 (0,0 = top-left). Actions:
{"action":"tap","x":500,"y":300}
{"action":"long_press","x":500,"y":300,"ms":800}
{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300,"ms":400}
{"action":"type","text":"..."}  {"action":"back"}  {"action":"home"}  {"action":"recents"}
{"action":"wait","seconds":5}
{"action":"open","name":"app name"}  {"action":"setting","ns":"system","key":"screen_brightness","value":"200"}
{"action":"save_macro","name":"jump x5","x":500,"y":800,"repeat":5,"gap_ms":150}  (find the button, save a reusable macro; or give "steps":[{"x":..,"y":..},...])
{"action":"remember","text":"..."}  {"action":"answer","text":"..."}  {"action":"done","text":"..."}
Repeating tasks: tap, wait, keep going; never use done until stopped. If the user says always / every time, also use remember once.
""".trimIndent()

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun sleepSlices(ms: Long) {
        var left = ms
        while (running && left > 0) {
            val s = minOf(200L, left)
            Thread.sleep(s)
            left -= s
        }
    }

    private fun capture(): ByteArray? {
        val svc = GameAccessibilityService.instance ?: return null
        val wait = (if (live) 1100L else 1300L) - (System.currentTimeMillis() - lastShot)
        if (wait > 0) Thread.sleep(wait)
        ui.post { hooks.overlays(false) }
        var bmp: Bitmap? = null
        try {
            Thread.sleep(if (live) 120L else 200L)
            val latch = CountDownLatch(1)
            ui.post {
                svc.screenshot { b ->
                    bmp = b
                    latch.countDown()
                }
            }
            latch.await(6, TimeUnit.SECONDS)
        } finally {
            lastShot = System.currentTimeMillis()
            ui.post { hooks.overlays(true) }
        }
        val b = bmp ?: return null
        shotW = b.width
        shotH = b.height
        val scale = 1280f / maxOf(b.width, b.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
        } else b
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
    }

    private fun parse(s: String): JSONObject? {
        val first = s.indexOf('{')
        val lastB = s.lastIndexOf('}')
        if (first >= 0 && lastB > first) {
            try {
                return JSONObject(s.substring(first, lastB + 1))
            } catch (e: Exception) {
            }
        }
        val a = s.lastIndexOf('{')
        if (a < 0) return null
        val b = s.indexOf('}', a)
        if (b <= a) return null
        return try {
            JSONObject(s.substring(a, b + 1))
        } catch (e: Exception) {
            null
        }
    }

    /** First look at the screen: which app / game is this? Result is delivered on the main thread. */
    fun detect(key: String, model: String, done: (String?, String?) -> Unit) {
        Thread {
            try {
                val img = capture()
                    ?: throw RuntimeException("Could not take a screenshot (is the accessibility permission on?)")
                val r = GroqClient.chat(
                    key, model.split(",")[0].trim(),
                    "You describe phone screenshots briefly.",
                    "Which app or game is on this screen? Answer in 8 words or fewer.",
                    listOf(img), 200
                )
                ui.post { done(r.trim(), null) }
            } catch (e: Exception) {
                ui.post { done(null, e.message ?: "error") }
            }
        }.start()
    }

    fun run(key: String, model: String, task: String) {
        if (running) return
        running = true
        prevImg = null
        thread = Thread {
            val history = ArrayList<String>()
            val models = model.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("qwen/qwen3.8-27b") }
            var mi = 0
            var limitedRow = 0
            var step = 0
            var errors = 0
            try {
                while (running && step < 300) {
                    step++
                    hooks.status("Step $step: looking at the screen…")
                    val img = capture()
                    if (img == null) {
                        hooks.status("Can't take a screenshot. Check the accessibility permission.")
                        break
                    }
                    val last = if (history.isEmpty()) "none" else history.takeLast(6).joinToString("; ")
                    val old = prevImg
                    val twoFrames = live && old != null
                    val liveNote = if (twoFrames)
                        "LIVE MODE: image 1 is the previous frame (about 1-2 seconds ago), image 2 is now. " +
                            "Work out how moving things travel and tap where the target will be when the tap lands. Act fast, avoid waiting.\n\n"
                    else ""
                    val prompt = liveNote + AiMemory.asPrompt(ctx) + "Task: $task\nLast actions: $last\nNext action?"
                    val frames = if (twoFrames) listOf(old!!, img) else listOf(img)
                    prevImg = img
                    var limitWait = -1L
                    val reply = try {
                        GroqClient.chat(key, models[mi], system + Persona.text(ctx), prompt, frames, 500)
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: RateLimitException) {
                        limitWait = e.waitSec
                        null
                    } catch (e: Exception) {
                        errors++
                        hooks.status("AI error: " + (e.message ?: "").take(150))
                        null
                    }
                    if (reply == null && limitWait >= 0) {
                        limitedRow++
                        if (models.size > 1 && limitedRow < models.size) {
                            mi = (mi + 1) % models.size
                            hooks.status("Rate limit on one model. Switching to ${models[mi]}…")
                            continue
                        }
                        limitedRow = 0
                        if (limitWait > 180) {
                            hooks.status("Rate limit: Groq says try again in about ${limitWait / 60} min. Switch the model in the gear settings, or wait.")
                            break
                        }
                        hooks.status("Rate limit hit. Waiting ${limitWait}s, then I'll continue…")
                        sleepSlices(limitWait * 1000L)
                        continue
                    }
                    if (reply == null) {
                        if (errors >= 4) {
                            hooks.status("Stopped: too many AI errors. Check your key, model name and Groq limits.")
                            break
                        }
                        sleepSlices(6000)
                        continue
                    }
                    errors = 0
                    limitedRow = 0
                    val obj = parse(reply)
                    if (obj == null) {
                        hooks.status("AI: " + reply.trim().take(300))
                        break
                    }
                    val svc = GameAccessibilityService.instance
                    if (svc == null) {
                        hooks.status("The accessibility service is off.")
                        break
                    }
                    val why = obj.optString("why", "")
                    val whyTxt = if (why.isNotEmpty()) " - $why" else ""
                    when (obj.optString("action")) {
                        "tap", "long_press" -> {
                            val x = obj.optDouble("x", -1.0)
                            val y = obj.optDouble("y", -1.0)
                            if (x < 0 || y < 0) {
                                hooks.status("The AI gave a bad position, retrying…")
                                sleepSlices(1500)
                            } else {
                                val px = (x.coerceIn(0.0, 1000.0) / 1000.0 * shotW).toFloat()
                                val py = (y.coerceIn(0.0, 1000.0) / 1000.0 * shotH).toFloat()
                                val isLong = obj.optString("action") == "long_press"
                                val ms = if (isLong) obj.optLong("ms", 800L) else 60L
                                history.add((if (isLong) "long_press" else "tap") + "(${x.toInt()},${y.toInt()})")
                                hooks.status("${if (isLong) "Hold" else "Tap"} ${x.toInt()},${y.toInt()}$whyTxt")
                                ui.post { svc.stroke(listOf(Pair(px, py)), ms, null) }
                                sleepSlices(if (live) 250L else 900L)
                            }
                        }
                        "swipe" -> {
                            val x1 = obj.optDouble("x1", -1.0)
                            val y1 = obj.optDouble("y1", -1.0)
                            val x2 = obj.optDouble("x2", -1.0)
                            val y2 = obj.optDouble("y2", -1.0)
                            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                                hooks.status("The AI gave a bad swipe, retrying…")
                                sleepSlices(1500)
                            } else {
                                val a = Pair((x1 / 1000.0 * shotW).toFloat(), (y1 / 1000.0 * shotH).toFloat())
                                val b = Pair((x2 / 1000.0 * shotW).toFloat(), (y2 / 1000.0 * shotH).toFloat())
                                val ms = obj.optLong("ms", 400L)
                                history.add("swipe")
                                hooks.status("Swipe$whyTxt")
                                ui.post { svc.stroke(listOf(a, b), ms, null) }
                                sleepSlices(ms + (if (live) 200L else 700L))
                            }
                        }
                        "type" -> {
                            val t = obj.optString("text", "")
                            history.add("type")
                            hooks.status("Typing: " + t.take(40))
                            ui.post { svc.typeText(t) }
                            sleepSlices(800)
                        }
                        "back" -> {
                            history.add("back")
                            hooks.status("Back")
                            ui.post { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
                            sleepSlices(800)
                        }
                        "home" -> {
                            history.add("home")
                            hooks.status("Home")
                            ui.post { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
                            sleepSlices(800)
                        }
                        "recents" -> {
                            history.add("recents")
                            hooks.status("Recent apps")
                            ui.post { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
                            sleepSlices(800)
                        }
                        "wait" -> {
                            val s = obj.optInt("seconds", 5).coerceIn(1, 120)
                            history.add("wait($s)")
                            hooks.status("Waiting ${s}s…")
                            sleepSlices(s * 1000L)
                        }
                        "save_macro" -> {
                            val name = obj.optString("name", "").trim().ifEmpty { "AI macro " + (System.currentTimeMillis() % 1000) }
                            val gap = obj.optLong("gap_ms", 150L).coerceIn(20L, 5000L)
                            val pts = ArrayList<Pair<Float, Float>>()
                            val steps = obj.optJSONArray("steps")
                            if (steps != null) {
                                for (i in 0 until steps.length()) {
                                    val so = steps.optJSONObject(i) ?: continue
                                    val sx = so.optDouble("x", -1.0)
                                    val sy = so.optDouble("y", -1.0)
                                    if (sx >= 0 && sy >= 0) pts.add(Pair((sx / 1000.0 * shotW).toFloat(), (sy / 1000.0 * shotH).toFloat()))
                                }
                            } else {
                                val sx = obj.optDouble("x", -1.0)
                                val sy = obj.optDouble("y", -1.0)
                                val rep = obj.optInt("repeat", 1).coerceIn(1, 200)
                                if (sx >= 0 && sy >= 0) {
                                    for (i in 0 until rep) pts.add(Pair((sx / 1000.0 * shotW).toFloat(), (sy / 1000.0 * shotH).toFloat()))
                                }
                            }
                            if (pts.isEmpty()) {
                                hooks.status("The AI gave a bad macro, retrying…")
                                sleepSlices(1500)
                            } else {
                                val strokes = ArrayList<Stroke>()
                                var t = 0L
                                for (p in pts) {
                                    strokes.add(Stroke(t, 50L, listOf(p)))
                                    t += 50L + gap
                                }
                                MacroStore.save(ctx, Macro(name, strokes))
                                hooks.status("Saved macro '$name' (${strokes.size} taps). Its button is on screen.")
                                hooks.macroSaved(name)
                                break
                            }
                        }
                        "open" -> {
                            val r = PhoneCtl.openApp(ctx, obj.optString("name", ""))
                            history.add("open")
                            hooks.status(r)
                            sleepSlices(1200)
                        }
                        "setting" -> {
                            val r = PhoneCtl.setSetting(ctx, obj.optString("ns", "system"), obj.optString("key", ""), obj.optString("value", ""))
                            history.add("setting")
                            hooks.status(r)
                            sleepSlices(300)
                        }
                        "remember" -> {
                            val t = obj.optString("text", "")
                            AiMemory.add(ctx, t)
                            history.add("remember")
                            hooks.status("Saved to memory: " + t.take(80))
                        }
                        "answer" -> {
                            hooks.status("AI: " + obj.optString("text", ""))
                            break
                        }
                        "done" -> {
                            hooks.status("Done: " + obj.optString("text", ""))
                            break
                        }
                        else -> {
                            hooks.status("AI: " + reply.trim().take(300))
                            break
                        }
                    }
                }
            } catch (e: InterruptedException) {
            } finally {
                running = false
            }
        }
        thread!!.start()
    }
}
