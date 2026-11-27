package com.musicone.source.spotify

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import com.musicone.source.spotify.internal.SpotifyAuthentication
import com.musicone.source.spotify.internal.SpotifyTokenStore
import com.musicone.source.spotify.internal.SpotifySearch

/**
 * Spotify 主音源入口。
 * 当前声明账号认证与目录搜索；尚未声明歌单资料库、歌词或音频播放能力。
 */
class SpotifySourceEntry : SourceEntry {
    override val descriptor = SourceDescriptor(
        id = SPOTIFY_SOURCE_ID,
        name = "Spotify",
        kind = SourceKind.MAIN,
        versionCode = 1,
        versionName = "0.1.0",
        capabilities = setOf(SourceCapability.ACCOUNT, SourceCapability.SEARCH),
        useCustomPages = false,
        requiresLogin = true
    )

    override val implementedCapabilities: Set<SourceCapability> = descriptor.capabilities
    private lateinit var auth: SpotifyAuthentication
    private lateinit var searchService: SpotifySearch
    private var registration: AutoCloseable? = null

    override val authentication: SourceAuthentication
        get() = auth

    override val search: SourceSearch
        get() = searchService

    override fun initialize(environment: SourceEnvironment) {
        auth = SpotifyAuthentication(
            store = SpotifyTokenStore(environment.context),
            onLoginCompleted = environment.host::exitSource
        )
        searchService = SpotifySearch(auth)
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
