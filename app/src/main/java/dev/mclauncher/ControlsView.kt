package dev.mclauncher

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val K_KEY = 0
const val K_MOUSE = 1
const val K_SCROLL = 2

class Btn(
    var label: String, var kind: Int, var code: Int,
    var cx: Float, var cy: Float, var w: Int, var h: Int, var toggle: Boolean = false
) {
    var pressed = false
    var on = false
    fun toJson() = JSONObject().put("l", label).put("k", kind).put("c", code).put("x", cx.toDouble())
        .put("y", cy.toDouble()).put("w", w).put("h", h).put("t", toggle)
}

/** Имена клавиш для назначения: (название, тип, код GLFW). */
private val KEY_CHOICES: List<Triple<String, Int, Int>> = buildList {
    add(Triple("Мышь: левая (ломать/бить)", K_MOUSE, 0))
    add(Triple("Мышь: правая (ставить/использовать)", K_MOUSE, 1))
    add(Triple("Мышь: средняя", K_MOUSE, 2))
    add(Triple("Колесо вверх", K_SCROLL, 1))
    add(Triple("Колесо вниз", K_SCROLL, -1))
    add(Triple("Пробел", K_KEY, 32)); add(Triple("Esc", K_KEY, 256)); add(Triple("Enter", K_KEY, 257))
    add(Triple("Tab", K_KEY, 258)); add(Triple("Backspace", K_KEY, 259))
    add(Triple("Стрелка вверх", K_KEY, 265)); add(Triple("Стрелка вниз", K_KEY, 264))
    add(Triple("Стрелка влево", K_KEY, 263)); add(Triple("Стрелка вправо", K_KEY, 262))
    add(Triple("Левый Shift", K_KEY, 340)); add(Triple("Левый Ctrl", K_KEY, 341)); add(Triple("Левый Alt", K_KEY, 342))
    for (c in 'A'..'Z') add(Triple(c.toString(), K_KEY, c.code))
    for (c in '0'..'9') add(Triple(c.toString(), K_KEY, c.code))
    for (i in 1..12) add(Triple("F$i", K_KEY, 289 + i))
    add(Triple("/", K_KEY, 47)); add(Triple(",", K_KEY, 44)); add(Triple(".", K_KEY, 46))
    add(Triple("-", K_KEY, 45)); add(Triple("=", K_KEY, 61)); add(Triple("`", K_KEY, 96))
    add(Triple("Page Up", K_KEY, 266)); add(Triple("Page Down", K_KEY, 267))
}

fun keyName(kind: Int, code: Int): String =
    KEY_CHOICES.firstOrNull { it.second == kind && it.third == code }?.first ?: "?"

/** Слой управления поверх игры: кнопки, камера, режим редактирования раскладки. */
class ControlsView(ctx: Context) : View(ctx) {
    private val file = File(ctx.filesDir, "controls.json")
    private val d = ctx.resources.displayMetrics.density
    val buttons = ArrayList<Btn>()
    var sens = 1.0f
    var editing = false
        set(v) { field = v; if (!v) save(); invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    private sealed class Owner {
        class B(val b: Btn, var sx: Float, var sy: Float, var moved: Boolean = false) : Owner()
        class Cam(var lx: Float, var ly: Float) : Owner()
        object Gui : Owner()
    }
    private val owners = HashMap<Int, Owner>()
    private var vx = 0.0
    private var vy = 0.0

    init { load() }

    // ---------- раскладка ----------
    private fun defaults() {
        buttons.clear()
        fun k(l: String, c: Int, x: Float, y: Float, w: Int, h: Int = w, t: Boolean = false) =
            buttons.add(Btn(l, K_KEY, c, x, y, w, h, t))
        // крестовина слева
        k("▲", 'W'.code, 0.13f, 0.60f, 56); k("▼", 'S'.code, 0.13f, 0.88f, 56)
        k("◀", 'A'.code, 0.065f, 0.74f, 56); k("▶", 'D'.code, 0.195f, 0.74f, 56)
        // действия справа
        buttons.add(Btn("Бить", K_MOUSE, 0, 0.89f, 0.60f, 76, 76))
        buttons.add(Btn("Ставить", K_MOUSE, 1, 0.77f, 0.76f, 76, 76))
        k("Прыжок", 32, 0.91f, 0.84f, 64)
        k("Присед", 340, 0.27f, 0.88f, 56, 56, true)
        k("Бег", 341, 0.27f, 0.70f, 56)
        // верхний ряд
        k("F5", 294, 0.26f, 0.07f, 40); k("Чат", 'T'.code, 0.33f, 0.07f, 40); k("Esc", 256, 0.40f, 0.07f, 40)
        k("Инв.", 'E'.code, 0.47f, 0.07f, 40); k("Бросить", 'Q'.code, 0.54f, 0.07f, 40)
        // хотбар
        for (i in 1..9) k(i.toString(), 48 + i, 0.335f + (i - 1) * 0.041f, 0.94f, 34)
        sens = 1.0f
    }

    fun save() {
        try {
            val a = JSONArray()
            buttons.forEach { a.put(it.toJson()) }
            file.writeText(JSONObject().put("sens", sens.toDouble()).put("buttons", a).toString())
        } catch (_: Throwable) {
        }
    }

    private fun load() {
        try {
            if (file.exists()) {
                val j = JSONObject(file.readText())
                sens = j.optDouble("sens", 1.0).toFloat()
                val a = j.getJSONArray("buttons")
                buttons.clear()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    buttons.add(Btn(o.getString("l"), o.getInt("k"), o.getInt("c"), o.getDouble("x").toFloat(),
                        o.getDouble("y").toFloat(), o.getInt("w"), o.getInt("h"), o.optBoolean("t")))
                }
                if (buttons.isNotEmpty()) return
            }
        } catch (_: Throwable) {
        }
        defaults()
    }

