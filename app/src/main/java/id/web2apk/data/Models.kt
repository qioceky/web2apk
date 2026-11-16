package id.web2apk.data

import org.json.JSONObject

/** Konfigurasi satu project web→APK yang dibuat user. */
data class ProjectConfig(
    val siteUrl: String = "",
    val appName: String = "",
    val packageId: String = "",
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val themeColor: String = "#FFD02E",
    val javaScript: Boolean = true,
    val zoom: Boolean = false,
    val fileUpload: Boolean = true,
    val externalLinksInBrowser: Boolean = true,
    val splashScreen: Boolean = true,
    val hideToolbar: Boolean = false,
    val pullToRefresh: Boolean = true,
    val allowCookies: Boolean = true,
    val createdAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("siteUrl", siteUrl); put("appName", appName); put("packageId", packageId)
        put("versionName", versionName); put("versionCode", versionCode)
        put("themeColor", themeColor); put("javaScript", javaScript); put("zoom", zoom)
        put("fileUpload", fileUpload); put("externalLinksInBrowser", externalLinksInBrowser)
        put("splashScreen", splashScreen); put("hideToolbar", hideToolbar)
        put("pullToRefresh", pullToRefresh); put("allowCookies", allowCookies)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = ProjectConfig(
            siteUrl = o.optString("siteUrl"), appName = o.optString("appName"),
            packageId = o.optString("packageId"), versionName = o.optString("versionName", "1.0"),
            versionCode = o.optInt("versionCode", 1), themeColor = o.optString("themeColor", "#FFD02E"),
            javaScript = o.optBoolean("javaScript", true), zoom = o.optBoolean("zoom"),
            fileUpload = o.optBoolean("fileUpload", true),
            externalLinksInBrowser = o.optBoolean("externalLinksInBrowser", true),
            splashScreen = o.optBoolean("splashScreen", true), hideToolbar = o.optBoolean("hideToolbar"),
            pullToRefresh = o.optBoolean("pullToRefresh", true),
            allowCookies = o.optBoolean("allowCookies", true), createdAt = o.optLong("createdAt"),
        )

        /** Slug paket dari nama aplikasi: "Toko Saya!" -> "tokosaya" */
        fun slugFromName(name: String): String {
            val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()
            return if (s.isEmpty() || s[0].isDigit()) "app$s" else s
        }

        fun isValidPackageId(pkg: String): Boolean =
            pkg.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))

        fun normalizeUrl(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return t
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}

/** Hasil pengambilan info situs (judul, ikon, warna tema). */
data class SiteInfo(
    val finalUrl: String,
    val title: String = "",
    val description: String = "",
    val themeColor: String = "",
    val iconUrl: String = "",
    val iconBytes: ByteArray? = null,
)
