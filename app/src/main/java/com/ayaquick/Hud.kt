package com.ayaquick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * The performance overlay drawn on top of games, in three sizes like the Steam Deck's: the frame rate alone, one
 * line of readings, or a detailed readout (frame-rate history, per-core clocks, GPU, memory, fan, battery, mode).
 * The detailed one is a panel or, after a tap, a strip along the top, the way AYASpace's own overlay flips.
 * One view, drawn straight to the canvas once a second.
 */
class HudView(ctx: Context, val level: Int) : View(ctx) {

    companion object {
        const val OFF = 0; const val FPS = 1; const val COMPACT = 2; const val DETAILED = 3
        val LABELS = listOf("Off", "FPS", "Compact", "Detailed")

        // MangoHud's label colours, as the Steam Deck overlay uses them
        private const val C_CPU = 0xFF4FB4E8.toInt()
        private const val C_GPU = 0xFF6CC04A.toInt()
        private const val C_RAM = 0xFFD678B6.toInt()
        private const val C_BAT = 0xFFF5A524.toInt()
        private const val C_FAN = 0xFF7FD6D0.toInt()
        private const val C_FPS = 0xFFEB5B5B.toInt()
        private const val BACK = 0xD90B1016.toInt()
        private const val HISTORY = 60

        private var logo: Bitmap? = null      // decoded once per process
        private var armLogo: Bitmap? = null       // the full "ARM Powered" mark, for the panel
        private var armMark: Bitmap? = null       // just "ARM", for the strip, where one caption covers both makers
    }

    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private val screenDp = ctx.resources.displayMetrics.widthPixels / d
    private val strong = Typeface.create(Typeface.SANS_SERIF, 700, false)
    private val medium = Typeface.create(Typeface.SANS_SERIF, 500, false)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d; strokeJoin = Paint.Join.ROUND; color = C_GPU }
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val path = Path()
    private val history = FloatArray(HISTORY) { -1f }
    private var head = 0
    private val store = Store(ctx)
    private var strip = store.hudStrip
    private val screen = ctx.resources.displayMetrics.let { m ->
        val p = android.graphics.Point()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealSize(p)
        if (p.x > 0) "${p.x}×${p.y}" else "${m.widthPixels}×${m.heightPixels}"
    }
    private val clock = android.text.format.DateFormat.getTimeFormat(ctx)

    init {
        if (level == DETAILED) {
            if (logo == null) logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.mediatek_wordmark)
            if (armLogo == null) armLogo = BitmapFactory.decodeResource(ctx.resources, R.drawable.arm_powered)
            if (armMark == null) armMark = BitmapFactory.decodeResource(ctx.resources, R.drawable.arm_mark)
            setOnClickListener {            // a tap flips between the panel and the strip
                strip = !strip
                store.hudStrip = strip
                requestLayout()
                invalidate()
            }
        }
    }

    /** Take the newest readings from [Stats] and redraw. */
    fun update() {
        history[head] = Stats.fps
        head = (head + 1) % HISTORY
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = when {
            level == FPS -> 46f to 22f
            level == COMPACT -> 318f to 22f
            strip -> minOf(STRIP_W, screenDp - 12f) to 56f
            else -> 176f to 286f
        }
        setMeasuredDimension((w * d).toInt(), (h * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        shape.color = BACK
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, 4 * d, 4 * d, shape)
        when {
            level == FPS -> { label(c, "FPS", 6f, 15f, C_FPS); value(c, fps(), 41f, 15.5f, 12.5f, right = true) }
            level == COMPACT -> drawCompact(c)
            strip -> drawStrip(c)
            else -> drawPanel(c)
        }
    }

    private fun fps() = if (Stats.fps >= 0) Math.round(Stats.fps).toString() else "–"
    private fun temp(t: Float) = if (t > 0) "${Math.round(t)}°C" else ""
    private fun cpuClock() = if (Stats.cpuGhz > 0) String.format("%.2f GHz", Stats.cpuGhz) else "–"
    private fun gpuClock() = if (Stats.gpuMhz > 0) "${Stats.gpuMhz} MHz" else ""
    private fun ram() = if (Stats.ramPct >= 0) String.format("%.1f / %.1f GB", Stats.ramUsedGb, Stats.ramTotalGb) else "–"
    private fun fan() = if (!Stats.fanOn) "Off" else if (Stats.fan >= 0) "${Stats.fan}%" else "–"
    private fun mode() = PerfMode.values().getOrNull(store.perfMode)?.takeIf { it.governor == Props.governor() }?.label ?: Props.governor().ifEmpty { "–" }
    private fun watts() = if (Stats.watts > 0) String.format("%.1f W", Stats.watts) else ""
    private fun swap() = if (Stats.swapTotalGb > 0) String.format("%.1f / %.1f GB", Stats.swapUsedGb, Stats.swapTotalGb) else "–"
    private fun power() = if (Stats.watts > 0) String.format("%s%.1f W", if (Stats.charging) "+" else "−", Stats.watts) else "–"
    private fun time() = clock.format(java.util.Date())

    // ---------------------------------------------------------------- one line

    private fun drawCompact(c: Canvas) {
        val s = Stats
        var x = 7f
        fun item(name: String, colour: Int, v: String, width: Float) {
            label(c, name, x, 15f, colour)
            value(c, v, x + width - 8f, 15.5f, 12f, right = true)
            x += width
        }
        item("FPS", C_FPS, fps(), 52f)
        item("CPU", C_CPU, if (s.cpuGhz > 0) String.format("%.1f GHz", s.cpuGhz) else "–", 82f)
        item("GPU", C_GPU, if (s.gpuLoad >= 0) "${s.gpuLoad}%" else "–", 62f)
        item("BAT", C_BAT, if (s.battTemp > 0) "${Math.round(s.battTemp)}°" else "–", 58f)
        value(c, watts(), 311f, 15.5f, 12f, right = true)
    }

    // ---------------------------------------------------------------- detailed, as a panel

    private fun drawPanel(c: Canvas) {
        val s = Stats
        val left = 9f; val right = 167f

        // frame rate, with the time one frame takes and the last minute as a graph
        value(c, fps(), left, 25f, 24f)
        label(c, "FPS", left + text.measureText(fps()) / d + 4f, 25f, C_FPS)
        if (s.fps >= 10f) value(c, String.format("%.1f ms", 1000f / s.fps), right, 25f, 11f, right = true, colour = Aya.DIM)
        drawHistory(c, left, 31f, right, 55f)

        var y = 73f
        fun row(name: String, colour: Int, vararg cells: String) {
            label(c, name, left, y, colour)
            // up to three right-aligned cells in fixed columns, so the numbers do not shift as they change
            val cols = floatArrayOf(right - 87f, right - 55f, right)
            val start = 3 - cells.size
            cells.forEachIndexed { i, v -> value(c, v, cols[start + i], y, 11.5f, right = true) }
            y += 16.5f
        }
        row("CPU", C_CPU, if (s.cpuLoad >= 0) "${s.cpuLoad}%" else "", temp(s.cpuTemp), cpuClock())
        drawCores(c, left, y - 11.5f, right, y - 3.5f)
        drawCoreClocks(c, left, right, y + 7f, y + 18f)
        y += 34.5f
        row("GPU", C_GPU, if (s.gpuLoad >= 0) "${s.gpuLoad}%" else "–", temp(s.gpuTemp), gpuClock())
        value(c, GPU, left, y - 4.5f, 9f, colour = Aya.DIM)
        value(c, screen, right, y - 4.5f, 9f, right = true, colour = Aya.DIM)
        y += 13f
        row("RAM", C_RAM, ram())
        row("SWAP", C_RAM, swap())
        row("FAN", C_FAN, fan(), if (store.fanManual >= 0) "Held" else "Auto")
        row("BAT", C_BAT, if (s.battPct >= 0) "${s.battPct}%" else "", temp(s.battTemp), power())
        row("MODE", Aya.DIM, mode())
        row("TIME", Aya.DIM, time())

        divider(c, left, y - 7.5f, right)
        credit(c, left, y + 5.5f, "POWERED BY")
        drawLogo(c, logo, left, y + 9f, 7f)
        value(c, CHIP, left, y + 26.5f, 8.5f, colour = Aya.DIM)
        drawLogo(c, armLogo, right, y + 2f, 25f, alignRight = true)
    }

    // ---------------------------------------------------------------- detailed, as a strip along the top

    private fun drawStrip(c: Canvas) {
        val s = Stats
        val room = width / d
        var x = 9f
        val l1 = 15f; val l2 = 29.5f; val l3 = 44f

        /** One block of the strip; blocks that would run off a narrower screen are left out. */
        fun block(w: Float, draw: (Float) -> Unit) {
            if (x + w > room) return
            if (x > 9f) { shape.color = 0x26FFFFFF; c.drawRect((x - 6f) * d, 8f * d, (x - 6f) * d + maxOf(1f, d / 2), 48f * d, shape) }
            draw(x)
            x += w + 12f
        }
        fun pair(x0: Float, w: Float, y: Float, name: String, colour: Int, v: String) {
            label(c, name, x0, y, colour)
            value(c, v, x0 + w, y, 11.5f, right = true)
        }
        fun joined(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("  ")

        block(84f) { x0 ->
            label(c, "FPS", x0, 14f, C_FPS)
            value(c, fps(), x0, 36f, 22f)
            value(c, time(), x0, 48.5f, 8.5f, colour = Aya.DIM)
            drawHistory(c, x0 + 40f, 8f, x0 + 84f, 48f)
        }
        block(140f) { x0 ->
            label(c, "CPU", x0, l1, C_CPU)
            value(c, joined(if (s.cpuLoad >= 0) "${s.cpuLoad}%" else "", temp(s.cpuTemp), cpuClock()), x0 + 140f, l1, 11.5f, right = true)
            drawCoreClocks(c, x0, x0 + 140f, l2 + 1f, l3 + 1f)
        }
        block(84f) { x0 ->
            label(c, "GPU", x0, l1, C_GPU)
            value(c, joined(if (s.gpuLoad >= 0) "${s.gpuLoad}%" else "", temp(s.gpuTemp)), x0 + 84f, l1, 11.5f, right = true)
            value(c, gpuClock(), x0 + 84f, l2, 11.5f, right = true)
            value(c, screen, x0 + 84f, l3, 9f, right = true, colour = Aya.DIM)
        }
        block(104f) { x0 ->
            pair(x0, 104f, l1, "RAM", C_RAM, ram())
            pair(x0, 104f, l2, "SWAP", C_RAM, swap())
            pair(x0, 104f, l3, "FAN", C_FAN, fan() + if (store.fanManual >= 0) "  Held" else "  Auto")
        }
        block(84f) { x0 ->
            pair(x0, 84f, l1, "BAT", C_BAT, joined(if (s.battPct >= 0) "${s.battPct}%" else "", temp(s.battTemp)))
            pair(x0, 84f, l2, "PWR", C_BAT, power())
            pair(x0, 84f, l3, "MODE", Aya.DIM, mode())
        }
        block(48f) { x0 ->
            credit(c, x0, 15f, "POWERED BY")
            drawLogo(c, logo, x0, 19.5f, 5.5f)
            drawLogo(c, armMark, x0, 32.5f, 8f)
        }
    }

    // ---------------------------------------------------------------- shared pieces

    private fun divider(c: Canvas, x0: Float, y: Float, x1: Float) {
        shape.color = 0x26FFFFFF
        c.drawRect(x0 * d, y * d, x1 * d, y * d + maxOf(1f, d / 2), shape)
    }

    private fun credit(c: Canvas, x: Float, y: Float, s: String) {
        text.typeface = strong; text.textSize = 6.2f * sd; text.color = Aya.DIM; text.letterSpacing = 0.12f
        text.textAlign = Paint.Align.LEFT
        c.drawText(s, x * d, y * d, text)
        text.letterSpacing = 0f
    }

    /** A maker's mark, [heightDp] tall, with its left edge (or, with [alignRight], its right edge) at [x]. */
    private fun drawLogo(c: Canvas, b: Bitmap?, x: Float, y: Float, heightDp: Float, alignRight: Boolean = false) {
        b ?: return
        val h = heightDp * d; val w = h * b.width / b.height
        val x0 = if (alignRight) x * d - w else x * d
        r.set(x0, y * d, x0 + w, y * d + h)
        c.drawBitmap(b, null, r, img)
    }

    /** Every core's clock in MHz, four to a line, as AYASpace's own detailed overlay lists them. */
    private fun drawCoreClocks(c: Canvas, x0: Float, x1: Float, y1: Float, y2: Float) {
        val col = (x1 - x0) / 4
        for (i in 0 until 8) {
            val mhz = Stats.coreMhz[i]
            value(c, if (mhz > 0) mhz.toString() else "off", x0 + col * (i % 4 + 1), if (i < 4) y1 else y2, 9.5f, right = true, colour = Aya.TEXT)
        }
    }

    /** Frame rate over the last minute, newest on the right. The scale tops out at 60 or the highest value seen. */
    private fun drawHistory(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        shape.color = 0x14FFFFFF
        r.set(x0 * d, y0 * d, x1 * d, y1 * d)
        c.drawRoundRect(r, 2 * d, 2 * d, shape)
        var top = 60f
        for (v in history) if (v > top) top = v
        path.rewind()
        var started = false
        val step = (x1 - x0 - 4f) * d / (HISTORY - 1)
        for (i in 0 until HISTORY) {
            val v = history[(head + i) % HISTORY]
            if (v < 0) continue
            val px = (x0 + 2f) * d + i * step
            val py = (y1 - 2f) * d - (v / top).coerceIn(0f, 1f) * (y1 - y0 - 4f) * d
            if (started) path.lineTo(px, py) else { path.moveTo(px, py); started = true }
        }
        if (started) c.drawPath(path, line)
    }

    /** One bar per core, as tall as its clock relative to its maximum; the two big cores stand slightly apart. */
    private fun drawCores(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val gap = 2.5f; val split = 5f
        val w = (x1 - x0 - gap * 7 - split) / 8
        for (i in 0 until 8) {
            val x = x0 + i * (w + gap) + if (i >= 6) split else 0f
            shape.color = 0x1FFFFFFF
            r.set(x * d, y0 * d, (x + w) * d, y1 * d)
            c.drawRoundRect(r, d, d, shape)
            val share = Stats.coreShare[i]
            if (share <= 0f) continue
            shape.color = C_CPU
            r.set(x * d, (y1 - (y1 - y0) * share.coerceIn(0.08f, 1f)) * d, (x + w) * d, y1 * d)
            c.drawRoundRect(r, d, d, shape)
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, colour: Int) {
        text.typeface = strong; text.textSize = 9.5f * sd; text.color = colour; text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0.04f
        c.drawText(s, x * d, y * d, text)
        text.letterSpacing = 0f
    }

    private fun value(c: Canvas, s: String, x: Float, y: Float, sizeSp: Float, right: Boolean = false, colour: Int = Aya.WHITE) {
        text.typeface = if (sizeSp >= 20f) strong else medium
        text.textSize = sizeSp * sd; text.color = colour
        text.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(s, x * d, y * d, text)
    }
}

private const val STRIP_W = 624f

// The chip in this handheld, for the overlay's credits. MT6785 is the Helio G90T, with Arm's Mali-G76 MC4.
private val CHIP = if (android.os.Build.HARDWARE.contains("6785")) "Helio G90T" else android.os.Build.HARDWARE.uppercase()
private val GPU = if (android.os.Build.HARDWARE.contains("6785")) "Mali-G76 MC4" else "Mali"
