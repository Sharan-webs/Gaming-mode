package com.gamingmode.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Perm(
        val title: String,
        val desc: String,
        val required: Boolean,
        val granted: () -> Boolean,
        val request: () -> Unit
    )

    private lateinit var perms: List<Perm>
    private lateinit var sheet: LinearLayout
    private lateinit var permList: LinearLayout
    private lateinit var mainStatus: TextView
    private lateinit var mainStartBtn: Button
    private lateinit var sheetStartBtn: Button
    private lateinit var showIconBtn: Button
    private var notifAsked = false

    private fun dp(v: Int) = UI.dp(this, v)

    private fun notifOk() =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName) && s.contains("GameAccessibilityService")
    }

    private fun requiredOk() = perms.filter { it.required }.all { it.granted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        perms = listOf(
            Perm(
                "Display over other apps",
                "Needed for the floating game icon and menus.",
                true,
                { Settings.canDrawOverlays(this) },
                { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            ),
            Perm(
                "Accessibility (touch control + screenshots)",
                "Lets the AI and your macros tap, swipe, type and see the screen. If the switch is greyed out on Android 13+: open App info, tap the ⋮ menu, choose 'Allow restricted settings', then try again.",
                true,
                { accessibilityOn() },
                { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ),
            Perm(
                "Notifications",
                "Shows that gaming mode is running and the macro recording notice.",
                true,
                { notifOk() },
                {
                    if (Build.VERSION.SDK_INT >= 33 && !notifAsked) {
                        notifAsked = true
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    } else {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    }
                }
            ),
            Perm(
                "Modify system settings (optional)",
                "Used by the sensitivity booster.",
                false,
                { Settings.System.canWrite(this) },
                { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            ),
            Perm(
                "All files access (optional)",
                "Lets the coding AI save files to phone storage and the SD card.",
                false,
                { Environment.isExternalStorageManager() },
                { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            ),
            Perm(
                "Run in background (optional)",
                "Stops the battery saver from killing gaming mode.",
                false,
                { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
                { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            )
        )

        val rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(0xFF0E1018.toInt())

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.gravity = Gravity.CENTER_HORIZONTAL
        main.setPadding(dp(24), dp(90), dp(24), dp(40))
        main.addView(UI.text(this, "🎮 Gaming Mode", 28f, Color.WHITE, true))
        val sub = UI.text(this, "AI-powered overlay, macros and screen control", 14f, UI.MUTED)
        sub.setPadding(0, dp(8), 0, dp(28))
        main.addView(sub)
        mainStatus = UI.text(this, "", 15f, Color.WHITE, true)
        main.addView(mainStatus)
        main.addView(UI.button(this, "Enable Gaming Mode", UI.ACCENT) { openSheet() }, UI.match(this, 18))
        mainStartBtn = UI.button(this, "", UI.GREEN) { toggleGaming() }
        main.addView(mainStartBtn, UI.match(this, 12))
        showIconBtn = UI.button(this, "Show floating icon") {
            startService(Intent(this, GamingService::class.java).setAction("SHOW_ICON"))
        }
        main.addView(showIconBtn, UI.match(this, 12))
        rootFrame.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        val r = dp(24).toFloat()
        val g = GradientDrawable()
        g.setColor(0xFF171A27.toInt())
        g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = g
        sheet.setPadding(dp(16), dp(16), dp(16), dp(16))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(UI.text(this, "Permissions", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(UI.button(this, "✕") { closeSheet() })
        sheet.addView(head)

        permList = LinearLayout(this)
        permList.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(permList)
        sheet.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        sheetStartBtn = UI.button(this, "", UI.GREEN) { toggleGaming() }
        sheet.addView(sheetStartBtn, UI.match(this, 10))

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.topMargin = dp(90)
        rootFrame.addView(sheet, lp)
        sheet.translationY = resources.displayMetrics.heightPixels.toFloat()

        setContentView(rootFrame)
    }

    private fun openSheet() {
        refresh()
        sheet.animate().translationY(0f).setDuration(250).start()
    }

    private fun closeSheet() {
        sheet.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(250).start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun toggleGaming() {
        val svc = Intent(this, GamingService::class.java)
        if (GamingService.running) {
            stopService(svc)
        } else {
            if (!requiredOk()) {
                Toast.makeText(this, "Enable the required permissions first.", Toast.LENGTH_LONG).show()
                openSheet()
                return
            }
            startForegroundService(svc)
        }
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        permList.removeAllViews()
        for (p in perms) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = UI.bg(this, UI.CARD, 14f)
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.addView(UI.text(this, p.title, 15f, Color.WHITE, true))
            card.addView(UI.text(this, p.desc, 12f, UI.MUTED))
            val ok = p.granted()
            if (ok) {
                card.addView(UI.text(this, "✓ Enabled", 13f, UI.GREEN, true), UI.match(this, 6))
            } else {
                card.addView(UI.button(this, if (p.required) "Enable" else "Enable (optional)", UI.ACCENT) { p.request() }, UI.match(this, 8))
            }
            permList.addView(card, UI.match(this, 10))
        }
        val running = GamingService.running
        mainStatus.text = if (running) "Gaming mode is RUNNING" else "Gaming mode is off"
        val label = if (running) "■ Stop gaming mode" else "▶ Start gaming mode"
        val color = if (running) UI.RED else UI.GREEN
        for (b in listOf(mainStartBtn, sheetStartBtn)) {
            b.text = label
            b.background = UI.bg(this, color, 12f)
            b.alpha = if (running || requiredOk()) 1f else 0.5f
        }
        showIconBtn.alpha = if (running) 1f else 0.4f
    }
}
