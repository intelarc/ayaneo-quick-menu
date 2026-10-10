package com.ayaquick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One option of a set (performance mode, fan level, ...). Looks like [Ui.pill] but its chosen state can change
 * in place, so picking an option never rebuilds the page or moves the controller focus.
 */
class ChoiceView(ctx: Context, private val label: String, textSp: Float = 13.5f) : View(ctx) {
    var chosen = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = ctx.resources.displayMetrics.density
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = textSp * ctx.resources.displayMetrics.scaledDensity }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val regular = Typeface.create(Typeface.SANS_SERIF, 500, false)
    private val strong = Typeface.create(Typeface.SANS_SERIF, 600, false)

    init { isFocusable = true; isClickable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        tp.typeface = strong
        setMeasuredDimension(resolveSize((tp.measureText(label) + 20 * d).toInt(), widthMeasureSpec), resolveSize((38 * d).toInt(), heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        bg.color = when { isFocused -> Aya.FOCUS_FILL; isPressed -> Aya.CARD_FOCUS; chosen -> Aya.ACCENT; else -> Aya.RAISED }
        c.drawRoundRect(r, 4 * d, 4 * d, bg)
        tp.typeface = if (chosen) strong else regular
        tp.color = when { isFocused -> Aya.ON_FOCUS; chosen && !isPressed -> Aya.onAccent(); else -> Aya.TEXT }
        c.drawText(label, w / 2, h / 2 - (tp.descent() + tp.ascent()) / 2, tp)
        if (chosen && isFocused) {          // the light focus fill hides the accent, so mark the chosen one underneath
            bg.color = Aya.ACCENT
            r.set(w * 0.3f, h - 5 * d, w * 0.7f, h - 2.5f * d)
            c.drawRoundRect(r, 1.5f * d, 1.5f * d, bg)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/** A page tab in the icon rail: accent bar + white icon for the current page, light fill when focused. */
class RailTab(ctx: Context, iconType: Int, label: String) : View(ctx) {
    var current = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = ctx.resources.displayMetrics.density
    private val icon = Icon(iconType, Aya.DIM)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { isFocusable = true; isClickable = true; contentDescription = label }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (isFocused || isPressed) {
            r.set(7 * d, 5 * d, w - 7 * d, h - 5 * d)
            paint.color = if (isFocused) Aya.FOCUS_FILL else Aya.CARD_FOCUS
            c.drawRoundRect(r, 5 * d, 5 * d, paint)
        } else if (current) {
            paint.color = 0x12FFFFFF
            c.drawRect(0f, 4 * d, w, h - 4 * d, paint)
        }
        if (current) {
            paint.color = Aya.ACCENT
            c.drawRect(0f, 4 * d, 3 * d, h - 4 * d, paint)
        }
        icon.color = when { isFocused -> Aya.ON_FOCUS; current -> Aya.WHITE; else -> Aya.DIM }
        val s = (11 * d).toInt(); val cx = width / 2; val cy = height / 2
        icon.setBounds(cx - s, cy - s, cx + s, cy + s)
        icon.draw(c)
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/**
 * A quick-settings tile: icon over a one or two line label. A switch tile fills with the accent while it is on;
 * a shortcut tile is flat, with a small arrow when it hands over to a settings screen.
 */
class TileView(ctx: Context, iconType: Int, label: String, private val shortcut: Boolean = false, private val opens: Boolean = false) : View(ctx) {
    var on = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = ctx.resources.displayMetrics.density
    private val lines = label.split('\n')
    private val icon = Icon(iconType, Aya.TEXT)
    private val arrow = Icon(Icon.OPEN, Aya.DIM)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = Aya.DIVIDER }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, 500, false) }
    private val baseText = 11.5f * ctx.resources.displayMetrics.scaledDensity
    private val r = RectF()

    init { isFocusable = true; isClickable = true; contentDescription = label.replace('\n', ' ') }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(getDefaultSize((68 * d).toInt(), widthMeasureSpec), resolveSize((70 * d).toInt(), heightMeasureSpec))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        bg.color = when { isFocused -> Aya.FOCUS_FILL; isPressed -> Aya.CARD_FOCUS; on -> Aya.ACCENT; shortcut -> Aya.PANEL; else -> Aya.RAISED }
        c.drawRoundRect(r, 5 * d, 5 * d, bg)
        if (shortcut && !isFocused && !isPressed) { r.inset(d / 2, d / 2); c.drawRoundRect(r, 5 * d, 5 * d, edge) }
        val fg = when { isFocused -> Aya.ON_FOCUS; on && !isPressed -> Aya.onAccent(); else -> Aya.TEXT }

        val two = lines.size > 1
        val s = (10.5f * d).toInt(); val cx = width / 2; val iy = ((if (two) 20 else 24) * d).toInt()
        icon.color = fg
        icon.setBounds(cx - s, iy - s, cx + s, iy + s)
        icon.draw(c)

        tp.textSize = baseText
        val widest = lines.maxOf { tp.measureText(it) }
        val room = w - 8 * d
        if (widest > room) tp.textSize = baseText * room / widest       // long words shrink to fit instead of clipping
        tp.color = fg
        var y = (if (two) 43 else 50) * d
        for (l in lines) { c.drawText(l, w / 2, y, tp); y += 13.5f * d }

        if (opens) {
            arrow.color = (fg and 0xFFFFFF) or (0x8C shl 24)
            val a = (5 * d).toInt(); val ax = (w - 9 * d).toInt(); val ay = (9 * d).toInt()
            arrow.setBounds(ax - a, ay - a, ax + a, ay + a)
            arrow.draw(c)
        }
        if (on && isFocused) {
            bg.color = Aya.ACCENT
            r.set(w * 0.34f, h - 5.5f * d, w * 0.66f, h - 3 * d)
            c.drawRoundRect(r, 1.5f * d, 1.5f * d, bg)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/** A live reading: small caption, big value, a quiet second line. Text is only touched when it changes. */
class StatTile(ui: Ui, caption: String) {
    private val value = ui.text("–", 18f, Aya.WHITE, weight = 700).apply { maxLines = 1 }
    private val unit = ui.text("", 11f, Aya.DIM, weight = 500).apply { maxLines = 1 }
    private val sub = ui.text("", 10.5f, Aya.DIMMER, weight = 500).apply { maxLines = 1 }
    val view: LinearLayout = ui.vbox().apply {
        background = ui.rounded(Aya.PANEL, 5f)
        setPadding(ui.px(7), ui.px(7), ui.px(4), ui.px(7))
        addView(ui.text(caption.uppercase(), 9.5f, Aya.DIM, weight = 700).apply { letterSpacing = 0.1f; maxLines = 1 })
        val line = ui.hbox().apply { gravity = android.view.Gravity.BOTTOM }
        line.addView(value)
        line.addView(unit, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(2); bottomMargin = ui.px(2) })
        addView(line, ui.lp(top = 4))
        addView(sub, ui.lp(top = 2))
    }

    fun set(v: String, u: String, s: String) {
        put(value, v); put(unit, u); put(sub, s)
    }

    private fun put(t: TextView, s: String) { if (t.text.toString() != s) t.text = s }
}

/** Battery glyph for the footer: fill follows the level, Steam green while charging, red when nearly empty. */
class BatteryView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * d; color = Aya.DIM }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var level = -1
    private var charging = false

    fun set(pct: Int, isCharging: Boolean) {
        if (pct == level && isCharging == charging) return
        level = pct; charging = isCharging
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = w - 2.5f * d
        r.set(d, d, bw, h - d)
        c.drawRoundRect(r, 2 * d, 2 * d, line)
        fill.color = Aya.DIM
        c.drawRect(bw + 0.5f * d, h * 0.33f, w, h * 0.67f, fill)
        if (level < 0) return
        fill.color = when { charging -> Aya.GREEN_TOP; level <= 15 -> Aya.RED; else -> Aya.TEXT }
        val inner = bw - 4.6f * d
        r.set(2.8f * d, 2.8f * d, 2.8f * d + inner * level.coerceIn(4, 100) / 100f, h - 2.8f * d)
        c.drawRoundRect(r, 0.8f * d, 0.8f * d, fill)
    }
}
