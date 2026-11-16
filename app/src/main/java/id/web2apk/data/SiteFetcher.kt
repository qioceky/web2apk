package id.web2apk.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * Mengambil judul, deskripsi, warna tema, dan ikon sebuah situs dari HTML-nya.
 * Murni HttpURLConnection — tanpa dependensi tambahan.
 */
object SiteFetcher {

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Web2APK")
        }

    fun fetch(rawUrl: String): SiteInfo {
        val url = ProjectConfig.normalizeUrl(rawUrl)
        val conn = open(url)
        val code = conn.responseCode
        if (code !in 200..399) throw IllegalStateException("Situs membalas HTTP $code")
        val finalUrl = conn.url.toString()
        val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(300_000)
        conn.disconnect()

        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ") ?: ""
        fun meta(attr: String, key: String): String {
            val re = Regex("<meta[^>]+$attr=[\"']$key[\"'][^>]*>", RegexOption.IGNORE_CASE)
            val tag = re.find(html)?.value ?: return ""
            return Regex("content=[\"'](.*?)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.trim() ?: ""
        }
        val description = meta("name", "description").ifEmpty { meta("property", "og:description") }
        val themeColor = meta("name", "theme-color")
        val siteName = meta("property", "og:site_name").ifEmpty { meta("property", "og:title") }

        // Ikon: apple-touch-icon / icon terbesar yang diumumkan, fallback /favicon.ico
        var iconUrl = ""
        var best = -1
        Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val rel = Regex("rel=[\"'](.*?)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
            if (!rel.contains("icon")) return@forEach
            val href = Regex("href=[\"'](.*?)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@forEach
            val size = Regex("(\\d+)x\\d+").find(
                Regex("sizes=[\"'](.*?)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: ""
            )?.groupValues?.get(1)?.toIntOrNull() ?: if (rel.contains("apple")) 180 else 32
            if (size > best) { best = size; iconUrl = resolve(finalUrl, href) }
        }
        if (iconUrl.isEmpty()) iconUrl = resolve(finalUrl, "/favicon.ico")

        var iconBytes: ByteArray? = null
        try {
            val ic = open(iconUrl)
            if (ic.responseCode in 200..299) {
                val bytes = ic.inputStream.use { it.readBytes() }
                if (bytes.size in 100..1_000_000) iconBytes = bytes
            }
            ic.disconnect()
        } catch (e: Exception) {
            iconBytes = null // ikon gagal bukan kegagalan fatal — generator akan membuat ikon huruf
        }

        return SiteInfo(
            finalUrl = finalUrl,
            title = title.ifEmpty { siteName },
            description = description,
            themeColor = themeColor,
            iconUrl = iconUrl,
            iconBytes = iconBytes,
        )
    }

    private fun resolve(base: String, href: String): String = try {
        URL(URL(base), href).toString()
    } catch (e: Exception) {
        href
    }
}
