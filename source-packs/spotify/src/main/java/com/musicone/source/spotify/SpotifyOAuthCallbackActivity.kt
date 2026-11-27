package com.musicone.source.spotify

import android.app.Activity
import android.os.Bundle
import com.musicone.source.spotify.internal.SpotifyTokenStore

/** Receives only the registered Spotify OAuth redirect and hands it to the source auth flow. */
class SpotifyOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.takeIf { it.startsWith("com.musicone.spotify://callback") }?.let {
            SpotifyTokenStore(applicationContext).saveCallback(it)
        }
        finish()
    }
}
