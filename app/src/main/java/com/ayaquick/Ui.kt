package com.ayaquick

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Design tokens: Steam's own palette. Navy background (#1B2838 fading to #0E141B), #171A21 bars,
 * blue-grey panels, #C7D5E0 text, Steam blue for selection and Steam green for primary actions.
 * Focused controls turn light, the way the Steam Deck UI does.
 */
object Aya {
    val BG = 0xFF0E141B.toInt()
    val BG_TOP = 0xFF1B2838.toInt()         // top of the background gradient
    val BAR = 0xFF171A21.toInt()            // top bar and footer
    val SIDEBAR = BAR
    val PANE = 0x40000000                   // preview pane, darkens the gradient behind it
    val PANEL = 0xFF16202D.toInt()
    val CARD = PANEL
    val RAISED = 0xFF233044.toInt()         // buttons, tiles
    val CARD_FOCUS = 0xFF2F4058.toInt()     // pressed row / tile
    val FILL = RAISED
    val FILL_2 = 0xFF3A4B63.toInt()
    val DIALOG = 0xFF16202D.toInt()
    val TEXT = 0xFFC7D5E0.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val DIM = 0xFF8F98A0.toInt()
    val DIMMER = 0xFF5E6A78.toInt()
    val DIVIDER = 0xFF223042.toInt()
    val LINE = DIVIDER
    val HAIRLINE = DIVIDER
    val RED = 0xFFE5484D.toInt()
    val TRACK = 0xFF3A4B63.toInt()
    val FOCUS_RING = 0xE6FFFFFF.toInt()
    val FOCUS_FILL = 0xFFDCDEDF.toInt()     // focused buttons turn light, like the Steam Deck
    val ON_FOCUS = 0xFF0E141B.toInt()
    val LINK = 0xFF66C0F4.toInt()           // Steam light blue
    val GREEN_TOP = 0xFF75B022.toInt()      // Steam "Play" button gradient
    val GREEN_BOTTOM = 0xFF588A1B.toInt()

    val ACCENTS = listOf(
        "Steam" to 0xFF1A9FFF.toInt(),
        "AYANEO" to 0xFF5B2BE0.toInt(),
        "Red" to 0xFFE5484D.toInt(),
        "Green" to 0xFF30A46C.toInt(),
        "Violet" to 0xFF8E4EC6.toInt(),
        "Amber" to 0xFFF5A524.toInt(),
        "Pink" to 0xFFD6409F.toInt(),
    )
    var ACCENT = ACCENTS[0].second
        private set

    fun setAccent(index: Int) { ACCENT = ACCENTS[index.coerceIn(0, ACCENTS.size - 1)].second }

    fun onAccent(): Int {
        val r = (ACCENT shr 16) and 0xff; val g = (ACCENT shr 8) and 0xff; val b = ACCENT and 0xff
        return if (0.299 * r + 0.587 * g + 0.114 * b > 165) 0xFF0F1115.toInt() else WHITE
    }

    fun accentAlpha(a: Int): Int = (a shl 24) or (ACCENT and 0xFFFFFF)
}

