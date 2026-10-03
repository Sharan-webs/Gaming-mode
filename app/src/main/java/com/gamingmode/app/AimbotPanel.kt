package com.gamingmode.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class AimbotPanel(
    val context: Context,
    val aimbot: AimbotModule,
    val onClose: () -> Unit
) {
    
    private lateinit var headOnlySwitch: Switch
    private lateinit var autoTrackSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var shootButton: Button
    
    val view: LinearLayout by lazy { buildPanel() }
    
    private fun dp(v: Int) = UI.dp(context, v)
    
    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        val r = dp(16).toFloat()
        val bg = GradientDrawable().apply {
            setColor(0xFF1A1F2E.toInt())
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.background = bg
        panel.setPadding(dp(16), dp(16), dp(16), dp(16))
        
        // Header with title and close button
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        
        val title = UI.text(context, "🔫 Aimbot FreeFire Max", 18f, Color.WHITE, true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        
        val closeBtn = UI.button(context, "✕", UI.RED) { 
            panel.visibility = ViewGroup.GONE
            onClose() 
        }
        closeBtn.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        header.addView(closeBtn)
        
        panel.addView(header, UI.match(context, 0))
        
        // Status indicator
        statusText = UI.text(context, "Status: Standby", 13f, UI.MUTED)
        statusText.setPadding(0, dp(8), 0, dp(12))
        panel.addView(statusText)
        
        // Head Only Toggle
        val headRow = buildToggleRow(
            label = "Head Only",
            description = "Target head only, not body",
            initialState = true,
            onToggle = { state ->
                aimbot.toggleHeadOnly(state)
                updateStatus()
            }
        )
        headOnlySwitch = headRow.second
        panel.addView(headRow.first, UI.match(context, 8))
        
        // Auto Track Toggle
        val trackRow = buildToggleRow(
            label = "Auto Track",
            description = "Automatically lock crosshair to head",
            initialState = false,
            onToggle = { state ->
                aimbot.toggleAutoTrack(state)
                updateStatus()
            }
        )
        autoTrackSwitch = trackRow.second
        panel.addView(trackRow.first, UI.match(context, 8))
        
        // Instructions
        val instr = UI.text(
            context,
            "✓ Enable in accessibility settings\n" +
            "✓ Press 'Detect Head' to start tracking\n" +
            "✓ Aim will assist when enabled",
            11f,
            UI.MUTED
        )
        instr.setPadding(0, dp(12), 0, dp(12))
        panel.addView(instr)
        
        // Action buttons
        val actionLayout = LinearLayout(context)
        actionLayout.orientation = LinearLayout.HORIZONTAL
        actionLayout.gravity = Gravity.CENTER
        
        val detectBtn = UI.button(context, "🔍 Detect Head", UI.ACCENT) {
            startHeadDetection()
        }
        detectBtn.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        actionLayout.addView(detectBtn)
        
        val spacer = View(context)
        spacer.layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        actionLayout.addView(spacer)
        
        shootButton = UI.button(context, "💥 Shoot", Color.parseColor("#FF4444")) {
            simulateShoot()
        }
        shootButton.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        shootButton.alpha = 0.5f
        actionLayout.addView(shootButton)
        
        panel.addView(actionLayout, UI.match(context, 8))
        
        return panel
    }
    
    private fun buildToggleRow(
        label: String,
        description: String,
        initialState: Boolean,
        onToggle: (Boolean) -> Unit
    ): Pair<LinearLayout, Switch> {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = UI.bg(context, UI.CARD, 10f)
        row.setPadding(dp(12), dp(8), dp(12), dp(8))
        
        val textLayout = LinearLayout(context)
        textLayout.orientation = LinearLayout.VERTICAL
        textLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        
        val labelText = UI.text(context, label, 14f, Color.WHITE, true)
        textLayout.addView(labelText)
        
        val descText = UI.text(context, description, 11f, UI.MUTED)
        textLayout.addView(descText)
        
        row.addView(textLayout)
        
        val switch = Switch(context)
        switch.isChecked = initialState
        switch.setOnCheckedChangeListener { _, isChecked ->
            onToggle(isChecked)
        }
        row.addView(switch)
        
        return Pair(row, switch)
    }
    
    private fun startHeadDetection() {
        if (!autoTrackSwitch.isChecked) {
            autoTrackSwitch.isChecked = true
        }
        Toast.makeText(context, "Head detection started...", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun simulateShoot() {
        if (autoTrackSwitch.isChecked) {
            val config = aimbot.getConfig()
            if (config.lockStrength > 0.5f) {
                Toast.makeText(context, "🎯 Shot fired! Lock strength: ${(config.lockStrength * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                shootButton.alpha = 1f
            } else {
                Toast.makeText(context, "⚠️ Acquire target first", Toast.LENGTH_SHORT).show()
                shootButton.alpha = 0.5f
            }
        } else {
            Toast.makeText(context, "Enable Auto Track first", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateStatus() {
        val config = aimbot.getConfig()
        val status = when {
            config.autoTrack && config.headDetected -> 
                "🔴 LOCKED | Head detected | Lock: ${(config.lockStrength * 100).toInt()}%"
            config.autoTrack -> 
                "🟡 TRACKING | Searching for target..."
            else -> 
                "⚪ STANDBY | ${if (config.headOnly) "Head Only" else "Body+Head"}"
        }
        statusText.text = status
        
        // Enable shoot button only when locked
        shootButton.alpha = if (config.lockStrength > 0.5f) 1f else 0.4f
    }
    
    fun updateTracking() {
        updateStatus()
    }
}
