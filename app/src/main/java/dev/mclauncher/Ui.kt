package dev.mclauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Три полоски: при progress=1 повёрнуты в вертикальное положение. */
class HamburgerView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43E06A.toInt() }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        c.save()
        c.rotate(90f * progress, cx, cy)
        for (i in -1..1) {
            val y = cy + i * 7f * d
            c.drawRoundRect(RectF(cx - 12f * d, y - 1.5f * d, cx + 12f * d, y + 1.5f * d), 2f * d, 2f * d, p)
        }
        c.restore()
    }
}

/** Логотип Microsoft: четыре цветных квадрата. */
class MsLogoView(ctx: Context) : View(ctx) {
    private val p = Paint()
    override fun onDraw(c: Canvas) {
        val s = width / 2f - 1f; val g = 2f
        fun sq(x: Float, y: Float, col: Int) { p.color = col; c.drawRect(x, y, x + s, y + s, p) }
        sq(0f, 0f, 0xFFF25022.toInt()); sq(s + g, 0f, 0xFF7FBA00.toInt())
        sq(0f, s + g, 0xFF00A4EF.toInt()); sq(s + g, s + g, 0xFFFFB900.toInt())
    }
}
