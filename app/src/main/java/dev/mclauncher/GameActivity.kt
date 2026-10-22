package dev.mclauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/** Мост к нативному коду. Загружается только в процессе игры. */
object Native {
    init {
        System.loadLibrary("simplemc")
    }

    @JvmStatic external fun eglClear(surface: Surface): String
    @JvmStatic external fun startJvm(javaHome: String, options: Array<String>): String
}

/**
 * Экран игры (отдельный процесс :game). Этап 3.2: проверяем, что
 *  1) из процесса можно рисовать на экран через EGL,
 *  2) Java запускается внутри процесса приложения.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var logView: TextView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this)
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        root.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 16, 16, 16)
        }
        root.addView(
            logView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        setContentView(root)
    }

    private fun say(s: String) = runOnUiThread { logView.append(s + "\n") }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (started) return
        started = true
        say("Экран: ${width}x$height")
        val surface = holder.surface
        Thread {
            try {
                say(Native.eglClear(surface))
                startJvmTest()
            } catch (e: Throwable) {
                say("ОШИБКА: $e")
            }
        }.start()
    }

    private fun startJvmTest() {
        val rt = File(filesDir, "runtime")
        val java = rt.walkTopDown().maxDepth(4)
            .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
        if (java == null) {
            say("Java-рантайм не установлен. Сначала нажмите «Играть» в главном окне.")
            return
        }
        val home = java.parentFile!!.parentFile!!
        val opts = arrayOf(
            "-Xmx512M", "-XX:+UseSerialGC", "-Xrs",
            "-Djava.io.tmpdir=${cacheDir.path}",
            "-Djava.awt.headless=true"
        )
        // JVM нужно создавать в потоке с большим стеком
        val t = Thread(null, {
            try {
                say(Native.startJvm(home.path, opts))
            } catch (e: Throwable) {
                say("ОШИБКА JVM: $e")
            }
        }, "jvm-start", 16L * 1024 * 1024)
        t.start()
    }
}
