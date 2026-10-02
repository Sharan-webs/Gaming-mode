package com.gamingmode.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GameAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GameAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) = stroke(listOf(Pair(x, y)), 60L, null)

    /** Draws one finger stroke through [pts] lasting [durMs]. [onEnd] runs when it finishes or is cancelled. */
    fun stroke(pts: List<Pair<Float, Float>>, durMs: Long, onEnd: (() -> Unit)?) {
        if (pts.isEmpty()) {
            onEnd?.invoke()
            return
        }
        val path = Path()
        path.moveTo(maxOf(0f, pts[0].first), maxOf(0f, pts[0].second))
        for (i in 1 until pts.size) path.lineTo(maxOf(0f, pts[i].first), maxOf(0f, pts[i].second))
        val d = durMs.coerceIn(1L, 60000L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, d))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onEnd?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onEnd?.invoke()
            }
        }, null)
        if (!ok) onEnd?.invoke()
    }

    /** Lists visible text / buttons with their centre coordinates (works in normal apps; games usually show nothing). */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "(no readable window)"
        val sb = StringBuilder()
        var n = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || n >= 150) return
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (t.isNotEmpty() || d.isNotEmpty() || node.isClickable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    n++
                    sb.append("- ")
                    if (t.isNotEmpty()) sb.append("\"").append(t.take(60)).append("\" ")
                    if (d.isNotEmpty()) sb.append("desc=\"").append(d.take(60)).append("\" ")
                    if (node.isClickable) sb.append("[clickable] ")
                    sb.append("at ").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return if (n == 0) "(nothing readable - games usually draw everything as one image)" else sb.toString()
    }

    fun typeText(t: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun screenshot(cb: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        cb(sw)
                    }

                    override fun onFailure(errorCode: Int) {
                        cb(null)
                    }
                }
            )
        } catch (e: Exception) {
            cb(null)
        }
    }
}