/** View helpers. Flat surfaces, thin dividers, a white ring on whatever the controller has focused. */
class Ui(val ctx: Context, private val rowPad: Int = 16) {
    val density = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * density + 0.5f).toInt()
    fun pxf(v: Float) = v * density
    private val hair get() = maxOf(1, density.toInt())

    fun font(weight: Int): Typeface = Typeface.create(Typeface.SANS_SERIF, weight, false)

    fun rounded(fill: Int, radiusDp: Float, strokeDp: Float = 0f, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = pxf(radiusDp)
        setColor(fill)
        if (strokeDp > 0) setStroke(maxOf(1, (strokeDp * density).toInt()), stroke)
    }

    /** Idle fill, lighter + white ring when focused, lighter when pressed. */
    fun focusable(fill: Int, radiusDp: Float = 6f, focusFill: Int = Aya.CARD_FOCUS, idleStroke: Int = 0) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(focusFill, radiusDp, 2f, Aya.FOCUS_RING))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(focusFill, radiusDp))
        addState(intArrayOf(), if (idleStroke != 0) rounded(fill, radiusDp, 1.5f, idleStroke) else rounded(fill, radiusDp))
    }

    fun text(t: CharSequence, sizeSp: Float = 15f, color: Int = Aya.TEXT, bold: Boolean = false, weight: Int = if (bold) 700 else 400) = TextView(ctx).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        typeface = font(weight)
        includeFontPadding = false
    }

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun card(): LinearLayout = vbox().apply {
        background = rounded(Aya.PANEL, 6f)
        setPadding(px(rowPad), px(12), px(rowPad), px(12))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = px(top) }

    fun divider(insetDp: Int = rowPad) = View(ctx).apply { setBackgroundColor(Aya.DIVIDER) }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hair).apply { leftMargin = px(insetDp); rightMargin = px(insetDp) }
    }

    fun sectionTitle(t: String) = text(t.uppercase(), 11.5f, Aya.DIM, weight = 700).apply {
        letterSpacing = 0.1f
        setPadding(px(2), px(20), 0, px(8))
    }

    /** Rectangular button. Accent fill when [selected]; Steam-green gradient when [primary]. Light fill when focused. */
    fun pill(label: String, selected: Boolean = false, textSp: Float = 14f, padH: Int = 14, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = textSp
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        typeface = font(if (selected || primary) 600 else 500)
        val idleText = when { primary -> Aya.WHITE; selected -> Aya.onAccent(); else -> Aya.TEXT }
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Aya.ON_FOCUS, idleText)))
        val idle = when {
            primary -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Aya.GREEN_TOP, Aya.GREEN_BOTTOM)).apply { cornerRadius = pxf(4f) }
            selected -> rounded(Aya.ACCENT, 4f)
            else -> rounded(Aya.RAISED, 4f)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Aya.FOCUS_FILL, 4f))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(Aya.CARD_FOCUS, 4f))
            addState(intArrayOf(), idle)
        }
        setPadding(px(padH), px(9), px(padH), px(9))
        minHeight = px(38)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * Tab bar: text tabs, the selected one underlined in the accent colour.
     * [fill] spreads the tabs across the width; otherwise they keep their natural width and scroll sideways.
     */
    fun segmented(options: List<String>, selected: Int, fill: Boolean = true, onSelect: (Int) -> Unit): View {
        val box = hbox().apply { gravity = Gravity.BOTTOM }
        options.forEachIndexed { i, label ->
            val tab = TabView(ctx, label, i == selected).apply {
                isFocusable = true
                isClickable = true
                setOnClickListener { onSelect(i) }
            }
            if (fill) box.addView(tab, LinearLayout.LayoutParams(0, px(40), label.length + 4f))   // wider tabs for longer labels
            else box.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)))
        }
        val wrap = vbox()
        if (fill) wrap.addView(box)
        else wrap.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(28))     // tabs past the edge fade out
            addView(box)
            post { box.getChildAt(selected)?.let { t -> scrollTo(maxOf(0, t.left + t.width / 2 - width / 2), 0) } }   // centre the selected tab
        })
        wrap.addView(View(ctx).apply { setBackgroundColor(Aya.DIVIDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hair))
        return wrap
    }

    /** Steam Deck style: a focused tile grows slightly and rises above its neighbours. */
    fun lift(v: View) {
        v.setOnFocusChangeListener { t, f ->
            t.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).translationZ(if (f) pxf(6f) else 0f).setDuration(110).start()
        }
    }

    /** Rows in one flat panel separated by thin dividers. */
    fun group(vararg rows: View): LinearLayout {
        val box = vbox().apply { background = rounded(Aya.PANEL, 6f) }
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(divider())
            box.addView(r)
        }
        return box
    }

    private fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Aya.CARD_FOCUS, 6f, 2f, Aya.FOCUS_RING))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(Aya.CARD_FOCUS, 6f))
        addState(intArrayOf(), rounded(0x00000000, 6f))
    }

    /** Settings row with a toggle on the right. The whole row is the focus target. */
    fun switchRow(label: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = hbox().apply {
            background = rowBackground()
            setPadding(px(rowPad), px(12), px(rowPad), px(12))
            minimumHeight = px(54)
            isFocusable = true
            isClickable = true
        }
        val texts = vbox()
        texts.addView(text(label, 15f, weight = 500))
        if (sub != null) texts.addView(text(sub, 12.5f, Aya.DIM).apply { setPadding(0, px(3), px(12), 0) })
        row.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        val toggle = Toggle(ctx, value)
        row.addView(toggle, LinearLayout.LayoutParams(px(44), px(24)))
        row.setOnClickListener {
            toggle.setOn(!toggle.on, animate = true)
            onChange(toggle.on)
        }
        return row
    }

    /** A row that leads somewhere: label, an optional value on the right, a chevron. */
    fun navRow(label: String, value: String?, onClick: () -> Unit): View {
        val row = hbox().apply {
            background = rowBackground()
            setPadding(px(rowPad), px(12), px(rowPad - 4), px(12))
            minimumHeight = px(48)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(text(label, 15f, weight = 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        if (value != null) row.addView(text(value, 13.5f, Aya.DIM, weight = 500).apply { maxLines = 1; setPadding(px(8), 0, 0, 0) })
        row.addView(android.widget.ImageView(ctx).apply { setImageDrawable(Icon(Icon.CHEVRON, Aya.DIM)) }, LinearLayout.LayoutParams(px(18), px(18)).apply { leftMargin = px(2) })
        return row
    }

    /** Steam Deck style button glyph. */
    fun glyph(key: String) = text(key, 10.5f, Aya.BAR, weight = 800).apply {
        background = rounded(Aya.TEXT, 999f); gravity = Gravity.CENTER
        setPadding(px(6), px(2), px(6), px(2)); minWidth = px(18)
    }

    /** Label + value on one line, slider underneath. */
    fun sliderRow(label: String, min: Int, max: Int, value: Int, unit: String, accent: Int = Aya.ACCENT,
                  format: (Int) -> String = { "$it$unit" }, onChange: (Int) -> Unit): LinearLayout {
        val box = vbox().apply { setPadding(px(rowPad), px(11), px(rowPad), px(5)) }
        val head = hbox()
        val v = text(format(value), 14f, Aya.DIM, weight = 500)
        head.addView(text(label, 15f, weight = 500), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        head.addView(v)
        box.addView(head)
        val bar = SeekBar(ctx).apply {
            this.max = max - min
            progress = value - min
            progressDrawable = sliderTrack(accent)
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Aya.WHITE); setSize(px(18), px(18)) }
            splitTrack = false
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(Aya.CARD_FOCUS, 8f, 2f, Aya.FOCUS_RING))
                addState(intArrayOf(), rounded(0x00000000, 8f))
            }
            setPadding(px(12), px(11), px(12), px(11))
            keyProgressIncrement = maxOf(1, (max - min) / 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    v.text = format(p + min)
                    if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(bar, lp(top = 2))
        return box
    }

    private fun sliderTrack(accent: Int): LayerDrawable {
        val bg = rounded(Aya.TRACK, 2f)
        val clip = ClipDrawable(rounded(accent, 2f), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
            setLayerHeight(0, px(4)); setLayerHeight(1, px(4))
            setLayerGravity(0, Gravity.CENTER_VERTICAL); setLayerGravity(1, Gravity.CENTER_VERTICAL)
        }
    }

    /** Colour swatch (rounded square). Selected: accent ring with a gap; focused: white ring. */
    fun chip(color: Int, sizeDp: Int = 36, onClick: () -> Unit, onLong: (() -> Unit)? = null, selected: Boolean = false) =
        ChipView(ctx, 0xFF000000.toInt() or color, selected).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)).apply { setMargins(px(3), px(3), px(3), px(3)) }
        }

    /** Fixed-column grid. */
    fun rows(views: List<View>, perRow: Int, equalWidth: Boolean = false, gapDp: Int = 3): LinearLayout {
        val box = vbox()
        var row: LinearLayout? = null
        views.forEachIndexed { i, v ->
            if (i % perRow == 0) {
                row = hbox().apply { gravity = Gravity.TOP; isBaselineAligned = false }
                box.addView(row)
            }
            if (equalWidth) {
                row!!.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(px(gapDp), px(gapDp), px(gapDp), px(gapDp)) })
            } else row!!.addView(v)
        }
        if (equalWidth) {
            val rem = views.size % perRow
            // fillers must match_parent too, or LinearLayout sizes the whole row from the 1px filler and the last row collapses
            if (rem != 0) repeat(perRow - rem) { row?.addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(px(gapDp), 0, px(gapDp), 0) }) }
        }
        return box
    }

    companion object {
        /** Blend two 0xRRGGBB colours; t = 0 gives a, 1 gives b. */
        fun lerp(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 0xff) + (((b shr s) and 0xff) - ((a shr s) and 0xff)) * k).toInt()
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        val QUICK = intArrayOf(
            0xFF0000, 0xFF4000, 0xFF8C00, 0xFFD000, 0xA0FF00, 0x00FF30, 0x00FFA0, 0x00E5FF,
            0x0090FF, 0x0030FF, 0x5A00FF, 0x9B00FF, 0xFF00FF, 0xFF0080, 0xFF6EA8, 0xFFFFFF,
        )
    }
}

