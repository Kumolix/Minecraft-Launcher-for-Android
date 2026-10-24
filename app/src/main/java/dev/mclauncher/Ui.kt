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

/** Цвета светлой/тёмной темы лаунчера (зелёный акцент в обеих). */
object Th {
    var light = false
    private fun c(d: Long, l: Long) = (if (light) l else d).toInt()
    val bg get() = c(0xFF0B0F0C, 0xFFF1F6F2)
    val bar get() = c(0xFF121A15, 0xFFE2EDE5)
    val drawer get() = c(0xFF0F1712, 0xFFFFFFFF)
    val skinBg get() = c(0xFF0E1F14, 0xFFDCEBDF)
    val accent get() = c(0xFF43E06A, 0xFF1B7F3A)
    val text get() = c(0xFFFFFFFF, 0xFF10200F)
    val text2 get() = c(0xFFB8E6C4, 0xFF2B4B33)
    val dim get() = c(0xFF7FA08A, 0xFF5A7A62)
    val line get() = c(0xFF22332A, 0xFFCADBCE)
    val line2 get() = c(0xFF1B2A20, 0xFFDCE8DF)
    val altBg get() = (if (light) 0xFF0B0F0C else 0xFFF1F6F2).toInt()
    val altText get() = (if (light) 0xFF43E06A else 0xFF10200F).toInt()
    val hint get() = c(0xFF58705F, 0xFF8AA391)
}
