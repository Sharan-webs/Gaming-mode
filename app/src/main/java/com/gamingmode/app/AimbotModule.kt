package com.gamingmode.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class AimbotModule(val context: Context) {
    
    companion object {
        const val HEAD_DETECTION_RANGE = 300f
        const val AUTO_ADJUST_SPEED = 0.15f
        const val CROSSHAIR_SIZE = 24f
        const val LOCK_THRESHOLD = 0.85f
    }
    
    private var enabled = false
    private var headOnly = true
    private var autoTrack = false
    
    private var screenWidth = 1080f
    private var screenHeight = 1920f
    
    private var targetHeadX = 0f
    private var targetHeadY = 0f
    private var headDetected = false
    private var detectionConfidence = 0f
    
    private var crosshairX = screenWidth / 2f
    private var crosshairY = screenHeight / 2f
    
    private var lockStrength = 0f
    private var smoothingFactor = 0.12f
    
    // Injection state
    private var injectionActive = false
    private var lastUpdateTime = 0L
    
    fun setScreenDimensions(w: Int, h: Int) {
        screenWidth = w.toFloat()
        screenHeight = h.toFloat()
        crosshairX = screenWidth / 2f
        crosshairY = screenHeight / 2f
    }
    
    fun toggleAimbot(state: Boolean) {
        enabled = state
        if (!state) {
            stopTracking()
        }
    }
    
    fun toggleHeadOnly(state: Boolean) {
        headOnly = state
    }
    
    fun toggleAutoTrack(state: Boolean) {
        autoTrack = state
        if (state && enabled) {
            startTracking()
        } else if (!state) {
            stopTracking()
        }
    }
    
    private fun startTracking() {
        injectionActive = true
        lockStrength = 0f
    }
    
    private fun stopTracking() {
        injectionActive = false
        lockStrength = 0f
        crosshairX = screenWidth / 2f
        crosshairY = screenHeight / 2f
    }
    
    fun updateTargetPosition(x: Float, y: Float, confidence: Float) {
        if (!enabled || !autoTrack) return
        
        targetHeadX = x
        targetHeadY = y
        detectionConfidence = confidence
        headDetected = confidence > 0.5f
        
        if (headDetected && confidence >= LOCK_THRESHOLD) {
            lockStrength = minOf(1f, lockStrength + 0.08f)
        } else {
            lockStrength = maxOf(0f, lockStrength - 0.06f)
        }
        
        lastUpdateTime = System.currentTimeMillis()
    }
    
    fun smoothCrosshair() {
        if (!enabled || !injectionActive || !headDetected) return
        
        // Smooth interpolation toward target
        val dx = targetHeadX - crosshairX
        val dy = targetHeadY - crosshairY
        
        val factor = smoothingFactor * (lockStrength + 0.1f)
        
        crosshairX += dx * factor
        crosshairY += dy * factor
    }
    
    fun handleMotionEvent(event: MotionEvent): MotionEvent {
        if (!enabled) return event
        
        // Intercept tap events for aim assistance
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            if (autoTrack && headDetected && lockStrength > 0.3f) {
                // Adjust reported coordinates toward target
                val adjustedX = event.x + (targetHeadX - crosshairX) * (lockStrength * 0.4f)
                val adjustedY = event.y + (targetHeadY - crosshairY) * (lockStrength * 0.4f)
                
                return MotionEvent.obtain(
                    event.downTime,
                    event.eventTime,
                    event.action,
                    adjustedX,
                    adjustedY,
                    event.pressure,
                    event.size,
                    event.metaState,
                    event.xPrecision,
                    event.yPrecision,
                    event.deviceId,
                    event.edgeFlags
                )
            }
        }
        
        return event
    }
    
    fun drawCrosshair(canvas: Canvas) {
        if (!enabled) return
        
        val paint = Paint().apply {
            color = when {
                lockStrength > 0.7f -> Color.RED
                lockStrength > 0.4f -> Color.argb(255, 255, 165, 0)
                else -> Color.YELLOW
            }
            strokeWidth = 2f
            isAntiAlias = true
        }
        
        val alpha = (255 * lockStrength).toInt().coerceIn(0, 200)
        paint.alpha = alpha
        
        val size = CROSSHAIR_SIZE * (0.8f + lockStrength * 0.4f)
        
        // Horizontal line
        canvas.drawLine(crosshairX - size, crosshairY, crosshairX + size, crosshairY, paint)
        
        // Vertical line
        canvas.drawLine(crosshairX, crosshairY - size, crosshairX, crosshairY + size, paint)
        
        // Center dot
        paint.strokeWidth = 0f
        canvas.drawCircle(crosshairX, crosshairY, 4f + lockStrength * 2f, paint)
        
        // Lock indicator ring (when locked)
        if (lockStrength > 0.6f) {
            paint.strokeWidth = 1.5f
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(crosshairX, crosshairY, size + 8f, paint)
        }
        
        // Head detection indicator
        if (headDetected) {
            val textPaint = Paint().apply {
                color = Color.argb((255 * lockStrength).toInt(), 46, 182, 125)
                textSize = 12f
                isAntiAlias = true
            }
            canvas.drawText("HEAD", crosshairX + size + 12f, crosshairY - size - 4f, textPaint)
        }
    }
    
    fun getConfig(): AimbotConfig {
        return AimbotConfig(
            enabled = enabled,
            headOnly = headOnly,
            autoTrack = autoTrack,
            lockStrength = lockStrength,
            headDetected = headDetected,
            crosshairX = crosshairX,
            crosshairY = crosshairY
        )
    }
    
    fun setSmoothingFactor(factor: Float) {
        smoothingFactor = factor.coerceIn(0.05f, 0.5f)
    }
    
    data class AimbotConfig(
        val enabled: Boolean,
        val headOnly: Boolean,
        val autoTrack: Boolean,
        val lockStrength: Float,
        val headDetected: Boolean,
        val crosshairX: Float,
        val crosshairY: Float
    )
}
