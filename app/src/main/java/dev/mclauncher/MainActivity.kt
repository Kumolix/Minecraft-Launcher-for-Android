package dev.mclauncher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Этап 1: минимальный офлайн-лаунчер.
 * Ник Player, версия 1.21.3. Кнопка "Играть" скачивает файлы игры
 * и собирает команду запуска. Java-рантайма пока нет — он будет следующим этапом.
 */
class MainActivity : Activity() {

    companion object {
        const val NICK = "Player"
        const val VERSION = "1.21.3"
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val ASSETS_URL = "https://resources.download.minecraft.net/"
    }

    private class Task(val url: String, val dest: File, val sha1: String?)

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var playBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Mini Launcher"
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
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun start() {
        playBtn.isEnabled = false
        Thread {
            try {
                prepareAndRun()
            } catch (e: Throwable) {
                log("ОШИБКА: $e")
            } finally {
                runOnUiThread { playBtn.isEnabled = true }
            }
        }.start()
    }

    // ---------- основной сценарий ----------

    private fun prepareAndRun() {
        val root = getExternalFilesDir(null) ?: filesDir
        val gameDir = File(root, "game").apply { mkdirs() }
        val assetsDir = File(root, "assets")
        val librariesDir = File(root, "libraries")
        val natives = File(root, "natives/$VERSION").apply { mkdirs() }
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

        // 5. Команда запуска
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
            "launcher_name" to "MiniLauncher",
            "launcher_version" to "0.1",
            "classpath" to classpath.joinToString(":")
        )
        val sub = { s: String ->
            Regex("\\$\\{(\\w+)\\}").replace(s) { m -> repl[m.groupValues[1]] ?: m.value }
        }
        val args = v.getJSONObject("arguments")
        val jvmArgs = collectArgs(args.optJSONArray("jvm"), sub)
        val gameArgs = collectArgs(args.optJSONArray("game"), sub)
        val cmd = ArrayList<String>()
        cmd.add(File(root, "runtime/bin/java").path)
        cmd.add("-Xmx1536M")
        cmd.addAll(jvmArgs)
        cmd.add(v.getString("mainClass"))
        cmd.addAll(gameArgs)
        File(root, "launch_command.txt").writeText(cmd.joinToString("\n"))
        log("Команда запуска собрана (${cmd.size} аргументов).")

        // 6. Запуск Java, если рантайм уже установлен
        val java = File(root, "runtime/bin/java")
        if (!java.exists()) {
            log("Файлы игры готовы. Java-рантайма пока нет — это следующий этап (нужна Java $javaMajor).")
            return
        }
        log("Запуск игры...")
        val p = ProcessBuilder(cmd).directory(gameDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine { log(it) }
        log("Процесс завершён, код ${p.waitFor()}")
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
