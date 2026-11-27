package com.musicone.source.spotify

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import com.musicone.source.spotify.internal.SpotifyAuthentication
import com.musicone.source.spotify.internal.SpotifyTokenStore

/**
 * Spotify 主音源入口。
 * 当前只声明真实实现的账号认证能力；没有伪造目录或音频播放能力。
 */
class SpotifySourceEntry : SourceEntry {
    override val descriptor = SourceDescriptor(
        id = SPOTIFY_SOURCE_ID,
        name = "Spotify",
        kind = SourceKind.MAIN,
        versionCode = 1,
        versionName = "0.1.0",
        capabilities = setOf(SourceCapability.ACCOUNT),
        useCustomPages = false,
        requiresLogin = true
    )

    override val implementedCapabilities: Set<SourceCapability> = descriptor.capabilities
    private lateinit var auth: SpotifyAuthentication
    private var registration: AutoCloseable? = null

    override val authentication: SourceAuthentication
        get() = auth

    override fun initialize(environment: SourceEnvironment) {
        auth = SpotifyAuthentication(
            store = SpotifyTokenStore(environment.context),
            onLoginCompleted = environment.host::exitSource
        )
        registration = environment.host.registerTask { close() }
    }

    @Composable
    override fun Content(route: SourceRoute) = Unit

    override fun close() {
        val current = registration
        registration = null
        current?.close()
    }
}

const val SPOTIFY_SOURCE_ID = "com.musicone.source.spotify"
