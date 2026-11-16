package id.web2apk.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan token GitHub user terenkripsi dengan kunci Android Keystore.
 * Token tidak pernah ditulis plaintext ke disk dan tidak pernah dikirim
 * ke mana pun selain api.github.com.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("github", Context.MODE_PRIVATE)

    var builderRepo: String
        get() = prefs.getString("builder_repo", DEFAULT_REPO) ?: DEFAULT_REPO
        set(value) = prefs.edit().putString("builder_repo", value.trim()).apply()

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun loadToken(): String {
        val iv = prefs.getString("token_iv", null) ?: return ""
        val ct = prefs.getString("token_ct", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun clearToken() = prefs.edit().remove("token_iv").remove("token_ct").apply()

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val DEFAULT_REPO = "qioceky/web2apk-builder"
        private const val ALIAS = "web2apk_gh_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
