package dev.mclauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.view.Gravity
import android.graphics.Color
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * SimpleMC Launcher, этап 2.
 * Ник Player (офлайн), версия 1.21.3.
 * Скачивает файлы игры, ставит Java-рантайм, проверяет `java -version` и пробует запустить игру.
 * Графики и ввода (GLFW/LWJGL под Android) пока нет, поэтому игра дойдёт до ошибки окна: это ожидаемо.
 */
class MainActivity : Activity() {

    companion object {
        const val INPROCESS = true
        const val NICK = "Player"
        const val VERSION = "1.21.3"
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val ASSETS_URL = "https://resources.download.minecraft.net/"

        // Готовые сборки OpenJDK 21 для Android arm64 (открытый код, GPLv2 + Classpath Exception).
        // Лаунчер ищет в релизах первый подходящий архив; если не вышло, пробует следующий источник.
        // Свой архив можно положить в папку данных как runtime.tar.xz или runtime.tar.gz.
        // Нативные библиотеки LWJGL под Android arm64 (подбираются по версии LWJGL из описания игры).
        val LWJGL_REPOS = listOf(
            "Unxkki/nlauncher-android-natives",
            "MDTCopper/android-lwjgl3",
            "yuyi2439/AndroidLWJGL"
        )
        val RUNTIME_REPOS = listOf(
            "metinjakupi/openjdk-android-arm64",
            "feeldev12/nerion-android-jdk-build",
            "AstralNext/aml-android-jre",
            "vhynlain/nlauncher-jre-android"
        )
    }

    private class Task(val url: String, val dest: File, val sha1: String?)

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var playBtn: Button
    private lateinit var wipeBtn: Button
    private var logFile: File? = null

    private lateinit var skinView: SkinView
    private lateinit var logPanel: LinearLayout
    private lateinit var versionText: TextView