/** A tab: label, accent underline when selected, soft highlight + ring when focused. */
class TabView(ctx: Context, private val label: String, private val selected: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, if (selected) 600 else 500, false)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val natural = (tp.measureText(label) + 32 * d).toInt()
        setMeasuredDimension(resolveSize(natural, widthMeasureSpec), resolveSize((40 * d).toInt(), heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (isFocused || isPressed) {
            r.set(2 * d, 3 * d, w - 2 * d, h - 6 * d)
            focus.color = if (isFocused) Aya.FOCUS_FILL else Aya.CARD_FOCUS
            c.drawRoundRect(r, 4 * d, 4 * d, focus)
        }
        tp.color = when { isFocused -> Aya.ON_FOCUS; selected -> Aya.TEXT; else -> Aya.DIM }
        c.drawText(label, w / 2, h / 2 - (tp.descent() + tp.ascent()) / 2 - 2 * d, tp)
        if (selected) {
            bar.color = Aya.ACCENT
            c.drawRect(w * 0.18f, h - 2.5f * d, w * 0.82f, h, bar)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/** Rounded-square colour swatch. */
class ChipView(ctx: Context, private val swatch: Int, private val selected: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = swatch }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x1FFFFFFF }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val showRing = isFocused || isPressed || selected
        val inset = if (showRing) 4.5f * d else 1f * d
        r.set(inset, inset, w - inset, h - inset)
        c.drawRoundRect(r, 6 * d, 6 * d, fill)
        c.drawRoundRect(r, 6 * d, 6 * d, edge)
        if (showRing) {
            ring.color = if (isFocused || isPressed) Aya.FOCUS_RING else Aya.ACCENT
            r.set(1f * d, 1f * d, w - 1f * d, h - 1f * d)
            c.drawRoundRect(r, 8 * d, 8 * d, ring)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/** Toggle: accent track when on, grey when off, white knob. Its row handles focus and clicks. */
class Toggle(ctx: Context, initial: Boolean) : View(ctx) {
    var on = initial
        private set
    private var pos = if (initial) 1f else 0f
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Aya.WHITE }
    private val rect = RectF()

    init { isFocusable = false; isClickable = false }

    fun setOn(v: Boolean, animate: Boolean) {
        on = v
        if (!animate) { pos = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 140
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        track.color = Ui.lerp(Aya.TRACK and 0xFFFFFF, Aya.ACCENT and 0xFFFFFF, pos) or 0xFF000000.toInt()
        c.drawRoundRect(rect, h / 2, h / 2, track)
        c.drawCircle(h / 2 + (w - h) * pos, h / 2, h / 2 - 3 * d, knob)
    }
}
