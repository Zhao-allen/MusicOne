package com.musicone.source.spotify.internal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val scope: String,
    val accountId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null
)

internal class SpotifyTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("spotify-auth-v1", Context.MODE_PRIVATE)
    private val alias = "musicone.spotify.token.key"
    private val callbackFile = File(context.filesDir, "oauth_callback.enc")

    @Synchronized fun read(): SpotifyTokens? = runCatching {
        val packed = preferences.getString("tokens", null) ?: return null
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        val ivSize = bytes[0].toInt()
        require(ivSize in 12..16)
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        SpotifyTokens(
            accessToken = json.getString("access"),
            refreshToken = json.optString("refresh").takeIf { it.isNotBlank() },
            expiresAtMs = json.getLong("expiresAt"),
            scope = json.optString("scope"),
            accountId = json.optString("accountId"),
            displayName = json.optString("displayName"),
            avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    @Synchronized fun write(tokens: SpotifyTokens) {
        val json = JSONObject().apply {
            put("access", tokens.accessToken)
            put("refresh", tokens.refreshToken ?: "")
            put("expiresAt", tokens.expiresAtMs)
            put("scope", tokens.scope)
            put("accountId", tokens.accountId)
            put("displayName", tokens.displayName)
            put("avatarUrl", tokens.avatarUrl ?: "")
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(json)
        val packed = byteArrayOf(iv.size.toByte()) + iv + encrypted
        check(preferences.edit().putString("tokens", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "无法持久化 Spotify 登录凭据"
        }
    }

    @Synchronized fun saveCallback(uri: String) {
        callbackFile.parentFile?.mkdirs()
        callbackFile.writeText(encryptString(uri), Charsets.UTF_8)
    }

    @Synchronized fun takeCallback(): String? {
        if (!callbackFile.isFile) return null
        val value = callbackFile.readText(Charsets.UTF_8)
        val decrypted = decryptString(value)
        callbackFile.delete()
        return decrypted
    }

    @Synchronized fun savePending(challengeId: String, verifier: String, state: String) {
        val json = JSONObject().put("id", challengeId).put("verifier", verifier).put("state", state).toString()
        check(preferences.edit().putString("oauth_pending_enc", encryptString(json)).commit())
    }

    @Synchronized fun readPending(challengeId: String): Pair<String, String>? {
        val packed = preferences.getString("oauth_pending_enc", null) ?: return null
        val json = runCatching { JSONObject(decryptString(packed)) }.getOrNull() ?: return null
        if (json.optString("id") != challengeId) return null
        val verifier = json.optString("verifier")
        val state = json.optString("state")
        return if (verifier.isNotBlank() && state.isNotBlank()) verifier to state else null
    }

    @Synchronized fun clearPending() {
        preferences.edit().remove("oauth_pending_enc").commit()
    }

    @Synchronized fun clear() {
        preferences.edit().remove("tokens").remove("oauth_pending_enc").commit()
        callbackFile.delete()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias)
        }
    }

    private fun encryptString(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptString(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val ivSize = bytes[0].toInt()
        require(ivSize in 12..16)
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
