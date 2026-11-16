package id.web2apk.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import id.web2apk.data.ProjectConfig
import id.web2apk.data.SiteInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Mode offline: patch template.apk yang dibundel aplikasi —
 * ganti package/label/versi/warna ikon lewat ARSCLib, ganti
 * config.json + ikon lewat ZipPatch (sekalian zipalign), lalu
 * tanda tangani dengan keystore lokal (apksig). Tanpa jaringan,
 * tanpa GitHub. Pipeline ini terverifikasi di JVM sebelum diporting.
 */
object OfflineBuilder {

    fun build(context: Context, cfg: ProjectConfig, site: SiteInfo?, onStep: (String) -> Unit): File {
        val slug = ProjectConfig.slugFromName(cfg.appName)
        val workDir = File(context.cacheDir, "offline/$slug").apply { mkdirs() }
        val outDir = File(context.getExternalFilesDir(null), "unduhan").apply { mkdirs() }

        onStep("Menyalin template…")
        val template = File(workDir, "template.apk")
        context.assets.open("template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }

        onStep("Menyiapkan kunci tanda tangan lokal…")
        val keyStore = LocalKeyStore(context)
        val signer = keyStore.loadOrCreate()

        onStep("Mengedit paket, nama & ikon…")
        val stage1 = File(workDir, "stage1.apk")
        val replacements = HashMap<String, ByteArray>()
        val module = ApkModule.loadApkFile(template)
        try {
            val manifest = module.androidManifest
            manifest.setPackageName(cfg.packageId)
            manifest.setVersionCode(cfg.versionCode)
            manifest.setVersionName(cfg.versionName)
            manifest.setApplicationLabel(cfg.appName)
            module.refreshManifest()

            // Warna latar ikon adaptif (color/ic_bg) mengikuti warna tema.
            val argb = try { Color.parseColor(cfg.themeColor) } catch (e: Exception) { Color.rgb(255, 208, 46) }
            val resources = module.tableBlock.resources
            while (resources.hasNext()) {
                val re = resources.next()
                if (re.type == "color" && re.name == "ic_bg") {
                    for (entry in re) entry.resValue.setData(argb)
                }
            }

            // Kumpulkan path berkas ikon dari tabel resource.
            val iconBitmap = decodeIcon(site) ?: letterIcon(cfg)
            for (resFile in module.listResFiles()) {
                for (entry in resFile) {
                    val name = entry.name ?: continue
                    if (name != "ic_launcher" && name != "ic_launcher_round" && name != "ic_launcher_foreground") continue
                    val path = resFile.filePath ?: continue
                    if (!path.endsWith(".png")) continue // XML adaptif dibiarkan
                    val density = try { entry.resConfig.densityValue } catch (e: Exception) { 0 }
                    val bmp = if (name == "ic_launcher_foreground") {
                        foregroundIcon(iconBitmap)
                    } else {
                        val size = when (density) {
                            120 -> 36; 160 -> 48; 240 -> 72; 320 -> 96; 480 -> 144; 640 -> 192
                            else -> 192
                        }
                        Bitmap.createScaledBitmap(iconBitmap, size, size, true)
                    }
                    replacements[path] = pngBytes(bmp)
                }
            }
            module.writeApk(stage1)
        } finally {
            module.close()
        }

        onStep("Menulis konfigurasi website…")
        val configJson = JSONObject().apply {
            put("sourceType", cfg.sourceType)
            put("startUrl", ProjectConfig.normalizeUrl(cfg.siteUrl))
            put("appName", cfg.appName)
            put("themeColor", cfg.themeColor)
            put("javaScript", cfg.javaScript)
            put("zoom", cfg.zoom)
            put("fileUpload", cfg.fileUpload)
            put("externalLinks", cfg.externalLinksInBrowser)
            put("splash", cfg.splashScreen)
            put("hideToolbar", cfg.hideToolbar)
            put("pullRefresh", cfg.pullToRefresh)
            put("cookies", cfg.allowCookies)
        }
        replacements["assets/config.json"] = configJson.toString().toByteArray(Charsets.UTF_8)
        if (cfg.sourceType == "html") {
            replacements["assets/page.html"] = cfg.htmlContent.toByteArray(Charsets.UTF_8)
        }

        val unsigned = File(workDir, "unsigned.apk")
        ZipPatch.patch(stage1, unsigned, replacements)

        onStep("Menandatangani APK…")
        val outApk = File(outDir, "$slug.apk")
        val signerConfig = ApkSigner.SignerConfig.Builder("Web2APK Lokal", signer.privateKey, listOf(signer.certificate)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsigned)
            .setOutputApk(outApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()

        // Self-check: installer menolak APK yang entri STORED-nya tidak
        // sejajar (kasus v0.4.1). Gagal di sini = pesan jelas, bukan APK
        // rusak yang ditolak diam-diam oleh pemasang.
        onStep("Memeriksa hasil akhir…")
        val badEntry = ZipPatch.checkAlignment(outApk)
        if (badEntry != null) {
            throw IllegalStateException("Alignment ZIP gagal pada entri $badEntry — laporkan pesan ini.")
        }
        onStep("Selesai")
        return outApk
    }

    private fun decodeIcon(site: SiteInfo?): Bitmap? {
        val bytes = site?.iconBytes ?: return null
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null }
    }

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

    private fun foregroundIcon(base: Bitmap): Bitmap {
        val fg = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fg)
        val inner = Bitmap.createScaledBitmap(base, 264, 264, true)
        canvas.drawBitmap(inner, 84f, 84f, null)
        return fg
    }

    private fun pngBytes(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
