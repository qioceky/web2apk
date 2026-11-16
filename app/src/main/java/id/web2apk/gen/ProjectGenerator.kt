package id.web2apk.gen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import id.web2apk.data.ProjectConfig
import id.web2apk.data.SiteInfo
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Menghasilkan ZIP project Android lengkap untuk satu situs, dari template
 * di assets/genproject. Project hasil generate siap di-push ke GitHub dan
 * dibangun workflow-nya sendiri menjadi APK.
 */
object ProjectGenerator {

    private val TEXT_FILES = listOf(
        "settings.gradle.tmpl" to "settings.gradle",
        "build.gradle.tmpl" to "build.gradle",
        "gradle.properties.tmpl" to "gradle.properties",
        "gradle/libs.versions.toml.tmpl" to "gradle/libs.versions.toml",
        "app/build.gradle.tmpl" to "app/build.gradle",
        "app/src/main/AndroidManifest.xml.tmpl" to "app/src/main/AndroidManifest.xml",
        "app/src/main/res/values/strings.xml.tmpl" to "app/src/main/res/values/strings.xml",
        "app/src/main/res/values/themes.xml.tmpl" to "app/src/main/res/values/themes.xml",
        "app/src/main/assets/config.json.tmpl" to "app/src/main/assets/config.json",
        ".github/workflows/build.yml.tmpl" to ".github/workflows/build.yml",
        "README.md.tmpl" to "README.md",
        ".gitignore.tmpl" to ".gitignore",
    )
    private val RAW_FILES = listOf(
        "gradlew" to "gradlew",
        "gradle/wrapper/gradle-wrapper.jar" to "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties" to "gradle/wrapper/gradle-wrapper.properties",
    )

    fun generate(context: Context, cfg: ProjectConfig, site: SiteInfo?, out: OutputStream) {
        val tokens = buildTokens(cfg)
        val pkgPath = cfg.packageId.replace('.', '/')
        ZipOutputStream(out.buffered()).use { zip ->
            for ((asset, dest) in TEXT_FILES) {
                val text = context.assets.open("genproject/$asset").bufferedReader().use { it.readText() }
                zip.putEntry(dest, replaceTokens(text, tokens).toByteArray(Charsets.UTF_8))
            }
            // MainActivity masuk ke folder paket hasil generate.
            val mainSrc = context.assets.open("genproject/app/src/main/java/MainActivity.kt.tmpl")
                .bufferedReader().use { it.readText() }
            zip.putEntry(
                "app/src/main/java/$pkgPath/MainActivity.kt",
                replaceTokens(mainSrc, tokens).toByteArray(Charsets.UTF_8)
            )
            zip.putEntry(
                "app/proguard-rules.pro",
                "# Dibuat Web2APK — WebView murni, tidak ada aturan khusus.\n".toByteArray()
            )
            for ((asset, dest) in RAW_FILES) {
                val bytes = context.assets.open("genproject/$asset").use { it.readBytes() }
                zip.putEntry(dest, bytes)
            }
            if (cfg.sourceType == "html") {
                zip.putEntry("app/src/main/assets/page.html", cfg.htmlContent.toByteArray(Charsets.UTF_8))
            }
            writeIcons(zip, cfg, site)
        }
    }

    private fun buildTokens(cfg: ProjectConfig): Map<String, String> {
        fun b(v: Boolean) = v.toString()
        return mapOf(
            "__APP_NAME__" to cfg.appName,
            "__APP_NAME_JSON__" to cfg.appName.replace("\\", "\\\\").replace("\"", "\\\""),
            "__APP_NAME_XML__" to cfg.appName.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "\\'"),
            "__PROJECT_NAME__" to cfg.appName.replace(Regex("[^A-Za-z0-9 ]"), "").trim().ifEmpty { "WebApp" },
            "__PROJECT_SLUG__" to ProjectConfig.slugFromName(cfg.appName),
            "__PACKAGE__" to cfg.packageId,
            "__URL__" to ProjectConfig.normalizeUrl(cfg.siteUrl),
            "__SOURCE_TYPE__" to cfg.sourceType,
            "__VERSION_NAME__" to cfg.versionName,
            "__VERSION_CODE__" to cfg.versionCode.toString(),
            "__THEME_COLOR__" to cfg.themeColor,
            "__OPT_JAVASCRIPT__" to b(cfg.javaScript),
            "__OPT_ZOOM__" to b(cfg.zoom),
            "__OPT_FILE_UPLOAD__" to b(cfg.fileUpload),
            "__OPT_EXTERNAL_LINKS__" to b(cfg.externalLinksInBrowser),
            "__OPT_SPLASH__" to b(cfg.splashScreen),
            "__OPT_HIDE_TOOLBAR__" to b(cfg.hideToolbar),
            "__OPT_PULL_REFRESH__" to b(cfg.pullToRefresh),
            "__OPT_COOKIES__" to b(cfg.allowCookies),
        )
    }

    private fun replaceTokens(text: String, tokens: Map<String, String>): String {
        var out = text
        for ((k, v) in tokens) out = out.replace(k, v)
        return out
    }

    // ---------- Ikon ----------

    private fun writeIcons(zip: ZipOutputStream, cfg: ProjectConfig, site: SiteInfo?) {
        val src: Bitmap? = site?.iconBytes?.let { bytes ->
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null }
        }
        val base: Bitmap = src ?: letterIcon(cfg)
        val sizes = mapOf(
            "mipmap-mdpi" to 48, "mipmap-hdpi" to 72, "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144, "mipmap-xxxhdpi" to 192,
        )
        for ((dir, size) in sizes) {
            val scaled = Bitmap.createScaledBitmap(base, size, size, true)
            zip.putEntry("app/src/main/res/$dir/ic_launcher.png", png(scaled))
            zip.putEntry("app/src/main/res/$dir/ic_launcher_round.png", png(scaled))
        }
        // Foreground adaptive icon: ikon di tengah kanvas 432 dengan ruang aman.
        val fg = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fg)
        val inner = Bitmap.createScaledBitmap(base, 264, 264, true)
        canvas.drawBitmap(inner, 84f, 84f, null)
        zip.putEntry("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png", png(fg))
        val adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_bg"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
        zip.putEntry("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", adaptive.toByteArray())
        zip.putEntry("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", adaptive.toByteArray())
        val theme = try { Color.parseColor(cfg.themeColor) } catch (e: Exception) { Color.WHITE }
        val hex = String.format("#%06X", 0xFFFFFF and theme)
        zip.putEntry(
            "app/src/main/res/values/colors.xml",
            ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
                "    <color name=\"ic_bg\">$hex</color>\n</resources>\n").toByteArray()
        )
    }

    /** Ikon huruf: latar warna tema + huruf pertama nama aplikasi. */
    private fun letterIcon(cfg: ProjectConfig): Bitmap {
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = try { Color.parseColor(cfg.themeColor) } catch (e: Exception) { Color.rgb(255, 208, 46) }
        canvas.drawColor(bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 110f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val letter = cfg.appName.trim().firstOrNull()?.uppercase() ?: "W"
        canvas.drawText(letter, 96f, 132f, paint)
        return bmp
    }

    private fun png(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