    fun reset() { defaults(); save(); invalidate() }

    // ---------- отрисовка ----------
    private fun rect(b: Btn): RectF {
        val cx = b.cx * width; val cy = b.cy * height
        return RectF(cx - b.w * d / 2, cy - b.h * d / 2, cx + b.w * d / 2, cy + b.h * d / 2)
    }

    override fun onDraw(c: Canvas) {
        for (b in buttons) {
            val r = rect(b)
            fill.color = if (b.pressed || b.on) 0xAAFFFFFF.toInt() else if (editing) 0x66FFD54F else 0x44FFFFFF
            c.drawRoundRect(r, 14f * d, 14f * d, fill)
            line.color = if (editing) 0xFFFFC107.toInt() else 0x88FFFFFF.toInt()
            c.drawRoundRect(r, 14f * d, 14f * d, line)
            txt.color = if (b.pressed || b.on) Color.BLACK else Color.WHITE
            txt.textSize = (if (b.label.length > 3) 11f else 15f).coerceAtMost(b.h * 0.4f) * d
            c.drawText(b.label, r.centerX(), r.centerY() + txt.textSize * 0.35f, txt)
        }
        if (editing) {
            txt.color = Color.YELLOW; txt.textSize = 13f * d
            c.drawText("Режим раскладки: тяните кнопки, нажмите на кнопку для настройки", width / 2f, height * 0.17f, txt)
        }
    }

    // ---------- события ----------
    private fun cursorDisabled() = try { Native.cursorMode() == 0x34003 } catch (_: Throwable) { false }

    private fun hit(x: Float, y: Float): Btn? = buttons.lastOrNull { rect(it).contains(x, y) }

