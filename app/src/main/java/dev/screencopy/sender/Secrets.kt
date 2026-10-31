package dev.screencopy.sender

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Secrets internal constructor(context: Context, keyAlias: String = "screencopy-v1", prefsName: String = "private-identity") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val key: SecretKey

    init {
        key = synchronized(identityLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(keyAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        store.getKey(keyAlias, null) as SecretKey
        }
        // Discard unused peer records while preserving the local ADB identity/key.
        synchronized(identityLock) {
            if ((prefs.contains("receivers") || prefs.contains("senderId")) &&
                !prefs.edit().remove("receivers").remove("senderId").commit())
                throw IOException("Cannot remove obsolete ScreenCopy trust records")
        }
    }

    fun get(name: String): String? = synchronized(identityLock) {
        val encoded = prefs.getString(name, null) ?: return@synchronized null
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        if (all.size < 29) throw IOException("Stored identity is corrupt; clear app data and pair again")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
    fun put(name: String, value: String): Unit = synchronized(identityLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val all = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        if (!prefs.edit().putString(name, Base64.encodeToString(all, Base64.NO_WRAP)).commit())
            throw IOException("Cannot persist encrypted identity")
    }
    fun getOrCreate(name: String, create: () -> String): String = synchronized(identityLock) {
        get(name) ?: create().also { put(name, it) }
    }

    companion object {
        private val identityLock = Any()
        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { append(Character.forDigit((it.toInt() ushr 4) and 15, 16)); append(Character.forDigit(it.toInt() and 15, 16)) }
        }
    }
}
