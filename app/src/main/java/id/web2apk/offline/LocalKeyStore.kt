package id.web2apk.offline

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalSigner(val privateKey: PrivateKey, val certificate: X509Certificate) {
    fun fingerprintSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }
}

/**
 * Keystore lokal mode offline: satu pasangan kunci RSA + sertifikat
 * self-signed, dibuat sekali di penyimpanan privat aplikasi, dipakai
 * menandatangani SEMUA APK hasil mode offline agar saling menimpa.
 * Password keystore dienkripsi Android Keystore. File keystore bisa
 * diekspor untuk cadangan dari UI — TANPA cadangan, ganti HP berarti
 * APK baru tidak bisa menimpa yang lama.
 */
class LocalKeyStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("localkey", Context.MODE_PRIVATE)
    private val ksFile: File get() = File(context.filesDir, "offline-signer.p12")

    fun exists(): Boolean = ksFile.exists() && prefs.contains("pw_ct")

    fun loadOrCreate(): LocalSigner {
        if (exists()) {
            val pw = loadPassword()
            val ks = KeyStore.getInstance("PKCS12")
            ksFile.inputStream().use { ks.load(it, pw.toCharArray()) }
            val key = ks.getKey(ALIAS, pw.toCharArray()) as PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            return LocalSigner(key, cert)
        }
        return create()
    }

    private fun create(): LocalSigner {
        ensureBcProvider()
        val pw = randomPassword()
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Web2APK Lokal, O=Web2APK, C=ID")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now / 1000),
            Date(now - 24L * 3600 * 1000), Date(now + 25L * 365 * 24 * 3600 * 1000),
            subject, pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(pair.private)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, pair.private, pw.toCharArray(), arrayOf(cert))
        ksFile.outputStream().use { ks.store(it, pw.toCharArray()) }
        savePassword(pw)
        return LocalSigner(pair.private, cert)
    }

    /** Salin file keystore mentah ke output (untuk cadangan user). */
    fun exportTo(out: OutputStream) {
        ksFile.inputStream().use { it.copyTo(out) }
    }

    fun keystorePassword(): String = loadPassword()

    // ---------- password terenkripsi Android Keystore ----------

    private fun savePassword(pw: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val ct = cipher.doFinal(pw.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("pw_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("pw_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    private fun loadPassword(): String {
        val iv = prefs.getString("pw_iv", null) ?: error("Password keystore hilang")
        val ct = prefs.getString("pw_ct", null) ?: error("Password keystore hilang")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun randomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        private const val ALIAS = "web2apk-offline"
        private const val AES_ALIAS = "web2apk_localkey_pw"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        @Volatile private var bcReady = false
        fun ensureBcProvider() {
            if (!bcReady) {
                synchronized(this) {
                    if (Security.getProvider("BC") == null) {
                        Security.addProvider(BouncyCastleProvider())
                    }
                    bcReady = true
                }
            }
        }
    }
}