    private fun bar(text: String, color: Int, sizeSp: Float, h: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = sizeSp; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setBackgroundColor(color); isClickable = true; minHeight = dp(h)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F0C.toInt())
            fitsSystemWindows = true
        }
        // верхняя белая полоса: меню слева, название по центру, лог справа
        val top = FrameLayout(this).apply { setBackgroundColor(0xFF121A15.toInt()) }
        top.addView(TextView(this).apply {
            text = "☰"; textSize = 28f; setTextColor(0xFF43E06A.toInt()); gravity = Gravity.CENTER
            setOnClickListener { Toast.makeText(this@MainActivity, "Меню появится в версии 1.4.1", Toast.LENGTH_SHORT).show() }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleBox.addView(TextView(this).apply { text = "SimpleMC"; textSize = 20f; setTextColor(0xFF43E06A.toInt()); gravity = Gravity.CENTER })
        titleBox.addView(TextView(this).apply { text = versionLabel(); textSize = 11f; setTextColor(0xFF7FA08A.toInt()); gravity = Gravity.CENTER })
        top.addView(titleBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.CENTER))
        top.addView(TextView(this).apply {
            text = "Лог"; textSize = 14f; setTextColor(0xFF43E06A.toInt()); gravity = Gravity.CENTER
            setOnClickListener { logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // область 3D-скина
        val skinBox = FrameLayout(this).apply { setBackgroundColor(0xFF0E1F14.toInt()) }
        skinView = SkinView(this)
        skinBox.addView(skinView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        skinBox.addView(TextView(this).apply {
            text = NICK; textSize = 16f; setTextColor(0xFFB8E6C4.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(skinBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // панель лога (скрыта по умолчанию)
        logPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(0xFF121A15.toInt()) }
        wipeBtn = Button(this).apply {
            text = "Удалить всё скачанное"
            setOnClickListener { confirmWipe() }
        }
        logPanel.addView(wipeBtn)
        scroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFB8E6C4.toInt())
            setTextIsSelectable(true)
            setPadding(dp(8), 0, dp(8), 0)
        }
        scroll.addView(logView)
        logPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        root.addView(logPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        // синяя полоса: выбранная версия + выпадающий вверх список
        val vrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        versionText = bar("Версия: $VERSION", 0xFF14301D.toInt(), 17f, 56) {}
        vrow.addView(versionText, LinearLayout.LayoutParams(0, dp(56), 1f))
        val arrow = bar("▲", 0xFF0F2416.toInt(), 18f, 56) {}
        arrow.setOnClickListener { showVersions(arrow) }
        versionText.setOnClickListener { showVersions(arrow) }
        vrow.addView(arrow, LinearLayout.LayoutParams(dp(64), dp(56)))
        root.addView(vrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // зелёная кнопка «Играть»
        playBtn = Button(this).apply {
            text = "Играть"; textSize = 22f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2EA84F.toInt()); isAllCaps = false
            setOnClickListener { start() }
        }
        root.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
        setContentView(root)
        Thread { loadSkin() }.start()
    }

    private fun showVersions(anchor: View) {
        val root = getExternalFilesDir(null) ?: filesDir
        val installed = ArrayList<String>()
        File(root, "versions").listFiles()?.forEach { if (File(it, it.name + ".jar").exists()) installed.add(it.name) }
        if (!installed.contains(VERSION)) installed.add(0, VERSION)
        val pm = android.widget.PopupMenu(this, anchor, Gravity.END)
        installed.forEachIndexed { i, v -> pm.menu.add(0, i, i, v + if (v == VERSION) "  ✓" else "") }
        pm.menu.add(1, 100, 100, "＋ Загрузить другую версию…")
        pm.setOnMenuItemClickListener {
            if (it.itemId == 100) Toast.makeText(this, "Скоро: выбор и загрузка других версий", Toast.LENGTH_SHORT).show()
            true
        }
        pm.show()
    }

    /** Достаёт стандартный скин (Стив) из клиентского jar и показывает его в 3D. */
    private fun loadSkin() {
        try {
            val root = getExternalFilesDir(null) ?: filesDir
            val cache = File(filesDir, "skin_default.png")
            if (!cache.exists()) {
                val jar = File(root, "versions/$VERSION/$VERSION.jar")
                if (!jar.exists()) return
                java.util.zip.ZipFile(jar).use { z ->
                    val e = listOf(
                        "assets/minecraft/textures/entity/player/wide/steve.png",
                        "assets/minecraft/textures/entity/steve.png"
                    ).firstNotNullOfOrNull { z.getEntry(it) } ?: return
                    z.getInputStream(e).use { i -> cache.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            val bmp = android.graphics.BitmapFactory.decodeFile(cache.path)
            runOnUiThread { skinView.setSkin(bmp) }
        } catch (_: Throwable) {
        }
    }

    private fun versionLabel(): String = try {
        "v-" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        ""
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(s: String) {
        try {
            logFile?.appendText(s + "\n")
        } catch (_: Exception) {
        }
        runOnUiThread {
            logView.append(s + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setBusy(b: Boolean) = runOnUiThread {
        try {
            if (b) DownloadService.start(this) else DownloadService.stop(this)
        } catch (_: Throwable) {
        }
        playBtn.isEnabled = !b
        wipeBtn.isEnabled = !b
    }

    private fun start() {
        setBusy(true)
        Thread {
            try {
                prepareAndRun()
            } catch (e: Throwable) {
                log("ОШИБКА: $e")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    // ---------- каталоги ----------

    /** Данные игры (скачанное). Внешняя папка приложения: оттуда запускать программы нельзя. */
    private fun dataRoot(): File = getExternalFilesDir(null) ?: filesDir

    /** Java-рантайм обязан лежать во внутренней памяти: из внешней Android не даст запустить файлы. */
    private fun runtimeDir(): File = File(filesDir, "runtime")

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("Удалить всё скачанное?")
            .setMessage("Будут удалены клиент игры, библиотеки, ресурсы и Java-рантайм. Папка game (миры и настройки) останется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> wipe() }
            .show()
    }

    private fun wipe() {
        setBusy(true)
        Thread {
            try {
                val root = dataRoot()
                var freed = 0L
                val targets = listOf(
                    File(root, "assets"), File(root, "libraries"), File(root, "versions"),
                    File(root, "natives"), File(root, "launch_command.txt"),
                    File(root, "runtime.tar.xz"), File(root, "runtime.tar.gz"),
                    runtimeDir(), cacheDir, File(filesDir, "natives"), File(filesDir, "lwjgl"), File(filesDir, "mg"), File(root, "mg-plugin.apk")
                ) + (root.listFiles { f -> f.name.startsWith("lwjgl-natives") }?.toList() ?: emptyList())
                for (t in targets) {
                    freed += sizeOf(t)
                    t.deleteRecursively()
                }
                cacheDir.mkdirs()
                log("Удалено: ${freed / 1024 / 1024} МБ. Папка game сохранена.")
            } catch (e: Throwable) {
                log("ОШИБКА удаления: $e")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------- основной сценарий ----------

    private fun prepareAndRun() {
        val root = dataRoot()
        val gameDir = File(root, "game").apply { mkdirs() }
        val assetsDir = File(root, "assets")
        val librariesDir = File(root, "libraries")
        val natives = File(filesDir, "natives/$VERSION").apply { mkdirs() }
        logFile = File(root, "launcher.log").also { it.writeText("") }
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}")
        log("Папка данных: ${root.path}")
        log("Версия $VERSION, ник $NICK")

        // 1. Манифест версий -> json версии
        val manifest = JSONObject(fetchText(MANIFEST_URL))
        val versions = manifest.getJSONArray("versions")
        var entry: JSONObject? = null
        for (i in 0 until versions.length()) {
            val o = versions.getJSONObject(i)
            if (o.getString("id") == VERSION) {
                entry = o
                break
            }
        }
        val found = entry ?: throw RuntimeException("Версия $VERSION не найдена")
        val versionJsonFile = File(root, "versions/$VERSION/$VERSION.json")
        if (!download(found.getString("url"), versionJsonFile, found.getString("sha1"))) {
            throw RuntimeException("Не удалось скачать описание версии")
        }
        val v = JSONObject(versionJsonFile.readText())
        val javaMajor = v.optJSONObject("javaVersion")?.optInt("majorVersion", 0) ?: 0
        log("Нужна Java: $javaMajor")

        // 2. Клиентский jar
        val clientInfo = v.getJSONObject("downloads").getJSONObject("client")
        val clientJar = File(root, "versions/$VERSION/$VERSION.jar")
        downloadAll(
            "Клиент игры",
            listOf(Task(clientInfo.getString("url"), clientJar, clientInfo.getString("sha1")))
        )
        Thread { loadSkin() }.start()

        // 3. Библиотеки
        var lwjglVer = ""
        val libTasks = ArrayList<Task>()
        val classpath = ArrayList<String>()
        val libs = v.getJSONArray("libraries")
        for (i in 0 until libs.length()) {
            val lib = libs.getJSONObject(i)
            if (!allowed(lib)) continue
            lib.optString("name").let { if (it.startsWith("org.lwjgl:lwjgl:")) lwjglVer = it.split(":")[2] }
            val art = lib.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            val dest = File(librariesDir, art.getString("path"))
            libTasks.add(Task(art.getString("url"), dest, art.optString("sha1", "").ifEmpty { null }))
            classpath.add(dest.path)
        }
        classpath.add(clientJar.path)
        downloadAll("Библиотеки", libTasks)

        // 4. Ресурсы (звуки, языки и т.д.)
        val idx = v.getJSONObject("assetIndex")
        val indexId = idx.getString("id")
        val indexFile = File(assetsDir, "indexes/$indexId.json")
        downloadAll("Индекс ресурсов", listOf(Task(idx.getString("url"), indexFile, idx.optString("sha1", "").ifEmpty { null })))
        val objects = JSONObject(indexFile.readText()).getJSONObject("objects")
        val assetTasks = ArrayList<Task>()
        val keys = objects.keys()
        while (keys.hasNext()) {
            val hash = objects.getJSONObject(keys.next()).getString("hash")
            val p = hash.substring(0, 2) + "/" + hash
            assetTasks.add(Task(ASSETS_URL + p, File(assetsDir, "objects/$p"), hash))
        }
        downloadAll("Ресурсы", assetTasks, fatal = false)

        // 5. Java-рантайм
        val java = ensureRuntime(root, javaMajor)
        val rt = java.parentFile!!.parentFile!!
        val lwjglPath = ensureLwjgl(lwjglVer)
        val mgPath = ensureMobileGlues()

        // 6. Команда запуска
        val repl = mapOf(
            "auth_player_name" to NICK,
            "version_name" to VERSION,
            "game_directory" to gameDir.path,
            "assets_root" to assetsDir.path,
            "assets_index_name" to indexId,
            "auth_uuid" to offlineUuid(NICK),
            "auth_access_token" to "0",
            "clientid" to "0",
            "auth_xuid" to "0",
            "user_type" to "legacy",
            "version_type" to "release",
            "natives_directory" to natives.path,
            "launcher_name" to "SimpleMC",
            "launcher_version" to "0.2",
            "classpath" to classpath.joinToString(":")
        )
        val sub = { s: String ->
            Regex("\\$\\{(\\w+)\\}").replace(s) { m -> repl[m.groupValues[1]] ?: m.value }
        }
        val args = v.getJSONObject("arguments")
        val jvmArgs = collectArgs(args.optJSONArray("jvm"), sub)
        val gameArgs = collectArgs(args.optJSONArray("game"), sub)
        val cmd = ArrayList<String>()
        cmd.add(java.path)
        cmd.add("-Xmx1536M")
        cmd.add("-XX:+UseSerialGC")
        cmd.add("-XX:ErrorFile=${File(gameDir, "hs_err.log").path}")
        cmd.add("-Djava.io.tmpdir=${cacheDir.path}")
        cmd.add("-Duser.home=${gameDir.path}")
        cmd.add("-Djava.awt.headless=true")
        val libPath = (if (lwjglPath != null) "$lwjglPath:" else "") + applicationInfo.nativeLibraryDir
        cmd.add("-Dorg.lwjgl.librarypath=$libPath")
        if (lwjglPath != null) {
            cmd.add("-Djna.boot.library.path=$lwjglPath")
            cmd.add("-Djna.nosys=true")
        }
        if (mgPath != null) cmd.add("-Dorg.lwjgl.opengl.libname=$mgPath")
        cmd.add("-Dorg.lwjgl.util.Debug=true")
        cmd.add("-Dorg.lwjgl.util.DebugLoader=true")
        cmd.addAll(jvmArgs)
        cmd.add(v.getString("mainClass"))
        cmd.addAll(gameArgs)
        File(root, "launch_command.txt").writeText(cmd.joinToString("\n"))
        val mainName = v.getString("mainClass")
        val mainIdx = cmd.indexOf(mainName)
        val launch = JSONObject()
        launch.put("javaHome", rt.path)
        launch.put("main", mainName)
        launch.put("jvm", JSONArray(cmd.subList(1, mainIdx)))
        launch.put("game", JSONArray(cmd.subList(mainIdx + 1, cmd.size)))
        launch.put("glLib", mgPath ?: "")
        File(root, "launch.json").writeText(launch.toString())
        log("Команда запуска собрана (${cmd.size} аргументов).")

        // 7. Проверка Java
        log("Проверка: java -version")
        val code = runProcess(listOf(java.path, "-version"), rt, gameDir)
        if (code != 0) {
            log("Java не запустилась (код $code). Пришлите этот лог.")
            return
        }
        log("Java работает.")

        if (INPROCESS) {
            log("Открываю окно игры...")
            runOnUiThread {
                startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "game"))
            }
            return
        }

        // 8. Запуск игры (отдельным процессом, без экрана)
        log("Запуск игры...")
        File(gameDir, "hs_err.log").delete()
        val gameCode = runProcess(cmd, rt, gameDir)
        log("Процесс игры завершён, код $gameCode")
        if (gameCode != 0) {
            val hs = File(gameDir, "hs_err.log")
            if (hs.exists()) {
                log("--- hs_err.log (начало) ---")
                hs.readLines().take(45).forEach { log(it) }
                log("--- конец ---")
            } else {
                log("hs_err.log не создан: процесс убит системой или оборван на низком уровне.")
            }
        }
    }

    // ---------- MobileGlues (OpenGL поверх OpenGL ES) ----------

    /** Берёт libmobileglues.so из плагина MobileGlues (LGPL-2.1). Возвращает путь к файлу или null. */
    private fun ensureMobileGlues(): String? {
        val dir = File(filesDir, "mg")
        fun find() = if (!dir.exists()) null else dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "libmobileglues.so" && it.path.contains("arm64") }
        find()?.let {
            log("MobileGlues уже установлен.")
            return it.path
        }
        log("Установка MobileGlues...")
        try {
            val json = JSONArray(fetchText("https://api.github.com/repos/MobileGL-Dev/MobileGlues-release/releases?per_page=10"))
            var best: JSONObject? = null
            search@ for (i in 0 until json.length()) {
                val assets = json.getJSONObject(i).optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val n = a.getString("name")
                    if (n.endsWith(".apk", true) && n.contains("mobileglues", true)) {
                        best = a
                        break@search
                    }
                }
            }
            if (best == null) {
                log("  APK MobileGlues не найден в релизах")
                return null
            }
            log("  скачивание ${best.getString("name")}")
            val apk = File(dataRoot(), "mg-plugin.apk")
            downloadBig(best.getString("browser_download_url"), apk)
            dir.deleteRecursively()
            dir.mkdirs()
            extractZip(apk, dir)
            val f = find()
            if (f == null) {
                log("  libmobileglues.so не найдена в APK. Содержимое:")
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { log("    " + it.relativeTo(dir).path) }
                return null
            }
            f.setReadable(true, false)
            log("  готово: ${f.name}")
            return f.path
        } catch (e: Exception) {
            log("  ошибка MobileGlues: $e")
            return null
        }
    }

    // ---------- LWJGL под Android ----------

    /** Возвращает значение для -Dorg.lwjgl.librarypath или null, если библиотеки получить не удалось. */
    private fun ensureLwjgl(version: String): String? {
        if (version.isEmpty()) {
            log("Версия LWJGL не определена, пропуск.")
            return null
        }
        val dir = File(filesDir, "lwjgl")
        fun hasMain() = dir.exists() && dir.walkTopDown().any { it.isFile && it.name == "liblwjgl.so" }
        if (!hasMain()) {
            log("Установка LWJGL $version для Android...")
            val archive = downloadLwjgl(version) ?: return null
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                if (archive.name.endsWith(".zip")) extractZip(archive, dir) else extractTar(archive, dir)
            } catch (e: Exception) {
                log("  ошибка распаковки: $e")
                return null
            }
        } else {
            log("LWJGL уже установлен.")
        }
        if (!hasMain()) {
            log("liblwjgl.so не найдена в архиве. Содержимое:")
            dir.walkTopDown().maxDepth(5).forEach { log("  " + it.relativeTo(dir).path) }
            return null
        }
        val libs = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
        libs.forEach { it.setReadable(true, false); it.setExecutable(true, false) }
        log("LWJGL: библиотек ${libs.size}: " + libs.joinToString(", ") { it.name })
        return libs.map { it.parentFile!!.path }.distinct().joinToString(":")
    }

    private fun downloadLwjgl(version: String): File? {
        val archiveRe = Regex("(?i).*\\.(zip|tar\\.xz|tar\\.gz)$")
        val skipRe = Regex("(?i)src|source|sha|sums")
        for (repo in LWJGL_REPOS) {
            try {
                log("Источник LWJGL: $repo")
                val json = JSONArray(fetchText("https://api.github.com/repos/$repo/releases?per_page=30"))
                var best: JSONObject? = null
                var bestScore = -1
                for (i in 0 until json.length()) {
                    val rel = json.getJSONObject(i)
                    val tag = rel.optString("tag_name")
                    val assets = rel.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        val name = a.getString("name")
                        if (!archiveRe.matches(name) || skipRe.containsMatchIn(name)) continue
                        if (!tag.contains(version) && !name.contains(version)) continue
                        var score = 1
                        if (name.contains("arm64", true) || name.contains("aarch64", true)) score += 2
                        if (name.contains("lwjgl", true)) score += 1
                        if (score > bestScore) {
                            bestScore = score
                            best = a
                        }
                    }
                }
                if (best == null) {
                    log("  нет файла для версии $version")
                    continue
                }
                val name = best.getString("name")
                val ext = when {
                    name.endsWith(".zip") -> ".zip"
                    name.endsWith(".tar.gz") -> ".tar.gz"
                    else -> ".tar.xz"
                }
                val dest = File(dataRoot(), "lwjgl-natives$ext")
                log("  скачивание $name (${best.optLong("size") / 1024} КБ)")
                downloadBig(best.getString("browser_download_url"), dest)
                return dest
            } catch (e: Exception) {
                log("  ошибка: $e")
            }
        }
        log("ПРЕДУПРЕЖДЕНИЕ: LWJGL для Android не получен. Игра остановится на загрузке liblwjgl.so.")
        return null
    }

    private fun extractZip(archive: File, dest: File) {
        val destPath = dest.canonicalPath
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                val p = out.canonicalPath
                if (p == destPath || p.startsWith(destPath + File.separator)) {
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> zin.copyTo(o) }
                    }
                }
                e = zin.nextEntry
            }
        }
    }

    // ---------- Java-рантайм ----------

    private fun findJava(rt: File): File? {
        if (!rt.exists()) return null
        return rt.walkTopDown().maxDepth(4)
            .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
    }

    private fun ensureRuntime(root: File, javaMajor: Int): File {
        val rt = runtimeDir()
        findJava(rt)?.let {
            log("Java-рантайм уже установлен.")
            return it
        }
        log("Установка Java-рантайма...")
        val local = listOf("runtime.tar.xz", "runtime.tar.gz").map { File(root, it) }
            .firstOrNull { it.exists() && it.length() > 0 }
        if (local != null) log("Найден локальный архив ${local.name}")
        val archive = local ?: downloadRuntime(root)
        rt.deleteRecursively()
        rt.mkdirs()
        log("Распаковка ${archive.name} (${archive.length() / 1024 / 1024} МБ)...")
        extractTar(archive, rt)
        val java = findJava(rt)
        if (java == null) {
            log("В рантайме нет bin/java. Содержимое:")
            rt.walkTopDown().maxDepth(3).forEach { log("  " + it.relativeTo(rt).path) }
            throw RuntimeException("Рантайм имеет неожиданную структуру")
        }
        java.parentFile?.listFiles()?.forEach { it.setExecutable(true, false) }
        val home = java.parentFile!!.parentFile!!
        File(home, "release").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("JAVA_VERSION") }?.let { log("Рантайм: $it (нужна $javaMajor)") }
        return java
    }

    private fun downloadRuntime(root: File): File {
        val pattern = Regex("(?i)^(?!.*src).*21.*(arm64|aarch64).*\\.tar\\.(xz|gz)$")
        var lastError = "нет источников"
        for (repo in RUNTIME_REPOS) {
            try {
                log("Источник Java: $repo")
                val json = JSONArray(fetchText("https://api.github.com/repos/$repo/releases?per_page=15"))
                var asset: JSONObject? = null
                search@ for (i in 0 until json.length()) {
                    val assets = json.getJSONObject(i).optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (pattern.matches(a.getString("name"))) {
                            asset = a
                            break@search
                        }
                    }
                }
                if (asset == null) {
                    lastError = "$repo: нет подходящего файла"
                    log("  нет подходящего файла")
                    continue
                }
                val name = asset.getString("name")
                val ext = if (name.endsWith(".gz")) "tar.gz" else "tar.xz"
                val dest = File(root, "runtime.$ext")
                log("  скачивание $name (${asset.optLong("size") / 1024 / 1024} МБ)")
                downloadBig(asset.getString("browser_download_url"), dest)
                return dest
            } catch (e: Exception) {
                lastError = "$repo: $e"
                log("  ошибка: $e")
            }
        }
        throw RuntimeException(
            "Не удалось скачать Java ($lastError). Можно положить архив вручную в ${root.path} под именем runtime.tar.xz"
        )
    }

    /** Большой файл с прогрессом; пишется во временный файл и переименовывается после полной загрузки. */
    private fun downloadBig(url: String, dest: File) {
        val tmp = File(dest.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "SimpleMC/0.3")
        val code = c.responseCode
        if (code != 200) throw RuntimeException("HTTP $code")
        val total = c.contentLengthLong
        var read = 0L
        dest.parentFile?.mkdirs()
        c.inputStream.use { i ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    read += n
                    if (total > 0) runOnUiThread { progress.progress = (read * 100 / total).toInt() }
                }
            }
        }
        runOnUiThread { progress.progress = 0 }
        if (total > 0 && tmp.length() != total) {
            tmp.delete()
            throw RuntimeException("файл скачан не полностью")
        }
        dest.delete()
        if (!tmp.renameTo(dest)) throw RuntimeException("не удалось сохранить файл")
        log("  готово, ${dest.length() / 1024 / 1024} МБ")
    }

    private fun extractTar(archive: File, dest: File) {
        val destPath = dest.canonicalPath
        val raw = BufferedInputStream(archive.inputStream())
        val stream = if (archive.name.endsWith(".gz")) GZIPInputStream(raw) else XZCompressorInputStream(raw)
        TarArchiveInputStream(stream).use { tin ->
            var e = tin.nextTarEntry
            while (e != null) {
                val out = File(dest, e.name)
                val p = out.canonicalPath
                if (p == destPath || p.startsWith(destPath + File.separator)) {
                    when {
                        e.isDirectory -> out.mkdirs()
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            Files.createSymbolicLink(out.toPath(), Paths.get(e.linkName))
                        }
                        e.isLink -> {
                            out.parentFile?.mkdirs()
                            try {
                                Files.copy(File(dest, e.linkName).toPath(), out.toPath())
                            } catch (_: Exception) {
                            }
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o -> tin.copyTo(o) }
                            if ((e.mode and 73) != 0) out.setExecutable(true, false)
                        }
                    }
                }
                e = tin.nextTarEntry
            }
        }
    }

    /** Системный журнал приложения: там бывает трасса падения нативного кода. */
    private fun logcatTail() {
        try {
            val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-t", "400").redirectErrorStream(true).start()
            val keep = Regex("DEBUG|libc|F/|backtrace|signal|#\\d+ pc|Abort|tombstone|SIGSEGV|SIGABRT|scudo|tag", RegexOption.IGNORE_CASE)
            val lines = p.inputStream.bufferedReader().readLines().filter { keep.containsMatchIn(it) }
            log("--- logcat (важные строки: ${lines.size}) ---")
            lines.takeLast(60).forEach { log(it.take(220)) }
            log("--- конец ---")
        } catch (e: Exception) {
            log("logcat недоступен: $e")
        }
    }

    private fun runProcess(cmd: List<String>, rt: File, workDir: File): Int {
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        val env = pb.environment()
        env["JAVA_HOME"] = rt.path
        env["HOME"] = workDir.path
        env["TMPDIR"] = cacheDir.path
        env["MEMTAG_OPTIONS"] = "off"
        val shim = File(applicationInfo.nativeLibraryDir, "libnotag.so")
        if (shim.exists()) {
            env["LD_PRELOAD"] = shim.path
        } else {
            log("ВНИМАНИЕ: libnotag.so не найдена в ${applicationInfo.nativeLibraryDir}")
        }
        env["LD_LIBRARY_PATH"] = listOf("lib", "lib/server", "lib/jli", "lib/aarch64", "lib/aarch64/server", "lib/aarch64/jli")
            .map { File(rt, it) }.filter { it.isDirectory }.joinToString(":") { it.path }
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine { log(it) }
        return p.waitFor()
    }

    // ---------- вспомогательное ----------

    /** Правила Mojang. Для Android считаем систему linux, фичи (demo и т.п.) отключены. */
    private fun allowed(o: JSONObject): Boolean {
        val rules = o.optJSONArray("rules") ?: return true
        var ok = false
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            if (r.has("features")) continue
            val os = r.optJSONObject("os")
            val match = os == null ||
                (os.optString("name").let { it.isEmpty() || it.startsWith("linux") } && os.optString("arch") != "x86")
            if (match) ok = r.getString("action") == "allow"
        }
        return ok
    }

    private fun collectArgs(arr: JSONArray?, sub: (String) -> String): List<String> {
        val out = ArrayList<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val a = arr.get(i)
            if (a is String) {
                out.add(sub(a))
            } else if (a is JSONObject && allowed(a)) {
                val value = a.get("value")
                if (value is String) {
                    out.add(sub(value))
                } else if (value is JSONArray) {
                    for (j in 0 until value.length()) out.add(sub(value.getString(j)))
                }
            }
        }
        return out
    }

    private fun offlineUuid(name: String): String {
        val b = MessageDigest.getInstance("MD5").digest("OfflinePlayer:$name".toByteArray())
        b[6] = ((b[6].toInt() and 0x0f) or 0x30).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long).toString()
    }

    private fun fetchText(url: String): String {
        var err: Exception? = null
        for (u in listOf(url) + mirrorsFor(url)) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "SimpleMC/0.3")
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                err = e
            }
        }
        throw err ?: RuntimeException("нет адреса")
    }

    private fun sha1Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { i ->
            val buf = ByteArray(65536)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var lastDownloadError = ""

    /** Зеркало BMCLAPI: подстановка для хостов Mojang. Файлы всё равно проверяются по sha1. */
    private fun mirrorsFor(url: String): List<String> {
        val m = "https://bmclapi2.bangbang93.com"
        return when {
            url.startsWith("https://resources.download.minecraft.net/") ->
                listOf(m + "/assets/" + url.removePrefix("https://resources.download.minecraft.net/"))
            url.startsWith("https://libraries.minecraft.net/") ->
                listOf(m + "/maven/" + url.removePrefix("https://libraries.minecraft.net/"))
            url.startsWith("https://piston-data.mojang.com/") ->
                listOf(m + "/" + url.removePrefix("https://piston-data.mojang.com/"))
            url.startsWith("https://piston-meta.mojang.com/") ->
                listOf(m + "/" + url.removePrefix("https://piston-meta.mojang.com/"))
            url.startsWith("https://launcher.mojang.com/") ->
                listOf(m + "/" + url.removePrefix("https://launcher.mojang.com/"))
            else -> emptyList()
        }
    }

    private fun download(url: String, dest: File, sha1: String?): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val urls = listOf(url) + mirrorsFor(url)
        for (attempt in 0 until 4) {
            val u = urls[attempt % urls.size]
            try {
                dest.parentFile?.mkdirs()
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 40000
                c.setRequestProperty("User-Agent", "SimpleMC/0.3")
                val code = c.responseCode
                if (code != 200) {
                    lastDownloadError = "HTTP $code ($u)"
                } else {
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    if (sha1 == null || sha1Of(tmp) == sha1) {
                        dest.delete()
                        if (tmp.renameTo(dest)) return true
                        lastDownloadError = "не удалось сохранить ($u)"
                    } else {
                        lastDownloadError = "sha1 не совпал ($u)"
                    }
                }
            } catch (e: Exception) {
                lastDownloadError = "$e ($u)"
            }
            tmp.delete()
            try {
                Thread.sleep(700L * (attempt + 1))
            } catch (_: InterruptedException) {
            }
        }
        return false
    }

    /** Качает файлы в несколько проходов. fatal=false: при неудаче только предупреждение. */
    private fun downloadAll(label: String, tasks: List<Task>, fatal: Boolean = true) {
        if (tasks.isEmpty()) return
        log("$label: ${tasks.size} файлов")
        var pending = tasks.distinctBy { it.dest.path }.filter { !(it.dest.exists() && it.dest.length() > 0) }
        if (pending.size < tasks.size) log("  уже скачано: ${tasks.size - pending.size}, осталось: ${pending.size}")
        for (pass in 1..3) {
            if (pending.isEmpty()) break
            if (pass > 1) {
                log("  повтор, осталось ${pending.size}. Последняя ошибка: $lastDownloadError")
                Thread.sleep(2500)
            }
            val done = AtomicInteger(0)
            val total = pending.size
            val failedList = java.util.Collections.synchronizedList(ArrayList<Task>())
            val pool = Executors.newFixedThreadPool(6)
            val futures = pending.map { t ->
                pool.submit(Runnable {
                    if (!download(t.url, t.dest, t.sha1)) failedList.add(t)
                    val d = done.incrementAndGet()
                    if (d % 25 == 0 || d == total) {
                        runOnUiThread { progress.progress = d * 100 / total }
                    }
                })
            }
            futures.forEach { it.get() }
            pool.shutdown()
            runOnUiThread { progress.progress = 0 }
            pending = ArrayList(failedList)
        }
        if (pending.isNotEmpty()) {
            val msg = "$label: не скачано ${pending.size} файлов. Причина: $lastDownloadError"
            if (fatal) throw RuntimeException(msg)
            log("ПРЕДУПРЕЖДЕНИЕ: $msg")
            log("Продолжаем без них (для проверки Java они не нужны).")
        }
    }
}
