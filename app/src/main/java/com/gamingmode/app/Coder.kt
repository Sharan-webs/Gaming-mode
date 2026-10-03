package com.gamingmode.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The "coding" AI (screenshot detection OFF). It searches the web, reads and writes files, runs JavaScript,
 * reads the screen as text, controls the phone, changes settings and creates floating buttons.
 */
class Coder(private val ctx: Context, private val hooks: Agent.Hooks) {

    @Volatile
    var running = false
        private set
    private var thread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    private val system = """
You are an agent inside an Android app with master access to the phone. Reply with one or more ACTION blocks (they run in order, put done last). Plain text, one field per line, no markdown.
Example:
ACTION: write
ROOT: phone
PATH: Download/test.txt
CONTENT:
<<<
file text
>>>
ACTION: done
TEXT: summary
Actions: search (QUERY) | fetch (URL) | read, list (ROOT, PATH) | write (ROOT, PATH, CONTENT) | screen (visible text + tap coordinates) | tap (X, Y) | swipe (X1, Y1, X2, Y2) | type (TEXT) | key (KEY: back|home|recents|notifications|quick|power|lock) | open (NAME: app name or package) | setting (NS: system|secure|global, KEY, VALUE) | getsetting (NS, KEY) | wait (SECONDS) | js (CONTENT: sandboxed JavaScript, console.log or last expression is the result) | button (LABEL, CONTENT: script) | shell (CMD, or a CONTENT block for several lines: full shell via Shizuku; use it for system files and game folders in Android/data) | menu (LABEL, CONTENT: lines like  Label :: command ; command) | done (TEXT).
button creates a floating on-screen button that runs a script. Script lines: tap X Y | hold X Y MS | swipe X1 Y1 X2 Y2 [MS] | wait MS | key NAME | type TEXT | open NAME | setting NS KEY VALUE | brightness 0-255 | volume 0-100 | shell CMD | repeat N ... end | forever ... end. Coordinates are screen pixels (use screen to find them). Never write Android source files to make a button: use button.
ROOT: internal, shared (GamingModeAI folder), sdcard (SD GamingModeAI folder), phone (all phone storage), sdroot (whole SD card). ROOT shell uses absolute paths through Shizuku and reaches everything, including Android/data. Files outside your own folders get a .bak backup before being overwritten.
If screen shows nothing, the app is probably a game: tell the user to switch screenshot detection ON. Keep replies short.
""".trimIndent()

    private val accErr = "ERROR: the accessibility service is not connected. The user must turn it off and on once in Settings (or use Master access)."

    fun stop() {
        running = false
        thread?.interrupt()
    }

