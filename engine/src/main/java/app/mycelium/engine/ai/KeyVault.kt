package app.mycelium.engine.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's API key, encrypted with a non-exportable AES-GCM key in the Android Keystore and stored as
 * app-private file (iv length, iv, ciphertext). Never in the DB, events, logs or exports; backups are off.
 */
class KeyVault(context: Context) {
    private val file = File(context.noBackupFilesDir, "ai_key.bin")

    fun has() = file.exists()

    @Synchronized fun save(apiKey: String) {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val sealed = c.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + sealed)
        if (!tmp.renameTo(file)) { tmp.delete(); error("could not store key") }
    }

    /** Null if absent or no longer decryptable (then the stored blob is removed). */
    @Synchronized fun load(): String? {
        if (!file.exists()) return null
        return try {
            val b = file.readBytes()
            val n = b[0].toInt()
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b, 1, n)) }
            String(c.doFinal(b, 1 + n, b.size - 1 - n), Charsets.UTF_8)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized fun clear() {
        file.delete()
        try { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "mycelium_ai_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
