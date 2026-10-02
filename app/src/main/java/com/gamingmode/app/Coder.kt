package com.gamingmode.app

import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The "coding" AI. It can search the web, read pages, and create / read / list files.
 * Files are limited to three workspace folders: internal storage, shared storage and the SD card (if present).
 */
class Coder(private val ctx: Context, private val hooks: Agent.Hooks) {

    @Volatile
    var running = false
        private set
    private var thread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    private val system = """
You are a coding agent running inside an Android app. You work in steps; after each step you see the result.
Reply with exactly ONE action in this plain-text format (no markdown fences, no extra text):

ACTION: search
QUERY: web search query

ACTION: fetch
URL: https://page-to-read

ACTION: write
ROOT: internal | shared | sdcard
PATH: folder/file.ext
CONTENT:
<<<
full file content here
>>>

ACTION: read
ROOT: internal
PATH: folder/file.ext

ACTION: list
ROOT: internal
PATH: folder

ACTION: screen
(returns the visible text and buttons of the current app with their tap coordinates)

ACTION: tap
X: 540
Y: 1200

ACTION: swipe
X1: 540
Y1: 1500
X2: 540
Y2: 500

ACTION: type
TEXT: text to type into the focused field

ACTION: key
KEY: back | home | recents

ACTION: wait
SECONDS: 3

ACTION: js
CONTENT:
<<<
javascript code - use console.log or make the last expression the result
>>>

ACTION: done
TEXT: short summary of what you built and where the files are

Rules: ROOT internal = app storage, shared = phone storage folder GamingModeAI, sdcard = SD card folder GamingModeAI (only if present).
Paths are relative to the root. You can run JavaScript with the js action (your own console, sandboxed). Other languages cannot run here: write them as complete files. You can also control the phone: read the screen with screen, then tap / swipe / type / key.
Build big things as several files. Search or fetch when you need up-to-date docs or examples.
Saved memory (standing instructions from the user):
""".trimIndent()

    fun stop() {
        running = false
        thread?.interrupt()
    }

    fun roots(): LinkedHashMap<String, File> {
        val m = LinkedHashMap<String, File>()
        m["internal"] = File(ctx.filesDir, "workspace")
        m["shared"] = File(Environment.getExternalStorageDirectory(), "GamingModeAI")
        val p = ctx.getExternalFilesDirs(null).getOrNull(1)?.absolutePath
        if (p != null) {
            val i = p.indexOf("/Android")
            if (i > 0) m["sdcard"] = File(p.substring(0, i), "GamingModeAI")
        }
        return m
    }

    private fun resolve(root: String, rel: String): File? {
        val base = roots()[root] ?: return null
        base.mkdirs()
        val f = File(base, rel)
        val bc = base.canonicalPath
        val fc = f.canonicalPath
        return if (fc == bc || fc.startsWith(bc + File.separator)) f else null
    }

    private class Cmd(val action: String, val fields: Map<String, String>, val content: String?)