    fun roots(): LinkedHashMap<String, File> {
        val m = LinkedHashMap<String, File>()
        m["internal"] = File(ctx.filesDir, "workspace")
        m["shared"] = File(Environment.getExternalStorageDirectory(), "GamingModeAI")
        m["phone"] = Environment.getExternalStorageDirectory()
        val p = ctx.getExternalFilesDirs(null).getOrNull(1)?.absolutePath
        if (p != null) {
            val i = p.indexOf("/Android")
            if (i > 0) {
                m["sdcard"] = File(p.substring(0, i), "GamingModeAI")
                m["sdroot"] = File(p.substring(0, i))
            }
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
    private class Out(val text: String, val done: Boolean = false)

    private fun parseBlock(lines: List<String>): Cmd? {
        val fields = HashMap<String, String>()
        var content: String? = null
        var i = 0
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

    private fun parseCmds(reply: String): List<Cmd> {
        val lines = GroqClient.clean(reply).lines()
        val blocks = ArrayList<MutableList<String>>()
        var inside = false
        for (l in lines) {
            val t = l.trim()
            if (!inside && t.startsWith("ACTION:")) blocks.add(ArrayList())
            if (blocks.isNotEmpty()) blocks.last().add(l)
            if (t.startsWith("<<<")) inside = !(t.length > 6 && t.endsWith(">>>"))
            else if (inside && t.endsWith(">>>")) inside = false
        }
        val out = ArrayList<Cmd>()
        for (b in blocks) {
            val c = parseBlock(b)
            if (c != null) out.add(c)
        }
        return out
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

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun shellFile(action: String, path: String, content: String?): String {
        if (!path.startsWith("/")) return "ERROR: with ROOT shell the PATH must be absolute (start with /)"
        return when (action) {
            "read" -> Shell.run("cat ${q(path)}").take(6000)
            "list" -> Shell.run("ls -la ${q(path)}").take(6000)
            else -> {
                if (content == null) return "ERROR: missing CONTENT block with <<< and >>>"
                val dir = File(Environment.getExternalStorageDirectory(), "GamingModeAI")
                dir.mkdirs()
                val tmp = File(dir, ".xfer")
                tmp.writeText(content)
                val parent = path.substringBeforeLast('/', "/")
                Shell.run(
                    "mkdir -p ${q(parent)} && { [ -f ${q(path)} ] && [ ! -f ${q(path + ".bak")} ] && cp ${q(path)} ${q(path + ".bak")}; cp ${q(tmp.absolutePath)} ${q(path)}; }"
                )
            }
        }
    }

    private fun exec(cmd: Cmd): Out {
        val f = cmd.fields
        val root = (f["ROOT"] ?: "internal").lowercase()
        val path = f["PATH"] ?: ""
        if (root == "shell" && (cmd.action == "read" || cmd.action == "write" || cmd.action == "list")) {
            return Out(shellFile(cmd.action, path, cmd.content))
        }
        return try {
            when (cmd.action) {
                "search" -> {
                    hooks.status("🔎 " + (f["QUERY"] ?: ""))
                    Out(webSearch(f["QUERY"] ?: ""))
                }
                "fetch" -> {
                    hooks.status("🌐 " + (f["URL"] ?: ""))
                    Out(fetchUrl(f["URL"] ?: ""))
                }
                "write" -> {
                    val file = resolve(root, path)
                    val body = cmd.content
                    if (file == null) {
                        Out("ERROR: unknown ROOT or path outside the root")
                    } else if (body == null) {
                        Out("ERROR: missing CONTENT block with <<< and >>>")
                    } else {
                        file.parentFile?.mkdirs()
                        if ((root == "phone" || root == "sdroot") && file.exists()) {
                            val bak = File(file.path + ".bak")
                            if (!bak.exists()) file.copyTo(bak)
                        }
                        file.writeText(body)
                        hooks.status("📝 wrote $root/$path (${body.length} chars)")
                        Out("OK wrote " + file.absolutePath)
                    }
                }
                "read" -> {
                    val file = resolve(root, path)
                    hooks.status("📖 read $root/$path")
                    Out(if (file == null || !file.isFile) "ERROR: file not found" else file.readText().take(6000))
                }
                "list" -> {
                    val dir = resolve(root, path)
                    hooks.status("📁 list $root/$path")
                    Out(if (dir == null || !dir.isDirectory) "ERROR: folder not found" else (dir.list()?.sorted()?.joinToString("\n") ?: "(empty)"))
                }
                "screen" -> {
                    hooks.status("👀 reading the screen")
                    Out(GameAccessibilityService.instance?.dumpScreen() ?: accErr)
                }
                "tap" -> {
                    val x = f["X"]?.toFloatOrNull()
                    val y = f["Y"]?.toFloatOrNull()
                    val svc = GameAccessibilityService.instance
                    if (x == null || y == null) {
                        Out("ERROR: X and Y must be numbers")
                    } else if (svc == null) {
                        Out(accErr)
                    } else {
                        hooks.status("👆 tap ${x.toInt()},${y.toInt()}")
                        ui.post { svc.stroke(listOf(Pair(x, y)), 60L, null) }
                        Thread.sleep(600)
                        Out("OK tapped")
                    }
                }
                "swipe" -> {
                    val x1 = f["X1"]?.toFloatOrNull()
                    val y1 = f["Y1"]?.toFloatOrNull()
                    val x2 = f["X2"]?.toFloatOrNull()
                    val y2 = f["Y2"]?.toFloatOrNull()
                    val svc = GameAccessibilityService.instance
                    if (x1 == null || y1 == null || x2 == null || y2 == null) {
                        Out("ERROR: X1 Y1 X2 Y2 must be numbers")
                    } else if (svc == null) {
                        Out(accErr)
                    } else {
                        hooks.status("👆 swipe")
                        ui.post { svc.stroke(listOf(Pair(x1, y1), Pair(x2, y2)), 400L, null) }
                        Thread.sleep(800)
                        Out("OK swiped")
                    }
                }
                "type" -> {
                    val svc = GameAccessibilityService.instance
                    val t = f["TEXT"] ?: ""
                    if (svc == null) {
                        Out(accErr)
                    } else {
                        hooks.status("⌨ typing")
                        ui.post { svc.typeText(t) }
                        Thread.sleep(500)
                        Out("OK typed (a text field must be focused)")
                    }
                }
                "key" -> {
                    val svc = GameAccessibilityService.instance
                    val k = (f["KEY"] ?: "").lowercase()
                    val code = when (k) {
                        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                        "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                        "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                        else -> -1
                    }
                    if (svc == null) {
                        Out(accErr)
                    } else if (code < 0) {
                        Out("ERROR: KEY must be back, home, recents, notifications, quick, power or lock")
                    } else {
                        hooks.status("🔘 $k")
                        ui.post { svc.performGlobalAction(code) }
                        Thread.sleep(600)
                        Out("OK pressed $k")
                    }
                }
                "open" -> {
                    hooks.status("📱 open " + (f["NAME"] ?: ""))
                    val r = PhoneCtl.openApp(ctx, f["NAME"] ?: "")
                    Thread.sleep(900)
                    Out(r)
                }
                "setting" -> {
                    hooks.status("⚙ setting " + (f["KEY"] ?: ""))
                    Out(PhoneCtl.setSetting(ctx, f["NS"] ?: "", f["KEY"] ?: "", f["VALUE"] ?: ""))
                }
                "getsetting" -> Out(PhoneCtl.getSetting(ctx, f["NS"] ?: "", f["KEY"] ?: ""))
                "shell" -> {
                    val c = cmd.content ?: f["CMD"] ?: ""
                    hooks.status("# " + c.lines().firstOrNull().orEmpty().take(60))
                    Out(Shell.run(c))
                }
                "menu" -> {
                    val title = (f["LABEL"] ?: f["TITLE"] ?: "Menu").trim().ifEmpty { "Menu" }
                    val body = cmd.content ?: ""
                    if (!body.contains("::")) {
                        Out("ERROR: CONTENT needs lines like  Label :: command ; command")
                    } else {
                        hooks.addMenu(title, body)
                        hooks.status("🧩 menu '$title' created")
                        Out("OK floating menu '$title' created")
                    }
                }
                "wait" -> {
                    val s = (f["SECONDS"]?.toIntOrNull() ?: 3).coerceIn(1, 120)
                    hooks.status("⏳ waiting ${s}s")
                    Thread.sleep(s * 1000L)
                    Out("OK waited")
                }
                "js" -> {
                    hooks.status("⚙ running JavaScript")
                    Out(runJs(cmd.content ?: ""))
                }
                "button" -> {
                    val label = (f["LABEL"] ?: "AI").trim().ifEmpty { "AI" }
                    val body = cmd.content ?: ""
                    if (body.isBlank()) {
                        Out("ERROR: missing CONTENT script")
                    } else {
                        hooks.addButton(label, body)
                        hooks.status("🧩 button '$label' created")
                        Out("OK floating button '$label' created")
                    }
                }
                "done" -> {
                    hooks.status("✅ " + (f["TEXT"] ?: "Done"))
                    Out(f["TEXT"] ?: "Done", true)
                }
                else -> Out("ERROR: unknown action '${cmd.action}'")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            hooks.status("⚠ " + (e.message ?: "error").take(120))
            Out("ERROR: ${e.message}. (Shared storage and SD card need the 'All files access' permission.)")
        }
    }

    fun run(key: String, modelList: String, task: String) {
        if (running) return
        running = true
        thread = Thread {
            val models = modelList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("openai/gpt-oss-120b") }
            var mi = 0
            var limitedRow = 0
            val log = StringBuilder()
            var invalid = 0
            var errors = 0
            var step = 0
            try {
                outer@ while (running && step < 80) {
                    step++
                    val prompt = AiMemory.asPrompt(ctx) + "Task: $task\n\nLog:\n" + log.takeLast(6000) + "\n\nNext action(s)?"
                    hooks.status("Step $step (${models[mi].substringAfterLast('/')})…")
                    var limitWait = -1L
                    val reply = try {
                        GroqClient.chat(key, models[mi], system + Persona.text(ctx), prompt, emptyList(), 6000)
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
                    if (reply == null) {
                        if (limitWait >= 0) {
                            limitedRow++
                            if (models.size > 1 && limitedRow < models.size) {
                                mi = (mi + 1) % models.size
                                hooks.status("Limit reached. Switching to ${models[mi]} (same task and memory)…")
                                continue
                            }
                            limitedRow = 0
                            if (limitWait > 180) {
                                hooks.status("All models are limited. Try again in about ${limitWait / 60} min, or add more models in the gear settings.")
                                break
                            }
                            hooks.status("Rate limit hit. Waiting ${limitWait}s, then I'll continue…")
                            Thread.sleep(limitWait * 1000L)
                            continue
                        }
                        if (models.size > 1) {
                            mi = (mi + 1) % models.size
                            hooks.status("Switching to ${models[mi]} (same task and memory)…")
                        }
                        if (errors >= 6) {
                            hooks.status("Stopped: too many AI errors. Check the key and the model names in the gear settings.")
                            break
                        }
                        Thread.sleep(3000)
                        continue
                    }
                    errors = 0
                    limitedRow = 0
                    val cmds = parseCmds(reply)
                    if (cmds.isEmpty()) {
                        invalid++
                        if (invalid >= 3) {
                            hooks.status("AI: " + reply.take(300))
                            break
                        }
                        log.append("(Your last reply was not in the ACTION format. Use the format exactly.)\n")
                        continue
                    }
                    invalid = 0
                    for (cmd in cmds) {
                        if (!running) break@outer
                        val out = exec(cmd)
                        log.append("[").append(cmd.action).append(" ")
                            .append(cmd.fields["PATH"] ?: cmd.fields["QUERY"] ?: cmd.fields["URL"] ?: "")
                            .append("]\n").append(out.text.take(2500)).append("\n")
                        if (out.done) break@outer
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
