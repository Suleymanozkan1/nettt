package com.bookmind.app.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Supabase session (refresh token) encrypted with an AES-GCM key that never leaves the Android
 * Keystore. If the keystore is unavailable (e.g. some emulators/robolectric) the session is kept in memory
 * only, so the user simply signs in again after a restart.
 */
interface SessionStore {
    fun read(): String?
    fun write(value: String?)
}

@Singleton
class KeystoreSessionStore @Inject constructor(@ApplicationContext context: Context) : SessionStore {
    private val file = File(context.noBackupFilesDir, "session.bin")
    private var memory: String? = null

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }.getOrNull()

    override fun read(): String? {
        memory?.let { return it }
        if (!file.exists()) return null
        val key = key() ?: return null
        return runCatching {
            val (ivB64, dataB64) = file.readText().split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)))
        }.getOrNull().also { memory = it }
    }

    override fun write(value: String?) {
        memory = value
        if (value == null) { file.delete(); return }
        val key = key() ?: return
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val data = cipher.doFinal(value.toByteArray())
            file.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP))
        }
    }

    private companion object { const val ALIAS = "bookmind_session_v1" }
}
