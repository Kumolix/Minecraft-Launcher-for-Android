package dev.mclauncher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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

/**
 * SimpleMC Launcher, этап 2.
 * Ник Player (офлайн), версия 1.21.3.
 * Скачивает файлы игры, ставит Java-рантайм, проверяет `java -version` и пробует запустить игру.
 * Графики и ввода (GLFW/LWJGL под Android) пока нет, поэтому игра дойдёт до ошибки окна: это ожидаемо.
 */
class MainActivity : Activity() {

    companion object {
        const val NICK = "Player"
        const val VERSION = "1.21.3"
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val ASSETS_URL = "https://resources.download.minecraft.net/"

        // Готовый Java-рантайм для Android (arm64). Состоит из двух архивов: общая часть + бинарники.
        // Если архивы лежат в папке данных приложения под этими же именами, скачивание пропускается.
        val RUNTIME_ORGS = listOf("PojavLauncherTeam")
        val RUNTIME_BRANCHES = listOf("v3_openjdk", "main")
        const val RUNTIME_PATH = "app_pojav/src/main/assets/components/jre-21/"
        const val RUNTIME_UNIVERSAL = "universal.tar.xz"
        const val RUNTIME_BIN = "bin-arm64.tar.xz"
    }

    private class Task(val url: String, val dest: File, val sha1: String?)

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var playBtn: Button
    private lateinit var wipeBtn: Button
    private var logFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "SimpleMC Launcher"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Ник: $NICK (офлайн)"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "Версия: $VERSION"
            isEnabled = false
        })
        playBtn = Button(this).apply {
            text = "Играть"
            setOnClickListener { start() }
        }
        root.addView(playBtn)
        wipeBtn = Button(this).apply {
            text = "Удалить всё скачанное"
            setOnClickListener { confirmWipe() }
        }
        root.addView(wipeBtn)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        scroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
                    File(root, RUNTIME_UNIVERSAL), File(root, RUNTIME_BIN),
                    runtimeDir(), cacheDir
                )
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
        val natives = File(root, "natives/$VERSION").apply { mkdirs() }
        logFile = File(root, "launcher.log").also { it.writeText("") }
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

        // 3. Библиотеки
        val libTasks = ArrayList<Task>()
        val classpath = ArrayList<String>()
        val libs = v.getJSONArray("libraries")
        for (i in 0 until libs.length()) {
            val lib = libs.getJSONObject(i)
            if (!allowed(lib)) continue
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
        downloadAll("Ресурсы", assetTasks)

        // 5. Java-рантайм
        val java = ensureRuntime(root, javaMajor)
        val rt = runtimeDir()

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
        cmd.add("-Djava.io.tmpdir=${cacheDir.path}")
        cmd.add("-Duser.home=${gameDir.path}")
        cmd.add("-Djava.awt.headless=true")
        cmd.addAll(jvmArgs)
        cmd.add(v.getString("mainClass"))
        cmd.addAll(gameArgs)
        File(root, "launch_command.txt").writeText(cmd.joinToString("\n"))
        log("Команда запуска собрана (${cmd.size} аргументов).")

        // 7. Проверка Java
        log("Проверка: java -version")
        val code = runProcess(listOf(java.path, "-version"), rt, gameDir)
        if (code != 0) {
            log("Java не запустилась (код $code). Пришлите этот лог.")
            return
        }
        log("Java работает.")

        // 8. Запуск игры
        log("Запуск игры...")
        val gameCode = runProcess(cmd, rt, gameDir)
        log("Процесс игры завершён, код $gameCode")
        if (gameCode != 0) {
            log("Это ожидаемо на этапе 2: графики и ввода под Android ещё нет.")
        }
    }

    // ---------- Java-рантайм ----------

    private fun ensureRuntime(root: File, javaMajor: Int): File {
        val rt = runtimeDir()
        var java = File(rt, "bin/java")
        if (java.exists()) {
            log("Java-рантайм уже установлен.")
            return java
        }
        log("Установка Java-рантайма...")
        rt.deleteRecursively()
        rt.mkdirs()
        for (name in listOf(RUNTIME_UNIVERSAL, RUNTIME_BIN)) {
            val archive = File(root, name)
            if (!archive.exists() || archive.length() == 0L) {
                downloadRuntimePart(name, archive)
            } else {
                log("Найден локальный архив $name")
            }
            log("Распаковка $name (${archive.length() / 1024 / 1024} МБ)...")
            extractTarXz(archive, rt)
        }
        // права на запуск для всего в bin/
        File(rt, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        java = File(rt, "bin/java")
        if (!java.exists()) {
            log("В рантайме нет bin/java. Содержимое:")
            rt.walkTopDown().maxDepth(2).forEach { log("  " + it.relativeTo(rt).path) }
            throw RuntimeException("Рантайм имеет неожиданную структуру")
        }
        File(rt, "release").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("JAVA_VERSION") }?.let { log("Рантайм: $it (нужна $javaMajor)") }
        return java
    }

    private fun downloadRuntimePart(name: String, dest: File) {
        var lastError = ""
        for (org in RUNTIME_ORGS) for (branch in RUNTIME_BRANCHES) {
            val url = "https://github.com/$org/PojavLauncher/raw/$branch/$RUNTIME_PATH$name"
            log("Скачивание: $url")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 60000
                val code = c.responseCode
                if (code != 200) {
                    lastError = "HTTP $code"
                    log("  $lastError")
                    continue
                }
                val total = c.contentLengthLong
                var read = 0L
                dest.parentFile?.mkdirs()
                c.inputStream.use { i ->
                    dest.outputStream().use { o ->
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
                log("  готово, ${dest.length() / 1024 / 1024} МБ")
                return
            } catch (e: Exception) {
                lastError = e.toString()
                log("  ошибка: $lastError")
                dest.delete()
            }
        }
        throw RuntimeException(
            "Не удалось скачать $name ($lastError). Можно положить файл вручную в ${dest.parent} под именем $name"
        )
    }

    private fun extractTarXz(archive: File, dest: File) {
        val destPath = dest.canonicalPath
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tin ->
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

    private fun runProcess(cmd: List<String>, rt: File, workDir: File): Int {
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        val env = pb.environment()
        env["JAVA_HOME"] = rt.path
        env["HOME"] = workDir.path
        env["TMPDIR"] = cacheDir.path
        env["LD_LIBRARY_PATH"] = listOf("lib", "lib/server", "lib/jli").joinToString(":") { File(rt, it).path }
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
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        return c.inputStream.bufferedReader().use { it.readText() }
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

    private fun download(url: String, dest: File, sha1: String?): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        dest.parentFile?.mkdirs()
        repeat(3) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                if (sha1 == null || sha1Of(dest) == sha1) return true
                dest.delete()
            } catch (e: Exception) {
                dest.delete()
            }
        }
        return false
    }

    private fun downloadAll(label: String, tasks: List<Task>) {
        if (tasks.isEmpty()) return
        log("$label: ${tasks.size} файлов")
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val futures = tasks.map { t ->
            pool.submit(Runnable {
                if (!download(t.url, t.dest, t.sha1)) failed.incrementAndGet()
                val d = done.incrementAndGet()
                if (d % 25 == 0 || d == tasks.size) {
                    runOnUiThread { progress.progress = d * 100 / tasks.size }
                }
            })
        }
        futures.forEach { it.get() }
        pool.shutdown()
        runOnUiThread { progress.progress = 0 }
        if (failed.get() > 0) throw RuntimeException("$label: не скачано ${failed.get()} файлов")
    }
}
