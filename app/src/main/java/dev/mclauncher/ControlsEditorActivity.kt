package dev.mclauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Редактор раскладки управления без запуска игры. */
class ControlsEditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0E1F14.toInt()) }
        val controls = ControlsView(this)
        controls.editing = true
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fun b(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; setOnClickListener { f() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(b("+ Кнопка") { controls.newButton() })
            addView(b("Камера") { controls.cameraDialog() })
            addView(b("Сброс") { controls.reset() })
            addView(b("Готово") { controls.editing = false; finish() })
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (56 * resources.displayMetrics.density).toInt() })
        setContentView(root)
    }
}
