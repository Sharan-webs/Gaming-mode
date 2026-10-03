package com.gamingmode.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object UI {
    val BG = 0xF0141622.toInt()
    val ACCENT = 0xFF6C3BFF.toInt()
    val CARD = 0xFF262B3F.toInt()
    val FIELD = 0xFF1C2030.toInt()
    val RED = 0xFFE5484D.toInt()
    val GREEN = 0xFF2EB67D.toInt()
    val MUTED = 0xFFB8C0D9.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun bg(c: Context, color: Int, radiusDp: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusDp * c.resources.displayMetrics.density
        return g
    }

    fun grad(c: Context, c1: Int, c2: Int, radiusDp: Float): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2))
        g.cornerRadius = radiusDp * c.resources.displayMetrics.density
        return g
    }

    fun panel(c: Context): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2191C2E.toInt(), 0xF20E101B.toInt()))
        g.cornerRadius = 20f * c.resources.displayMetrics.density
        g.setStroke(maxOf(1, (c.resources.displayMetrics.density).toInt()), 0x33FFFFFF)
        return g
    }

    fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    fun text(c: Context, s: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun button(c: Context, label: String, color: Int = CARD, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 14f
        val base: Drawable = if (color == ACCENT) grad(c, 0xFF7C4DFF.toInt(), 0xFF4F7CFF.toInt(), 12f) else bg(c, color, 12f)
        b.background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), base, null)
        b.minHeight = 0
        b.minimumHeight = 0
        b.stateListAnimator = null
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        b.setOnClickListener { onClick() }
        return b
    }

    fun edit(c: Context, hint: String, value: String = "", multiline: Boolean = false): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(0xFF8890A8.toInt())
        e.setTextColor(Color.WHITE)
        e.textSize = 14f
        e.background = bg(c, FIELD, 10f)
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        if (multiline) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.maxLines = 3
        } else {
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.setSingleLine(true)
        }
        e.setText(value)
        return e
    }

    fun match(c: Context, topDp: Int = 6): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(c, topDp)
        return p
    }
}
