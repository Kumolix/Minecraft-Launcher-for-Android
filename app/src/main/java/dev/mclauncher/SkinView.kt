package dev.mclauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Лёгкий программный 3D-просмотр скина игрока: вращается сам, крутится пальцем. */
class SkinView(ctx: Context) : View(ctx) {
    private var skin: Bitmap? = null
    private var yaw = 25f
    private var pitch = 8f
    private var lastY = 0f
    private var lastX = 0f
    private var touching = false
    private var last = System.nanoTime()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setSkin(b: Bitmap?) { skin = b; postInvalidate() }

    private class Part(
        val cx: Float, val cy: Float, val w: Float, val h: Float, val d: Float,
        val u: Int, val v: Int, val infl: Float, val over: Boolean
    )

    var slim = false
        set(v) { field = v; postInvalidate() }
    private val parts: List<Part> get() { val aw = if (slim) 3f else 4f; val ax = 4f + aw / 2f; return listOf(
        // база: голова, тело, руки, ноги
        Part(0f, 28f, 8f, 8f, 8f, 0, 0, 0f, false),
        Part(0f, 18f, 8f, 12f, 4f, 16, 16, 0f, false),
        Part(-ax, 18f, aw, 12f, 4f, 40, 16, 0f, false),
        Part(ax, 18f, aw, 12f, 4f, 32, 48, 0f, false),
        Part(-2f, 6f, 4f, 12f, 4f, 0, 16, 0f, false),
        Part(2f, 6f, 4f, 12f, 4f, 16, 48, 0f, false),
        // второй слой: шапка, куртка, рукава, штаны
        Part(0f, 28f, 8f, 8f, 8f, 32, 0, 0.5f, true),
        Part(0f, 18f, 8f, 12f, 4f, 16, 32, 0.25f, true),
        Part(-ax, 18f, aw, 12f, 4f, 40, 32, 0.25f, true),
        Part(ax, 18f, aw, 12f, 4f, 48, 48, 0.25f, true),
        Part(-2f, 6f, 4f, 12f, 4f, 0, 32, 0.25f, true),
        Part(2f, 6f, 4f, 12f, 4f, 0, 48, 0.25f, true)
    ) }

    private class Face(val pts: FloatArray, val src: FloatArray, val rect: Rect, val depth: Float)

    private fun faces(w: Int, h: Int): List<Face> {
        val out = ArrayList<Face>()
        val a = Math.toRadians(yaw.toDouble()); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
        val p = Math.toRadians(-pitch.toDouble()); val cp = Math.cos(p).toFloat(); val sp = Math.sin(p).toFloat()
        val dist = 70f
        val f = h * 0.88f * dist / 36f
        for (pt in parts) {
            val hx = pt.w / 2 + pt.infl; val hy = pt.h / 2 + pt.infl; val hz = pt.d / 2 + pt.infl
            val tw = pt.w.toInt(); val th = pt.h.toInt(); val td = pt.d.toInt()
            val u = pt.u; val v = pt.v
            // (4 угла: левый-верх, правый-верх, правый-низ, левый-низ), прямоугольник текстуры
            class F(val c: FloatArray, val r: Rect)
            val fs = listOf(
                F(floatArrayOf(-hx, hy, hz, hx, hy, hz, hx, -hy, hz, -hx, -hy, hz), Rect(u + td, v + td, u + td + tw, v + td + th)),
                F(floatArrayOf(hx, hy, -hz, -hx, hy, -hz, -hx, -hy, -hz, hx, -hy, -hz), Rect(u + 2 * td + tw, v + td, u + 2 * td + 2 * tw, v + td + th)),
                F(floatArrayOf(-hx, hy, -hz, -hx, hy, hz, -hx, -hy, hz, -hx, -hy, -hz), Rect(u, v + td, u + td, v + td + th)),
                F(floatArrayOf(hx, hy, hz, hx, hy, -hz, hx, -hy, -hz, hx, -hy, hz), Rect(u + td + tw, v + td, u + 2 * td + tw, v + td + th)),
                F(floatArrayOf(-hx, hy, -hz, hx, hy, -hz, hx, hy, hz, -hx, hy, hz), Rect(u + td, v, u + td + tw, v + td)),
                F(floatArrayOf(-hx, -hy, hz, hx, -hy, hz, hx, -hy, -hz, -hx, -hy, -hz), Rect(u + td + tw, v, u + td + 2 * tw, v + td))
            )
            for (fc in fs) {
                val dst = FloatArray(8); var dsum = 0f
                for (i in 0 until 4) {
                    var x = fc.c[i * 3] + pt.cx; var y = fc.c[i * 3 + 1] + pt.cy - 16f; var z = fc.c[i * 3 + 2]
                    val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca
                    val y2 = y * cp - z1 * sp; val z2 = y * sp + z1 * cp
                    val depth = dist - z2
                    dst[i * 2] = w / 2f + f * x1 / depth * 1f
                    dst[i * 2 + 1] = h / 2f - f * y2 / depth * 1f
                    dsum += depth
                    x = 0f; y = 0f; z = 0f
                }
                var area = 0f
                for (i in 0 until 4) {
                    val j = (i + 1) % 4
                    area += dst[i * 2] * dst[j * 2 + 1] - dst[j * 2] * dst[i * 2 + 1]
                }
                if (area <= 0f) continue
                val r = fc.r
                val src = floatArrayOf(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.top.toFloat(),
                    r.right.toFloat(), r.bottom.toFloat(), r.left.toFloat(), r.bottom.toFloat())
                out.add(Face(dst, src, r, dsum / 4f - (if (pt.over) 0.6f else 0f)))
            }
        }
        out.sortByDescending { it.depth }
        return out
    }

    override fun onDraw(c: Canvas) {
        val b = skin
        if (b == null) {
            msg.textSize = 14f * resources.displayMetrics.density
            c.drawText("Скин появится после первой загрузки игры", width / 2f, height / 2f, msg)
            return
        }
        val now = System.nanoTime()
        if (!touching) yaw += (now - last) / 1e9f * 25f
        last = now
        val m = Matrix()
        for (f in faces(width, height)) {
            if (!m.setPolyToPoly(f.src, 0, f.pts, 0, 4)) continue
            c.save()
            c.concat(m)
            c.drawBitmap(b, f.rect, RectF(f.rect), paint)
            c.restore()
        }
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { yaw += (e.x - lastX) * 0.6f; lastX = e.x
                pitch = (pitch + (e.y - lastY) * 0.5f).coerceIn(-70f, 70f); lastY = e.y; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; last = System.nanoTime() }
        }
        return true
    }
}
