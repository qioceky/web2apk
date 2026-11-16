package id.web2apk.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class WorkflowRun(val id: Long, val status: String, val conclusion: String?, val createdAtMillis: Long)
data class ReleaseAsset(val releaseId: Long, val tag: String, val assetId: Long, val name: String, val size: Long)

/**
 * Klien GitHub REST API secukupnya untuk build langsung:
 * dispatch workflow builder → pantau run → ambil APK dari Release.
 */
class GitHubClient(private val token: String) {

    private fun connect(path: String, method: String = "GET"): HttpURLConnection =
        (URL("https://api.github.com$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Web2APK-Android")
        }

    private fun bodyText(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /** Tes token: kembalikan login user, lempar bila token tidak valid. */
    fun whoAmI(): String {
        val conn = connect("/user")
        val text = bodyText(conn)
        if (conn.responseCode != 200)
            throw IllegalStateException("Token ditolak GitHub (HTTP ${conn.responseCode})")
        return JSONObject(text).optString("login")
    }

    fun dispatchBuild(repo: String, cfg: ProjectConfig, iconUrl: String) {
        val inputs = JSONObject().apply {
            put("site_url", ProjectConfig.normalizeUrl(cfg.siteUrl))
            put("app_name", cfg.appName)
            put("package_id", cfg.packageId)
            put("version_name", cfg.versionName)
            put("theme_color", cfg.themeColor)
            put("icon_url", iconUrl)
            put("opt_javascript", cfg.javaScript.toString())
            put("opt_zoom", cfg.zoom.toString())
            put("opt_file_upload", cfg.fileUpload.toString())
            put("opt_external_links", cfg.externalLinksInBrowser.toString())
            put("opt_splash", cfg.splashScreen.toString())
            put("opt_hide_toolbar", cfg.hideToolbar.toString())
            put("opt_pull_refresh", cfg.pullToRefresh.toString())
            put("opt_cookies", cfg.allowCookies.toString())
            put("app_slug", ProjectConfig.slugFromName(cfg.appName))
        }
        val payload = JSONObject().apply {
            put("ref", "main")
            put("inputs", inputs)
        }
        val conn = connect("/repos/$repo/actions/workflows/build-apk.yml/dispatches", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val text = bodyText(conn)
        if (conn.responseCode != 204) {
            val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { text }
            throw IllegalStateException("Dispatch gagal (HTTP ${conn.responseCode}): $msg")
        }
    }

    fun latestRuns(repo: String): List<WorkflowRun> {
        val conn = connect("/repos/$repo/actions/workflows/build-apk.yml/runs?per_page=5")
        val text = bodyText(conn)
        if (conn.responseCode != 200)
            throw IllegalStateException("Gagal membaca daftar run (HTTP ${conn.responseCode})")
        val arr = JSONObject(text).optJSONArray("workflow_runs") ?: JSONArray()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WorkflowRun(
                id = o.optLong("id"),
                status = o.optString("status"),
                conclusion = o.optString("conclusion").ifEmpty { null },
                createdAtMillis = try { fmt.parse(o.optString("created_at"))?.time ?: 0L } catch (e: Exception) { 0L },
            )
        }
    }

    /** Nama langkah yang sedang berjalan / terakhir selesai pada sebuah run. */
    fun currentStep(repo: String, runId: Long): String {
        val conn = connect("/repos/$repo/actions/runs/$runId/jobs?per_page=5")
        val text = bodyText(conn)
        if (conn.responseCode != 200) return ""
        val jobs = JSONObject(text).optJSONArray("jobs") ?: return ""
        if (jobs.length() == 0) return "Menunggu runner…"
        val steps = jobs.getJSONObject(0).optJSONArray("steps") ?: return ""
        var lastDone = ""
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            when (s.optString("status")) {
                "in_progress" -> return "Sedang: ${s.optString("name")}"
                "completed" -> lastDone = s.optString("name")
            }
        }
        return if (lastDone.isNotEmpty()) "Selesai: $lastDone" else "Menyiapkan…"
    }

    /** Cari Release hasil build untuk slug+versi tertentu (tag: slug-vVersi-NomorRun). */
    fun findRelease(repo: String, slug: String, versionName: String): ReleaseAsset? {
        val conn = connect("/repos/$repo/releases?per_page=30")
        val text = bodyText(conn)
        if (conn.responseCode != 200)
            throw IllegalStateException("Gagal membaca releases (HTTP ${conn.responseCode})")
        val arr = JSONArray(text)
        val prefix = "$slug-v$versionName-"
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            val tag = rel.optString("tag_name")
            if (!tag.startsWith(prefix)) continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk")) {
                    return ReleaseAsset(rel.optLong("id"), tag, a.optLong("id"),
                        a.optString("name"), a.optLong("size"))
                }
            }
        }
        return null
    }

    fun downloadAsset(repo: String, asset: ReleaseAsset, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = connect("/repos/$repo/releases/assets/${asset.assetId}")
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.instanceFollowRedirects = true
        if (conn.responseCode != 200)
            throw IllegalStateException("Unduh APK gagal (HTTP ${conn.responseCode})")
        val total = if (asset.size > 0) asset.size else conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
    }
}
