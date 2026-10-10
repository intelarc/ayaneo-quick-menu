package com.ayaquick

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line icons drawn in code on a 24 x 24 grid (crisp at any size, no image assets), in the same hand as
 * AYANEO RGB Control's icons: round caps, one stroke weight, a few solid shapes.
 */
class Icon(private val type: Int, color: Int) : Drawable() {
    companion object {
        const val BOLT = 0; const val GAMEPAD = 1; const val SLIDERS = 2; const val GRID = 3; const val GEAR = 4
        const val WIFI = 5; const val BLUETOOTH = 6; const val PLANE = 7; const val MOON = 8; const val ROTATE = 9
        const val DND = 10; const val SCREENSHOT = 11; const val CAST = 12; const val HOTSPOT = 13; const val LOCATION = 14
        const val HOME = 15; const val RECENTS = 16; const val BELL = 17; const val POWER = 18; const val LOCK = 19
        const val SUN = 20; const val SPEAKER = 21; const val CHEVRON = 22; const val OPEN = 23; const val RING = 24
    }

    var color = color
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s)
        c.scale(s, s)
        p.color = color; f.color = color
        p.strokeWidth = 1.9f
        path.rewind()
        when (type) {
            BOLT -> {
                path.moveTo(13.4f, 2f); path.lineTo(5f, 13.6f); path.lineTo(11f, 13.6f); path.lineTo(10.2f, 22f)
                path.lineTo(19f, 10f); path.lineTo(13f, 10f); path.close()
                c.drawPath(path, f)
            }
            GAMEPAD -> {
                r.set(2f, 6.8f, 22f, 17.2f); c.drawRoundRect(r, 5.2f, 5.2f, p)
                c.drawLine(7.4f, 10.3f, 7.4f, 13.7f, p); c.drawLine(5.7f, 12f, 9.1f, 12f, p)
                c.drawCircle(15.3f, 13.2f, 1.15f, f); c.drawCircle(17.9f, 10.9f, 1.15f, f)
            }
            SLIDERS -> {
                val knob = floatArrayOf(9f, 15.5f, 8f)
                for (k in 0..2) {
                    val y = 6f + k * 6f
                    p.alpha = 120; c.drawLine(4f, y, 20f, y, p); p.alpha = 255
                    c.drawCircle(knob[k], y, 2.3f, f)
                }
            }
            GRID -> for (k in 0..3) {
                val x = if (k % 2 == 0) 4f else 13.4f; val y = if (k < 2) 4f else 13.4f
                r.set(x, y, x + 6.6f, y + 6.6f); c.drawRoundRect(r, 1.6f, 1.6f, f)
            }
            GEAR -> {
                c.drawCircle(12f, 12f, 6.2f, p); c.drawCircle(12f, 12f, 2.3f, f)
                p.strokeWidth = 2.6f
                for (k in 0 until 8) ray(c, Math.PI / 4 * k, 7.4f, 9.3f)
            }
            WIFI -> {
                c.drawCircle(12f, 18.4f, 1.5f, f)
                for (rad in floatArrayOf(5f, 9.2f, 13.4f)) { r.set(12f - rad, 18.4f - rad, 12f + rad, 18.4f + rad); c.drawArc(r, 225f, 90f, false, p) }
            }
            BLUETOOTH -> {
                path.moveTo(7.2f, 7.6f); path.lineTo(16.6f, 16.4f); path.lineTo(12f, 21f); path.lineTo(12f, 3f)
                path.lineTo(16.6f, 7.6f); path.lineTo(7.2f, 16.4f)
                c.drawPath(path, p)
            }
            PLANE -> {
                path.moveTo(21f, 16f); path.lineTo(21f, 14f); path.lineTo(13f, 9f); path.lineTo(13f, 3.5f)
                path.cubicTo(13f, 2.67f, 12.33f, 2f, 11.5f, 2f); path.cubicTo(10.67f, 2f, 10f, 2.67f, 10f, 3.5f)
                path.lineTo(10f, 9f); path.lineTo(2f, 14f); path.lineTo(2f, 16f); path.lineTo(10f, 13.5f); path.lineTo(10f, 19f)
                path.lineTo(8f, 20.5f); path.lineTo(8f, 22f); path.lineTo(11.5f, 21f); path.lineTo(15f, 22f); path.lineTo(15f, 20.5f)
                path.lineTo(13f, 19f); path.lineTo(13f, 13.5f); path.close()
                c.drawPath(path, f)
            }
            MOON -> {
                path.addCircle(11.4f, 12.4f, 8.2f, Path.Direction.CW)
                val cut = Path().apply { addCircle(15.6f, 8.6f, 7f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, f)
            }
            ROTATE -> {
                c.save(); c.rotate(-45f, 12f, 12f)
                r.set(8.6f, 6f, 15.4f, 18f); c.drawRoundRect(r, 1.6f, 1.6f, p)
                c.restore()
                arrowArc(c, 10.2f, -78f, 62f); arrowArc(c, 10.2f, 102f, 62f)
            }
            DND -> { c.drawCircle(12f, 12f, 8.6f, p); c.drawLine(8f, 12f, 16f, 12f, p) }
            SCREENSHOT -> {
                val x0 = 4f; val y0 = 5f; val x1 = 20f; val y1 = 19f; val l = 4.2f
                c.drawLine(x0, y0 + l, x0, y0, p); c.drawLine(x0, y0, x0 + l, y0, p)
                c.drawLine(x1 - l, y0, x1, y0, p); c.drawLine(x1, y0, x1, y0 + l, p)
                c.drawLine(x1, y1 - l, x1, y1, p); c.drawLine(x1, y1, x1 - l, y1, p)
                c.drawLine(x0 + l, y1, x0, y1, p); c.drawLine(x0, y1, x0, y1 - l, p)
                c.drawCircle(12f, 12f, 2.4f, f)
            }
            CAST -> {
                path.moveTo(3f, 8.6f); path.lineTo(3f, 5f); path.lineTo(21f, 5f); path.lineTo(21f, 19f); path.lineTo(14.2f, 19f)
                c.drawPath(path, p)
                c.drawCircle(3.6f, 18.4f, 1.35f, f)
                for (rad in floatArrayOf(4.2f, 8f)) { r.set(3f - rad, 19f - rad, 3f + rad, 19f + rad); c.drawArc(r, 270f, 90f, false, p) }
            }
            HOTSPOT -> {
                c.drawCircle(12f, 13f, 1.9f, f)
                for (rad in floatArrayOf(5.2f, 9.4f)) { r.set(12f - rad, 13f - rad, 12f + rad, 13f + rad); c.drawArc(r, 150f, 240f, false, p) }
            }
            LOCATION -> {
                r.set(5.8f, 3.3f, 18.2f, 15.7f)
                path.arcTo(r, 140f, 260f); path.lineTo(12f, 21.6f); path.close()
                c.drawPath(path, p)
                c.drawCircle(12f, 9.5f, 2.1f, f)
            }
            HOME -> {
                path.moveTo(3.6f, 11.6f); path.lineTo(12f, 4.2f); path.lineTo(20.4f, 11.6f)
                path.moveTo(6.4f, 10f); path.lineTo(6.4f, 19.6f); path.lineTo(17.6f, 19.6f); path.lineTo(17.6f, 10f)
                c.drawPath(path, p)
            }
            RECENTS -> { r.set(5f, 5f, 19f, 19f); c.drawRoundRect(r, 2.4f, 2.4f, p) }
            BELL -> {
                path.moveTo(7f, 17.2f); path.lineTo(7f, 11f); path.cubicTo(7f, 7.7f, 9.2f, 5.6f, 12f, 5.6f)
                path.cubicTo(14.8f, 5.6f, 17f, 7.7f, 17f, 11f); path.lineTo(17f, 17.2f)
                path.moveTo(4.8f, 17.2f); path.lineTo(19.2f, 17.2f)
                path.moveTo(12f, 3.6f); path.lineTo(12f, 5.4f)
                c.drawPath(path, p)
                c.drawCircle(12f, 20.2f, 1.45f, f)
            }
            POWER -> {
                r.set(4.4f, 5f, 19.6f, 20.2f); c.drawArc(r, 300f, 300f, false, p)
                c.drawLine(12f, 3.4f, 12f, 11.6f, p)
            }
            LOCK -> {
                r.set(6f, 10.8f, 18f, 20.6f); c.drawRoundRect(r, 2.2f, 2.2f, p)
                r.set(8.5f, 4.4f, 15.5f, 11.4f)
                path.moveTo(8.5f, 10.8f); path.lineTo(8.5f, 7.9f); path.arcTo(r, 180f, 180f); path.lineTo(15.5f, 10.8f)
                c.drawPath(path, p)
            }
            SUN -> {
                c.drawCircle(12f, 12f, 3.7f, f)
                for (k in 0 until 8) ray(c, Math.PI / 4 * k, 6.5f, 8.8f)
            }
            SPEAKER -> {
                path.moveTo(3.6f, 9.6f); path.lineTo(7.6f, 9.6f); path.lineTo(12.2f, 5.4f); path.lineTo(12.2f, 18.6f)
                path.lineTo(7.6f, 14.4f); path.lineTo(3.6f, 14.4f); path.close()
                c.drawPath(path, f)
                for (rad in floatArrayOf(3.4f, 6.8f)) { r.set(12.6f - rad, 12f - rad, 12.6f + rad, 12f + rad); c.drawArc(r, -45f, 90f, false, p) }
            }
            CHEVRON -> { path.moveTo(9.4f, 6.4f); path.lineTo(15f, 12f); path.lineTo(9.4f, 17.6f); c.drawPath(path, p) }
            OPEN -> {
                path.moveTo(7.4f, 16.6f); path.lineTo(16.6f, 7.4f)
                path.moveTo(9.6f, 7.4f); path.lineTo(16.6f, 7.4f); path.lineTo(16.6f, 14.4f)
                c.drawPath(path, p)
            }
            RING -> {           // a stick ring split into its four LEDs
                r.set(4.6f, 4.6f, 19.4f, 19.4f)
                p.strokeWidth = 2.4f
                for (k in 0..3) c.drawArc(r, 90f * k + 14f, 62f, false, p)
                c.drawCircle(12f, 12f, 2.4f, f)
            }
        }
        c.restore()
    }

    private fun ray(c: Canvas, a: Double, r1: Float, r2: Float) =
        c.drawLine(12f + r1 * cos(a).toFloat(), 12f + r1 * sin(a).toFloat(), 12f + r2 * cos(a).toFloat(), 12f + r2 * sin(a).toFloat(), p)

    /** A clockwise arc around the centre with an arrowhead at its end. */
    private fun arrowArc(c: Canvas, rad: Float, start: Float, sweep: Float) {
        r.set(12f - rad, 12f - rad, 12f + rad, 12f + rad)
        c.drawArc(r, start, sweep, false, p)
        val a = Math.toRadians((start + sweep).toDouble())
        val ex = 12f + rad * cos(a).toFloat(); val ey = 12f + rad * sin(a).toFloat()
        val back = a - Math.PI / 2          // pointing back along the arc
        for (spread in doubleArrayOf(-0.6, 0.6)) {
            val d = back + spread
            c.drawLine(ex, ey, ex + 3.2f * cos(d).toFloat(), ey + 3.2f * sin(d).toFloat(), p)
        }
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; f.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
