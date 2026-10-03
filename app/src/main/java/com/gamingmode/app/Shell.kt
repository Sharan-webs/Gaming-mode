package com.gamingmode.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Runs commands with Shizuku (shell / ADB power). Everything here is a no-op until Shizuku is running and allowed. */
object Shell {

    // Only the commands that can wipe or brick the whole phone are refused.
    private val blocked = listOf(
        Regex("(^|[;&|] ?)rm (-[a-z]+ )*(/|/\\*|/sdcard/?\\*?|/storage/?\\*?|/storage/emulated(/0)?/?\\*?|/system\\S*|/data/?\\*?|/vendor\\S*|/product\\S*)( |\$|;|&)"),
        Regex("(^|[;&|] ?)wm (density|size) (?!reset)"),
        Regex("mkfs|(^| )dd .*of=/dev|wipe |factory.?reset|reboot (bootloader|recovery|fastboot)|fastboot|recovery --")
    )

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    private fun dangerous(cmd: String): Boolean {
        val c = cmd.lowercase().replace(Regex("\\s+"), " ")
        return blocked.any { it.containsMatchIn(c) }
    }

    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    fun run(cmd: String, timeoutMs: Long = 20000L): String {
        if (!ready()) return "ERROR: Shizuku is not running or this app is not allowed in Shizuku"
        if (dangerous(cmd)) return "BLOCKED: this command could wipe or brick the phone"
        return try {
            val p = newProcess(arrayOf("sh", "-c", "( $cmd ) 2>&1"))
            val sb = StringBuilder()
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (sb.length < 20000) sb.append(buf, 0, n)
                        }
                    }
                } catch (e: Exception) {
                }
            }
            t.start()
            t.join(timeoutMs)
            if (t.isAlive) {
                p.destroy()
                sb.append("\n(timed out)")
            }
            sb.toString().trim().ifEmpty { "(done, no output)" }
        } catch (e: Throwable) {
            "ERROR: " + (e.cause?.message ?: e.message)
        }
    }

    /** Live output: [onText] is called as text arrives. Returns the process so it can be stopped. */
    fun stream(cmd: String, onText: (String) -> Unit): Process? {
        if (!ready()) {
            onText("ERROR: Shizuku is not running or this app is not allowed in Shizuku\n")
            return null
        }
        if (dangerous(cmd)) {
            onText("BLOCKED: this command could wipe or brick the phone\n")
            return null
        }
        return try {
            val p = newProcess(arrayOf("sh", "-c", "( $cmd ) 2>&1"))
            Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            onText(String(buf, 0, n))
                        }
                    }
                } catch (e: Exception) {
                }
                onText("\n[finished]\n")
            }.start()
            p
        } catch (e: Throwable) {
            onText("ERROR: " + (e.cause?.message ?: e.message) + "\n")
            null
        }
    }

    /** The app gives itself every permission it needs. */
    fun grantSelf(ctx: Context): String {
        val pkg = ctx.packageName
        val cmds = listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg MANAGE_EXTERNAL_STORAGE allow",
            "appops set $pkg WRITE_SETTINGS allow",
            "cmd deviceidle whitelist +$pkg"
        )
        val sb = StringBuilder()
        for (c in cmds) sb.append(c.substringBefore(' ')).append(": ").append(run(c)).append("\n")
        Master.enableAccessibility(ctx)
        return sb.toString().trim()
    }
}