    private fun parseCmd(reply: String): Cmd? {
        val lines = GroqClient.clean(reply).lines()
        val start = lines.indexOfFirst { it.trim().startsWith("ACTION:") }
        if (start < 0) return null
        val fields = HashMap<String, String>()
        var content: String? = null
        var i = start
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("CONTENT:")) {
                val rest = lines.drop(i + 1).joinToString("\n")
                val a = rest.indexOf("<<<")
                val b = rest.lastIndexOf(">>>")
                if (a >= 0 && b > a) content = rest.substring(a + 3, b).trim('\n', '\r')
                break
            }
            val c = t.indexOf(':')
            if (c > 0) fields[t.substring(0, c).trim().uppercase()] = t.substring(c + 1).trim()
            i++
        }
        val action = fields["ACTION"]?.lowercase() ?: return null
        return Cmd(action, fields, content)
    }

    /** A sandboxed JavaScript console: runs code in a hidden WebView with no file or app access. */
    private fun runJs(code: String): String {
        if (code.isBlank()) return "ERROR: empty code (put it in a CONTENT block with <<< and >>>)"
        val latch = CountDownLatch(1)
        var out = "ERROR: timed out after 12 seconds"
        val script = "(function(){var __l=[];console.log=function(){__l.push(Array.prototype.slice.call(arguments).join(' '))};" +
            "try{var r=eval(" + JSONObject.quote(code) + ");return __l.join('\\n')+(r===undefined?'':'\\n=> '+String(r));}" +
            "catch(e){return __l.join('\\n')+'\\nERROR: '+e;}})()"
        ui.post {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script) { res ->
                        out = try {
                            JSONTokener(res).nextValue().toString()
                        } catch (e: Exception) {
                            res ?: ""
                        }
                        latch.countDown()
                        view?.destroy()
                    }
                }
            }
            wv.loadDataWithBaseURL("https://sandbox.local/", "<html><body></body></html>", "text/html", "utf-8", null)
        }
        latch.await(12, TimeUnit.SECONDS)
        return out.take(3000)
    }

    private fun httpGet(url: String, maxBytes: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val buf = ByteArray(maxBytes)
        var n = 0
        stream?.use { s ->
            while (n < maxBytes) {
                val r = s.read(buf, n, maxBytes - n)
                if (r < 0) break
                n += r
            }
        }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun strip(html: String): String {
        var t = html.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
        t = t.replace(Regex("<[^>]+>"), " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun webSearch(q: String): String {
        val html = httpGet("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), 250000)
        val re = Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val out = StringBuilder()
        var n = 0
        for (m in re.findAll(html)) {
            if (n >= 6) break
            var link = m.groupValues[1]
            val u = Regex("uddg=([^&]+)").find(link)
            if (u != null) link = URLDecoder.decode(u.groupValues[1], "UTF-8")
            if (link.startsWith("//")) link = "https:$link"
            out.append("- ").append(strip(m.groupValues[2])).append(" | ").append(link).append("\n")
            n++
        }
        return if (n == 0) "No results (the search page may have changed). Try fetch with a URL you know." else out.toString()
    }

    private fun fetchUrl(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "ERROR: only http/https URLs"
        return strip(httpGet(url, 300000)).take(6000)
    }

    fun run(key: String, model: String, task: String) {
        if (running) return
        running = true
        thread = Thread {
            val log = StringBuilder()
            var invalid = 0
            var step = 0
            try {
                while (running && step < 60) {
                    step++
                    val roots = roots().keys.joinToString(", ")
                    val prompt = "Available roots: $roots\n" + AiMemory.asPrompt(ctx) +
                        "\n\nTask: $task\n\nWork log so far:\n" + log.takeLast(9000) + "\n\nNext action?"
                    hooks.status("Step $step: thinking…")
                    val reply = try {
                        GroqClient.chat(key, model, system + "\n" + AiMemory.asPrompt(ctx), prompt, emptyList(), 8000)
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: RateLimitException) {
                        if (e.waitSec > 180) {
                            hooks.status("Rate limit: try again in about ${e.waitSec / 60} min, or change the model in the gear settings.")
                            break
                        }
                        hooks.status("Rate limit hit. Waiting ${e.waitSec}s…")
                        Thread.sleep(e.waitSec * 1000L)
                        continue
                    } catch (e: Exception) {
                        hooks.status("AI error: " + (e.message ?: "").take(150))
                        invalid++
                        if (invalid >= 4) break
                        Thread.sleep(5000)
                        continue
                    }
                    val cmd = parseCmd(reply)
                    if (cmd == null) {
                        invalid++
                        if (invalid >= 3) {
                            hooks.status("AI: " + reply.take(300))
                            break
                        }
                        log.append("(Your last reply was not in the ACTION format. Use the format exactly.)\n")
                        continue
                    }
                    invalid = 0
                    val f = cmd.fields
                    val root = (f["ROOT"] ?: "internal").lowercase()
                    val path = f["PATH"] ?: ""
                    var result: String
                    try {
                        when (cmd.action) {
                            "search" -> {
                                hooks.status("🔎 search: " + (f["QUERY"] ?: ""))
                                result = webSearch(f["QUERY"] ?: "")
                            }
                            "fetch" -> {
                                hooks.status("🌐 read: " + (f["URL"] ?: ""))
                                result = fetchUrl(f["URL"] ?: "")
                            }
                            "write" -> {
                                val file = resolve(root, path)
                                val body = cmd.content
                                if (file == null) {
                                    result = "ERROR: unknown root or path outside the workspace"
                                } else if (body == null) {
                                    result = "ERROR: missing CONTENT block with <<< and >>>"
                                } else {
                                    file.parentFile?.mkdirs()
                                    file.writeText(body)
                                    hooks.status("📝 wrote $root/$path (${body.length} chars)")
                                    result = "OK wrote ${file.absolutePath}"
                                }
                            }
                            "read" -> {
                                val file = resolve(root, path)
                                result = if (file == null || !file.isFile) "ERROR: file not found" else file.readText().take(6000)
                                hooks.status("📖 read $root/$path")
                            }
                            "list" -> {
                                val dir = resolve(root, path)
                                result = if (dir == null || !dir.isDirectory) "ERROR: folder not found"
                                else (dir.list()?.sorted()?.joinToString("\n") ?: "(empty)")
                                hooks.status("📁 list $root/$path")
                            }
                            "screen" -> {
                                hooks.status("👀 reading the screen")
                                result = GameAccessibilityService.instance?.dumpScreen() ?: "ERROR: accessibility is off"
                            }
                            "tap" -> {
                                val x = f["X"]?.toFloatOrNull()
                                val y = f["Y"]?.toFloatOrNull()
                                val svc = GameAccessibilityService.instance
                                if (x == null || y == null) result = "ERROR: X and Y must be numbers"
                                else if (svc == null) result = "ERROR: accessibility is off"
                                else {
                                    hooks.status("👆 tap ${x.toInt()},${y.toInt()}")
                                    ui.post { svc.stroke(listOf(Pair(x, y)), 60L, null) }
                                    Thread.sleep(700)
                                    result = "OK tapped"
                                }
                            }
                            "swipe" -> {
                                val x1 = f["X1"]?.toFloatOrNull()
                                val y1 = f["Y1"]?.toFloatOrNull()
                                val x2 = f["X2"]?.toFloatOrNull()
                                val y2 = f["Y2"]?.toFloatOrNull()
                                val svc = GameAccessibilityService.instance
                                if (x1 == null || y1 == null || x2 == null || y2 == null) result = "ERROR: X1 Y1 X2 Y2 must be numbers"
                                else if (svc == null) result = "ERROR: accessibility is off"
                                else {
                                    hooks.status("👆 swipe")
                                    ui.post { svc.stroke(listOf(Pair(x1, y1), Pair(x2, y2)), 400L, null) }
                                    Thread.sleep(900)
                                    result = "OK swiped"
                                }
                            }
                            "type" -> {
                                val svc = GameAccessibilityService.instance
                                val t = f["TEXT"] ?: ""
                                if (svc == null) result = "ERROR: accessibility is off"
                                else {
                                    hooks.status("⌨ typing")
                                    ui.post { svc.typeText(t) }
                                    Thread.sleep(600)
                                    result = "OK typed (a text field must be focused)"
                                }
                            }
                            "key" -> {
                                val svc = GameAccessibilityService.instance
                                val k = (f["KEY"] ?: "").lowercase()
                                val code = when (k) {
                                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                    else -> -1
                                }
                                if (svc == null) result = "ERROR: accessibility is off"
                                else if (code < 0) result = "ERROR: KEY must be back, home or recents"
                                else {
                                    hooks.status("🔘 $k")
                                    ui.post { svc.performGlobalAction(code) }
                                    Thread.sleep(700)
                                    result = "OK pressed $k"
                                }
                            }
                            "wait" -> {
                                val s = (f["SECONDS"]?.toIntOrNull() ?: 3).coerceIn(1, 120)
                                hooks.status("⏳ waiting ${s}s")
                                Thread.sleep(s * 1000L)
                                result = "OK waited"
                            }
                            "js" -> {
                                hooks.status("⚙ running JavaScript")
                                result = runJs(cmd.content ?: "")
                            }
                            "done" -> {
                                hooks.status("✅ " + (f["TEXT"] ?: "Done"))
                                break
                            }
                            else -> result = "ERROR: unknown action '${cmd.action}'"
                        }
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        result = "ERROR: ${e.message}. (Shared storage and SD card need the 'All files access' permission.)"
                        hooks.status("⚠ " + (e.message ?: "error").take(120))
                    }
                    log.append("[").append(cmd.action).append(" ").append(path.ifEmpty { f["QUERY"] ?: f["URL"] ?: "" })
                        .append("]\n").append(result.take(3000)).append("\n")
                }
            } catch (e: InterruptedException) {
            } finally {
                running = false
            }
        }
        thread!!.start()
    }
}