    private fun fire(b: Btn, down: Boolean) {
        try {
            when (b.kind) {
                K_KEY -> Native.pushEvent(1, b.code.toDouble(), 0.0, if (down) 1 else 0)
                K_MOUSE -> Native.pushEvent(4, b.code.toDouble(), 0.0, if (down) 1 else 0)
                K_SCROLL -> if (down) Native.pushEvent(5, 0.0, b.code.toDouble(), 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun press(b: Btn, down: Boolean) {
        if (b.toggle) {
            if (down) { b.on = !b.on; fire(b, b.on) }
        } else {
            b.pressed = down
            fire(b, down)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx)
                val b = hit(x, y)
                if (b != null) {
                    owners[id] = Owner.B(b, x, y)
                    if (!editing) press(b, true)
                } else if (editing) {
                    owners[id] = Owner.Gui
                } else if (cursorDisabled()) {
                    owners[id] = Owner.Cam(x, y)
                } else {
                    owners[id] = Owner.Gui
                    vx = x.toDouble(); vy = y.toDouble()
                    try { Native.pushEvent(3, vx, vy, 0); Native.pushEvent(4, 0.0, 0.0, 1) } catch (_: Throwable) {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val o = owners[e.getPointerId(i)] ?: continue
                    val x = e.getX(i); val y = e.getY(i)
                    when (o) {
                        is Owner.B -> if (editing) {
                            if (Math.abs(x - o.sx) > 8 * d || Math.abs(y - o.sy) > 8 * d) o.moved = true
                            if (o.moved) {
                                o.b.cx = (x / width).coerceIn(0.02f, 0.98f)
                                o.b.cy = (y / height).coerceIn(0.03f, 0.97f)
                            }
                        }
                        is Owner.Cam -> {
                            vx += (x - o.lx) * sens; vy += (y - o.ly) * sens
                            o.lx = x; o.ly = y
                            try { Native.pushEvent(3, vx, vy, 0) } catch (_: Throwable) {}
                        }
                        is Owner.Gui -> if (!editing) {
                            try { Native.pushEvent(3, x.toDouble(), y.toDouble(), 0) } catch (_: Throwable) {}
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val o = owners.remove(id)
                val x = e.getX(idx); val y = e.getY(idx)
                when (o) {
                    is Owner.B -> if (editing) { if (!o.moved) editButton(o.b) else save() } else if (!o.b.toggle) press(o.b, false)
                    is Owner.Gui -> if (!editing) {
                        try { Native.pushEvent(3, x.toDouble(), y.toDouble(), 0); Native.pushEvent(4, 0.0, 0.0, 0) } catch (_: Throwable) {}
                    }
                    else -> {}
                }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    buttons.forEach { if (it.pressed) { it.pressed = false; fire(it, false) } }
                }
            }
        }
        invalidate()
        return true
    }

    // ---------- редактор ----------
    private fun seek(ctx: Context, title: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(ctx).apply { text = "$title: $value" }
        val s = SeekBar(ctx).apply {
            this.max = max - min; progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    t.text = "$title: ${p + min}"; onChange(p + min); invalidate()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(t); box.addView(s)
        return box
    }

    fun newButton() {
        val b = Btn("?", K_KEY, 32, 0.5f, 0.5f, 56, 56)
        buttons.add(b)
        invalidate()
        editButton(b)
    }

    private fun pickKey(b: Btn, keyBtn: android.widget.Button) {
        val names = KEY_CHOICES.map { it.first }.toTypedArray()
        AlertDialog.Builder(context).setTitle("Назначить клавишу").setItems(names) { _, i ->
            val (n, k, c) = KEY_CHOICES[i]
            b.kind = k; b.code = c
            if (b.label == "?" || b.label.isEmpty()) b.label = if (n.length <= 3) n else n.take(5)
            keyBtn.text = "Клавиша: $n"
            invalidate(); save()
        }.show()
    }

    private fun editButton(b: Btn) {
        val ctx = context
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        val name = EditText(ctx).apply {
            setText(b.label); hint = "Название"; inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val keyBtn = android.widget.Button(ctx).apply {
            text = "Клавиша: ${keyName(b.kind, b.code)}"
            setOnClickListener { pickKey(b, this) }
        }
        val tg = CheckBox(ctx).apply { text = "Фиксировать (нажал — включено, ещё раз — выключено)"; isChecked = b.toggle }
        box.addView(name); box.addView(keyBtn)
        box.addView(seek(ctx, "Ширина", 24, 200, b.w) { b.w = it })
        box.addView(seek(ctx, "Высота", 24, 200, b.h) { b.h = it })
        box.addView(tg)
        AlertDialog.Builder(ctx).setTitle("Кнопка").setView(box)
            .setPositiveButton("Готово") { _, _ ->
                b.label = name.text.toString().ifEmpty { "?" }
                b.toggle = tg.isChecked
                if (!b.toggle) b.on = false
                save(); invalidate()
            }
            .setNeutralButton("Удалить") { _, _ -> buttons.remove(b); save(); invalidate() }
            .setNegativeButton("Отмена") { _, _ -> invalidate() }
            .show()
    }

    fun cameraDialog() {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        box.addView(seek(context, "Чувствительность камеры, %", 20, 400, (sens * 100).toInt()) { sens = it / 100f })
        AlertDialog.Builder(context).setTitle("Камера").setView(box)
            .setPositiveButton("Готово") { _, _ -> save() }.show()
    }
}
