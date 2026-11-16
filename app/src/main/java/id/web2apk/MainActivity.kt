package id.web2apk

import android.graphics.BitmapFactory
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.web2apk.data.ProjectConfig
import id.web2apk.data.ProjectStore
import id.web2apk.data.SiteFetcher
import id.web2apk.data.SiteInfo
import id.web2apk.gen.ProjectGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Paper = Color(0xFFFFFDF7)
val Ink = Color(0xFF161616)
val Yellow = Color(0xFFFFD02E)
val CardFill = Color(0xFFFFFFFF)
val Muted = Color(0xFF6B6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Web2ApkApp() }
    }
}

@Composable
fun NeoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .background(CardFill, RoundedCornerShape(14.dp))
            .border(2.5.dp, Ink, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
fun NeoButton(text: String, fill: Color = Yellow, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = Ink),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.border(2.dp, Ink, RoundedCornerShape(10.dp))
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun OptionRow(label: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(desc, fontSize = 12.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Web2ApkApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProjectStore(context) }

    var cfg by remember { mutableStateOf(ProjectConfig()) }
    var site by remember { mutableStateOf<SiteInfo?>(null) }
    var history by remember { mutableStateOf(store.loadAll()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val createZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Membuat project ZIP…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        ProjectGenerator.generate(context, cfg, site, out)
                    } ?: throw IllegalStateException("Tidak bisa membuka file tujuan")
                }
                val saved = cfg.copy(createdAt = System.currentTimeMillis())
                store.save(saved)
                history = store.loadAll()
                status = "Project ZIP berhasil dibuat."
                toast("Project ZIP berhasil dibuat — push ke GitHub untuk build APK-nya.")
            } catch (e: Exception) {
                status = "Gagal: ${e.message}"
                toast("Gagal membuat ZIP: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    val pickHtml = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Tidak bisa membaca file")
                }
                if (text.length > 2_000_000) {
                    toast("File HTML terlalu besar (maks ±2 MB).")
                    return@launch
                }
                cfg = cfg.copy(htmlContent = text)
                status = "File HTML dimuat (${text.length} karakter)."
            } catch (e: Exception) {
                status = "Gagal membaca file: ${e.message}"
            }
        }
    }

    if (showPreview) {
        PreviewScreen(
            url = ProjectConfig.normalizeUrl(cfg.siteUrl),
            html = if (cfg.sourceType == "html") cfg.htmlContent else null,
            cfg = cfg
        ) { showPreview = false }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(Yellow).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Web2APK", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink)
                Text("Ubah website jadi aplikasi Android", fontSize = 13.sp, color = Ink)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NeoCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1 · Sumber Konten", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeoButton(
                            text = "Alamat Web",
                            fill = if (cfg.sourceType == "url") Yellow else CardFill
                        ) { cfg = cfg.copy(sourceType = "url") }
                        NeoButton(
                            text = "HTML",
                            fill = if (cfg.sourceType == "html") Yellow else CardFill
                        ) { cfg = cfg.copy(sourceType = "html"); site = null }
                    }
                    if (cfg.sourceType == "html") {
                        Text(
                            "Tempel kode HTML atau pilih file .html — aplikasi hasil build membukanya " +
                                "dari dalam APK, jalan full offline. Satu file ya (CSS/JS inline).",
                            fontSize = 12.sp, color = Muted
                        )
                        NeoButton(text = "Pilih File .html", fill = CardFill) {
                            pickHtml.launch(arrayOf("text/html", "text/plain", "*/*"))
                        }
                        OutlinedTextField(
                            value = cfg.htmlContent,
                            onValueChange = { cfg = cfg.copy(htmlContent = it) },
                            label = { Text("Kode HTML") },
                            placeholder = { Text("<html><body>Halo!</body></html>") },
                            minLines = 5,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${cfg.htmlContent.length} karakter", fontSize = 12.sp, color = Muted)
                    } else {
                    OutlinedTextField(
                        value = cfg.siteUrl,
                        onValueChange = { cfg = cfg.copy(siteUrl = it) },
                        label = { Text("Alamat website") },
                        placeholder = { Text("contoh: tokosaya.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    NeoButton(text = if (busy) "Mengambil…" else "Ambil Info Situs", enabled = !busy && cfg.siteUrl.isNotBlank()) {
                        busy = true
                        status = "Mengambil info situs…"
                        scope.launch {
                            try {
                                val info = withContext(Dispatchers.IO) { SiteFetcher.fetch(cfg.siteUrl) }
                                site = info
                                cfg = cfg.copy(
                                    siteUrl = info.finalUrl,
                                    appName = if (cfg.appName.isBlank()) info.title else cfg.appName,
                                    packageId = if (cfg.packageId.isBlank())
                                        "id." + ProjectConfig.slugFromName(info.title.ifEmpty { "webapp" })
                                    else cfg.packageId,
                                    themeColor = if (info.themeColor.isNotBlank()) info.themeColor else cfg.themeColor,
                                )
                                status = "Info situs berhasil diambil."
                            } catch (e: Exception) {
                                status = "Gagal mengambil situs: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                    site?.let { info ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val bmp = remember(info.iconBytes) {
                                info.iconBytes?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (e: Exception) { null } }
                            }
                            if (bmp != null) {
                                Image(bmp.asImageBitmap(), contentDescription = "Ikon situs",
                                    modifier = Modifier.size(40.dp))
                                Spacer(Modifier.size(10.dp))
                            }
                            Column {
                                if (info.title.isNotBlank()) Text(info.title, fontWeight = FontWeight.SemiBold, color = Ink)
                                if (info.description.isNotBlank())
                                    Text(info.description, fontSize = 12.sp, color = Muted, maxLines = 2)
                                Text("Warna tema: ${cfg.themeColor}", fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                    }
                }
            }

            NeoCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2 · Aplikasi", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                    OutlinedTextField(
                        value = cfg.appName,
                        onValueChange = { name ->
                            cfg = cfg.copy(
                                appName = name,
                                packageId = if (cfg.packageId.isBlank() || cfg.packageId == "id." + ProjectConfig.slugFromName(cfg.appName))
                                    "id." + ProjectConfig.slugFromName(name) else cfg.packageId
                            )
                        },
                        label = { Text("Nama aplikasi") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cfg.packageId,
                        onValueChange = { cfg = cfg.copy(packageId = it.trim()) },
                        label = { Text("Package ID") }, singleLine = true,
                        supportingText = {
                            if (cfg.packageId.isNotBlank() && !ProjectConfig.isValidPackageId(cfg.packageId))
                                Text("Format tidak valid — contoh: id.tokosaya", color = Color.Red)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cfg.versionName,
                            onValueChange = { cfg = cfg.copy(versionName = it) },
                            label = { Text("Versi") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = cfg.themeColor,
                            onValueChange = { cfg = cfg.copy(themeColor = it) },
                            label = { Text("Warna tema (#hex)") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            NeoCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("3 · Pilihan WebView", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    OptionRow("JavaScript", "Wajib untuk hampir semua situs modern", cfg.javaScript) { cfg = cfg.copy(javaScript = it) }
                    OptionRow("Upload file", "Formulir di situs bisa memilih file dari HP", cfg.fileUpload) { cfg = cfg.copy(fileUpload = it) }
                    OptionRow("Tarik untuk segarkan", "Geser ke bawah untuk memuat ulang halaman", cfg.pullToRefresh) { cfg = cfg.copy(pullToRefresh = it) }
                    OptionRow("Splash screen", "Layar pembuka berwarna tema + nama aplikasi", cfg.splashScreen) { cfg = cfg.copy(splashScreen = it) }
                    OptionRow("Link luar di browser", "Tautan ke situs lain dibuka di browser, bukan di aplikasi", cfg.externalLinksInBrowser) { cfg = cfg.copy(externalLinksInBrowser = it) }
                    OptionRow("Cookie", "Sesi login situs tetap tersimpan", cfg.allowCookies) { cfg = cfg.copy(allowCookies = it) }
                    OptionRow("Zoom", "Cubit untuk memperbesar halaman", cfg.zoom) { cfg = cfg.copy(zoom = it) }
                    OptionRow("Sembunyikan bilah judul", "Tanpa bilah atas — situs tampil penuh", cfg.hideToolbar) { cfg = cfg.copy(hideToolbar = it) }
                }
            }

            val valid = (if (cfg.sourceType == "html") cfg.htmlContent.isNotBlank() else cfg.siteUrl.isNotBlank()) &&
                cfg.appName.isNotBlank() && ProjectConfig.isValidPackageId(cfg.packageId)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeoButton(
                    text = "Pratinjau", fill = CardFill,
                    enabled = if (cfg.sourceType == "html") cfg.htmlContent.isNotBlank() else cfg.siteUrl.isNotBlank()
                ) { showPreview = true }
                NeoButton(text = if (busy) "Memproses…" else "Buat Project ZIP", enabled = valid && !busy) {
                    createZip.launch(ProjectConfig.slugFromName(cfg.appName) + "-project.zip")
                }
            }
            if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = Muted)
            Text(
                "ZIP berisi project Android lengkap + workflow GitHub Actions. Push ke GitHub, " +
                    "isi secret keystore sekali (panduan ada di README dalam ZIP), jalankan workflow — APK jadi.",
                fontSize = 12.sp, color = Muted
            )

            BuildSection(cfg = cfg, site = site, valid = valid, store = store) {
                history = store.loadAll()
            }

            OfflineSection(cfg = cfg, site = site, valid = valid, store = store) {
                history = store.loadAll()
            }

            if (history.isNotEmpty()) {
                Text("Project saya", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                history.forEach { item ->
                    NeoCard(modifier = Modifier.fillMaxWidth().clickable {
                        cfg = item
                        site = null
                        status = "Konfigurasi '${item.appName}' dimuat."
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.appName, fontWeight = FontWeight.SemiBold, color = Ink)
                                Text(item.siteUrl, fontSize = 12.sp, color = Muted, maxLines = 1)
                                Text(item.packageId, fontSize = 12.sp, color = Muted)
                            }
                            Text("Hapus", color = Color.Red, fontSize = 13.sp,
                                modifier = Modifier.clickable {
                                    store.delete(item.packageId)
                                    history = store.loadAll()
                                }.padding(6.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Web2APK v0.4.0 — dibuat untuk mengubah web jadi APK tanpa ribet.",
                fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun PreviewScreen(url: String, html: String?, cfg: ProjectConfig, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Yellow).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹ Kembali", fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.clickable { onBack() }.padding(6.dp))
            Text("Pratinjau — ${html?.let { "HTML lokal" } ?: url}", fontSize = 13.sp, color = Ink, maxLines = 1)
        }
        var webView by remember { mutableStateOf<WebView?>(null) }
        DisposableEffect(Unit) { onDispose { webView?.destroy() } }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.javaScriptEnabled = cfg.javaScript
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(cfg.zoom)
                    if (html != null) loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                    else loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
