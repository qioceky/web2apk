package id.web2apk

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import id.web2apk.data.ProjectConfig
import id.web2apk.data.ProjectStore
import id.web2apk.data.SiteInfo
import id.web2apk.offline.LocalKeyStore
import id.web2apk.offline.OfflineBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kartu "Mode Offline": build APK langsung di HP dari template yang
 * dibundel — tanpa GitHub, tanpa token, tanpa jaringan.
 */
@Composable
fun OfflineSection(cfg: ProjectConfig, site: SiteInfo?, valid: Boolean, store: ProjectStore, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyStore = remember { LocalKeyStore(context) }

    var phase by remember { mutableStateOf("") }
    var building by remember { mutableStateOf(false) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var fingerprint by remember {
        mutableStateOf(if (keyStore.exists()) {
            try { keyStore.loadOrCreate().fingerprintSha256() } catch (e: Exception) { "" }
        } else "")
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val exportKeystore = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> keyStore.exportTo(out) }
                        ?: throw IllegalStateException("Tidak bisa membuka file tujuan")
                }
                toast("Keystore tersimpan. Password-nya: lihat tombol Tampilkan Password — simpan keduanya bersama.")
            } catch (e: Exception) {
                toast("Ekspor gagal: ${e.message}")
            }
        }
    }

    NeoCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("5 · Mode Offline (Tanpa GitHub)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
            Text(
                "APK dibuat langsung di HP ini dari template bawaan aplikasi — tanpa token, " +
                    "tanpa jaringan, prosesnya detik-an. Ditandatangani keystore lokal di HP-mu, " +
                    "jadi semua APK offline-mu bisa saling menimpa.",
                fontSize = 12.sp, color = Muted
            )

            NeoButton(
                text = if (building) "Membuat APK…" else "Build APK Offline",
                enabled = valid && !building
            ) {
                building = true
                apkFile = null
                phase = "Memulai…"
                scope.launch {
                    try {
                        val out = withContext(Dispatchers.IO) {
                            OfflineBuilder.build(context, cfg, site) { step ->
                                scope.launch { phase = step }
                            }
                        }
                        store.save(cfg.copy(createdAt = System.currentTimeMillis()))
                        onSaved()
                        if (fingerprint.isEmpty()) {
                            fingerprint = try {
                                keyStore.loadOrCreate().fingerprintSha256()
                            } catch (e: Exception) { "" }
                        }
                        apkFile = out
                        phase = "APK offline siap: ${out.name} (${out.length() / 1024} KB) — ketuk Pasang APK."
                    } catch (e: Exception) {
                        phase = "Gagal: ${e.message}"
                    } finally {
                        building = false
                    }
                }
            }
            if (phase.isNotBlank()) Text(phase, fontSize = 13.sp, color = Muted)

            apkFile?.let { file ->
                NeoButton(text = "Pasang APK") {
                    try {
                        val uri = FileProvider.getUriForFile(context, "id.web2apk.fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        toast("Gagal membuka pemasang: ${e.message}")
                    }
                }
            }

            if (fingerprint.isNotEmpty()) {
                Text("Kunci lokal: ${fingerprint.take(16)}…", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeoButton(text = "Cadangkan Keystore", fill = CardFill) {
                        exportKeystore.launch("web2apk-offline-keystore.p12")
                    }
                    NeoButton(text = "Lihat Password", fill = CardFill) {
                        try {
                            toast("Password keystore: ${keyStore.keystorePassword()}")
                        } catch (e: Exception) {
                            toast("Gagal membaca password: ${e.message}")
                        }
                    }
                }
                Text(
                    "Penting: simpan file cadangan + password-nya di tempat aman. Kalau HP ganti " +
                        "tanpa cadangan ini, APK offline berikutnya tidak bisa menimpa yang lama.",
                    fontSize = 12.sp, color = Color.Red
                )
            }
        }
    }
}
