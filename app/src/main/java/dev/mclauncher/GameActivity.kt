package dev.mclauncher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Мост к нативному коду. Загружается только в процессе игры. */
object Native {
    init {
        System.loadLibrary("simplemc")
    }

    @JvmStatic external fun eglClear(surface: Surface): String
    @JvmStatic external fun startJvm(javaHome: String, options: Array<String>): String
    @JvmStatic external fun setSurface(surface: Surface, w: Int, h: Int)
    @JvmStatic external fun pushEvent(type: Int, a: Double, b: Double, c: Int)
    @JvmStatic external fun setGlLib(path: String)
    @JvmStatic external fun redirectOutput(path: String)
    @JvmStatic external fun chdir(path: String)
    @JvmStatic external fun surfaceLost()
    @JvmStatic external fun cursorMode(): Int
    @JvmStatic external fun runMain(javaHome: String, options: Array<String>, mainClass: String, args: Array<String>): String
}

/**
 * Экран игры (отдельный процесс :game).
 * mode=test: проверка EGL и запуска Java.
 * mode=game: запуск Minecraft внутри процесса.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var logView: TextView
    private var started = false
    private var mode = "game"
    private val handler = Handler(Looper.getMainLooper())
    private var gameLog: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "game"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this)
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        root.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val controls = ControlsView(this)
        run {
            val sc = renderScale()
            controls.gameScale = sc / 100f
        }
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setPadding(12, 8, 12, 8)
            maxLines = 45
        }
        root.addView(
            logView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        logView.visibility = View.GONE
        val copy = Button(this).apply {
            text = "Копировать лог"
            textSize = 11f
            visibility = View.GONE
            setOnClickListener { copyLog() }
        }
        val toggle = Button(this).apply {
            text = "Показать лог"
            textSize = 11f
            setOnClickListener {
                val show = logView.visibility != View.VISIBLE
                logView.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Скрыть лог" else "Показать лог"
                copy.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        val editBtns = ArrayList<Button>()
        fun eb(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; visibility = View.GONE
            setOnClickListener { f() }
            editBtns.add(this)
        }
        val layoutBtn = Button(this).apply {
            text = "Раскладка"
            textSize = 11f
            setOnClickListener {
                val on = !controls.editing
                controls.editing = on
                text = if (on) "Готово" else "Раскладка"
                editBtns.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(layoutBtn)
            addView(toggle)
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(row)
            addView(copy)
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        )
        val editBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(eb("+ Кнопка") { controls.newButton() })
            addView(eb("Камера") { controls.cameraDialog() })
            addView(eb("Сброс") { controls.reset() })
        }
        root.addView(
            editBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = (56 * resources.displayMetrics.density).toInt() }
        )
        setContentView(root)
    }

    private fun copyLog() {
        val sb = StringBuilder()
        try {
            val f = gameLog
            if (f != null && f.exists()) {
                val t = f.readText()
                sb.append(if (t.length > 30000) t.takeLast(30000) else t)
            }
        } catch (_: Exception) {
        }
        sb.append("\n--- экран ---\n").append(logView.text)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mobilix log", sb.toString()))
        say("Лог скопирован в буфер обмена.")
    }

    private fun say(s: String) = runOnUiThread { logView.append(s + "\n") }

    // ---------- поверхность ----------
    private fun renderScale(): Int = try {
        JSONObject(File(filesDir, "game_settings.json").readText()).optInt("renderScale", 100).coerceIn(30, 100)
    } catch (_: Throwable) { 100 }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val sc = renderScale()
        if (sc < 100) {
            val dm = resources.displayMetrics
            val w = maxOf(dm.widthPixels, dm.heightPixels) * sc / 100
            val h = minOf(dm.widthPixels, dm.heightPixels) * sc / 100
            holder.setFixedSize(w, h)
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (mode == "game" && started) {
            try {
                Native.surfaceLost()
            } catch (_: Throwable) {
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (mode == "game") {
            try {
                Native.setSurface(holder.surface, width, height)
            } catch (e: Throwable) {
                say("ОШИБКА setSurface: $e")
            }
        }
        if (started) return
        started = true
        say("Экран: ${width}x$height")
        val surface = holder.surface
        if (mode == "test") {
            Thread {
                try {
                    say(Native.eglClear(surface))
                    startJvmTest()
                } catch (e: Throwable) {
                    say("ОШИБКА: $e")
                }
            }.start()
        } else {
            Thread(null, { runGame() }, "game-main", 32L * 1024 * 1024).start()
        }
    }

    private fun findJavaHome(): File? {
        val rt = File(filesDir, "runtime")
        val java = rt.walkTopDown().maxDepth(4)
            .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
        return java?.parentFile?.parentFile
    }

    private fun startJvmTest() {
        val home = findJavaHome()
        if (home == null) {
            say("Java-рантайм не установлен. Сначала нажмите «Играть» в главном окне.")
            return
        }
        val opts = arrayOf("-Xmx512M", "-XX:+UseSerialGC", "-Xrs", "-Djava.io.tmpdir=${cacheDir.path}", "-Djava.awt.headless=true")
        Thread(null, {
            try {
                say(Native.startJvm(home.path, opts))
            } catch (e: Throwable) {
                say("ОШИБКА JVM: $e")
            }
        }, "jvm-start", 16L * 1024 * 1024).start()
    }

    // ---------- игра ----------
    private fun runGame() {
        try {
            val dataDir = getExternalFilesDir(null) ?: filesDir
            val launchFile = File(dataDir, "launch.json")
            if (!launchFile.exists()) {
                say("Нет launch.json. Сначала нажмите «Играть» в главном окне.")
                return
            }
            val j = JSONObject(launchFile.readText())
            val jvm = j.getJSONArray("jvm")
            val opts = ArrayList<String>()
            var i = 0
            while (i < jvm.length()) {
                val a = jvm.getString(i)
                if ((a == "-cp" || a == "-classpath") && i + 1 < jvm.length()) {
                    opts.add("-Djava.class.path=" + jvm.getString(i + 1))
                    i += 2
                    continue
                }
                val eq = setOf("--add-modules", "--add-opens", "--add-exports", "--add-reads", "--limit-modules", "--patch-module", "--upgrade-module-path", "--module-path")
                if ((a == "-p" || a in eq) && i + 1 < jvm.length()) {
                    opts.add((if (a == "-p") "--module-path" else a) + "=" + jvm.getString(i + 1))
                    i += 2
                    continue
                }
                opts.add(a)
                i++
            }
            if (!opts.contains("-Xrs")) opts.add("-Xrs")
            val game = j.getJSONArray("game")
            val args = Array(game.length()) { game.getString(it) }
            for (k in 0 until game.length() - 1) {
                if (game.getString(k) == "--gameDir") {
                    val gd = File(game.getString(k + 1)).apply { mkdirs() }
                    opts.add("-Duser.dir=" + gd.path)
                    File(gd, "logs").mkdirs()
                    Native.chdir(gd.path)
                }
            }

            val log = File(dataDir, "game.log")
            gameLog = log
            Native.redirectOutput(log.path)
            runOnUiThread { handler.post(tailer) }

            val gl = j.optString("glLib", "")
            if (gl.isNotEmpty()) Native.setGlLib(gl)
            say("Запуск Minecraft в процессе...")
            val r = Native.runMain(j.getString("javaHome"), opts.toTypedArray(), j.getString("main"), args)
            say(r)
        } catch (e: Throwable) {
            say("ОШИБКА игры: $e")
        }
    }

    /** Показывает последние строки лога игры на экране. */
    private val tailer = object : Runnable {
        override fun run() {
            try {
                val f = gameLog
                if (f != null && f.exists()) {
                    RandomAccessFile(f, "r").use { raf ->
                        val len = raf.length()
                        val start = maxOf(0L, len - 7000)
                        raf.seek(start)
                        val buf = ByteArray((len - start).toInt())
                        raf.readFully(buf)
                        val lines = String(buf).lines().takeLast(45)
                        logView.text = lines.joinToString("\n")
                    }
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 700)
        }
    }

    // ---------- ввод ----------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode != "game") {
            super.onBackPressed()
            return
        }
        try {
            Native.pushEvent(1, 256.0, 0.0, 1) // Esc нажата
            Native.pushEvent(1, 256.0, 0.0, 0) // Esc отпущена
        } catch (_: Throwable) {
        }
    }
}
