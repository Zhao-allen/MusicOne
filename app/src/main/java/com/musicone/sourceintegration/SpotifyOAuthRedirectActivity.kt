package com.musicone.sourceintegration

import android.app.Activity
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * Host-owned OAuth redirect receiver.
 *
 * Source packs are DexClassLoader modules, so their own AndroidManifest.xml
 * activities are not installed with the host app. The host receives the
 * redirect and writes it to the Spotify source's private data directory.
 */
class SpotifyOAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri?.scheme == SCHEME && uri.host == HOST) {
            runCatching {
                val directory = File(filesDir, "source-packs/data/$SOURCE_ID").apply { mkdirs() }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val encrypted = cipher.doFinal(uri.toString().toByteArray(Charsets.UTF_8))
                val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
                File(directory, CALLBACK_FILE).outputStream().use {
                    it.write(Base64.encode(packed, Base64.NO_WRAP))
                    it.fd.sync()
                }
            }
        }
        finish()
    }

    private fun key(): javax.crypto.SecretKey {
        val alias = "musicone.spotify.token.key"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val SCHEME = "com.musicone.spotify"
        private const val HOST = "callback"
        private const val SOURCE_ID = "com.musicone.source.spotify"
        private const val CALLBACK_FILE = "oauth_callback.enc"
    }
}
