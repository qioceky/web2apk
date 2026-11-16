package id.web2apk

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import id.web2apk.data.GitHubClient
import id.web2apk.data.ProjectConfig
import id.web2apk.data.ProjectStore
import id.web2apk.data.SiteInfo
import id.web2apk.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kartu "Build Langsung": dispatch workflow web2apk-builder, pantau run,
 * unduh APK dari Release repo builder, lalu tawarkan pemasangan.
 */
@Composable
fun BuildSection(cfg: ProjectConfig, site: SiteInfo?, valid: Boolean, store: ProjectStore, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore(context) }

    var token by remember { mutableStateOf(tokenStore.loadToken()) }
    var tokenInput by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(tokenStore.builderRepo) }
    var loginName by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("") }
    var building by remember { mutableStateOf(false) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var buildJob by remember { mutableStateOf<Job?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    NeoCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("4 · Build Langsung ke APK", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
            Text(
                "APK dibangun server GitHub lewat repo builder, lalu terbit di Releases repo itu " +
                    "dan bisa diunduh + dipasang dari sini. Butuh token GitHub milikmu sendiri.",
                fontSize = 12.sp, color = Muted
            )

            if (token.isEmpty()) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Token GitHub") },
                    placeholder = { Text("ghp_… / github_pat_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Buat di github.com/settings/tokens — tipe classic, centang scope repo + workflow. " +
                        "Token disimpan terenkripsi (Android Keystore) di HP ini, hanya dikirim ke api.github.com.",
                    fontSize = 12.sp, color = Muted
                )
                NeoButton(text = "Simpan & Tes Token", enabled = tokenInput.isNotBlank()) {
                    scope.launch {
                        try {
                            val login = withContext(Dispatchers.IO) { GitHubClient(tokenInput.trim()).whoAmI() }
                            tokenStore.saveToken(tokenInput.trim())
                            token = tokenInput.trim()
                            tokenInput = ""
                            loginName = login
                            toast("Token tersimpan — masuk sebagai @$login")
                        } catch (e: Exception) {
                            toast("Token gagal: ${e.message}")
                        }
                    }
                }
            } else {
                Text(
                    if (loginName.isNotEmpty()) "Token GitHub: tersimpan (@$loginName)" else "Token GitHub: tersimpan",
                    fontSize = 13.sp, color = Ink
                )
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("Repo builder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeoButton(text = "Simpan Repo", fill = CardFill) {
                        tokenStore.builderRepo = repo
                        toast("Repo builder disimpan")
                    }
                    NeoButton(text = "Hapus Token", fill = CardFill) {
                        buildJob?.cancel()
                        tokenStore.clearToken()
                        token = ""
                        loginName = ""
                        phase = ""
                    }
                }
            }

            if (token.isNotEmpty()) {
                NeoButton(
                    text = if (building) "Sedang Build…" else "Build APK Sekarang",
                    enabled = valid && !building
                ) {
                    tokenStore.builderRepo = repo
                    apkFile = null
                    building = true
                    phase = "Mengirim perintah build ke GitHub…"
                    buildJob = scope.launch {
                        try {
                            val client = GitHubClient(token)
                            val prevMaxId = withContext(Dispatchers.IO) {
                                try { client.latestRuns(repo).maxOfOrNull { it.id } ?: 0L } catch (e: Exception) { 0L }
                            }
                            withContext(Dispatchers.IO) { client.dispatchBuild(repo, cfg, site?.iconUrl ?: "") }

                            store.save(cfg.copy(createdAt = System.currentTimeMillis()))
                            onSaved()

                            var runId: Long? = null
                            var tries = 0
                            while (runId == null && tries < 24) {
                                delay(if (tries == 0) 8000 else 12000)
                                val runs = withContext(Dispatchers.IO) { client.latestRuns(repo) }
                                runId = runs.firstOrNull { it.id > prevMaxId }?.id
                                tries++
                                phase = "Dispatch terkirim — menunggu run build muncul…"
                            }
                            val rid = runId ?: throw IllegalStateException(
                                "Run build tidak muncul — periksa tab Actions di repo builder.")

                            var done = false
                            var conclusion: String? = null
                            var guard = 0
                            while (!done && guard < 60) {
                                val runs = withContext(Dispatchers.IO) { client.latestRuns(repo) }
                                val run = runs.firstOrNull { it.id == rid }
                                if (run != null && run.status == "completed") {
                                    done = true
                                    conclusion = run.conclusion
                                } else {
                                    val step = withContext(Dispatchers.IO) { client.currentStep(repo, rid) }
                                    phase = "Build berjalan di GitHub… $step"
                                    delay(15000)
                                }
                                guard++
                            }
                            if (!done) throw IllegalStateException("Build terlalu lama — cek tab Actions repo builder.")
                            if (conclusion != "success")
                                throw IllegalStateException("Build gagal di GitHub (conclusion=$conclusion) — buka tab Actions repo builder untuk log-nya.")

                            phase = "Build sukses — mencari APK di Releases…"
                            val slug = ProjectConfig.slugFromName(cfg.appName)
                            var asset = withContext(Dispatchers.IO) { client.findRelease(repo, slug, cfg.versionName) }
                            var rtries = 0
                            while (asset == null && rtries < 12) {
                                delay(10000)
                                asset = withContext(Dispatchers.IO) { client.findRelease(repo, slug, cfg.versionName) }
                                rtries++
                            }
                            val found = asset ?: throw IllegalStateException(
                                "APK tidak ditemukan di Releases — cek repo builder.")

                            val dir = File(context.getExternalFilesDir(null), "unduhan").apply { mkdirs() }
                            val dest = File(dir, found.name)
                            phase = "Mengunduh ${found.name}…"
                            withContext(Dispatchers.IO) {
                                client.downloadAsset(repo, found, dest) { doneBytes, total ->
                                    if (total > 0) {
                                        val pct = doneBytes * 100 / total
                                        phase = "Mengunduh ${found.name}… $pct%"
                                    }
                                }
                            }
                            apkFile = dest
                            phase = "APK siap: ${found.name} (${found.size / 1024} KB) — ketuk Pasang APK."
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) {
                                phase = "Build dibatalkan."
                            } else {
                                phase = "Gagal: ${e.message}"
                            }
                        } finally {
                            building = false
                        }
                    }
                }
                if (building) {
                    NeoButton(text = "Batalkan Pantauan", fill = CardFill) { buildJob?.cancel() }
                }
            }

            if (phase.isNotBlank()) Text(phase, fontSize = 13.sp, color = Muted)
            apkFile?.let { file ->
                Spacer(Modifier.height(2.dp))
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
                Text(
                    "Catatan: Android mungkin meminta izin 'install dari sumber ini' untuk Web2APK — izinkan sekali.",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
