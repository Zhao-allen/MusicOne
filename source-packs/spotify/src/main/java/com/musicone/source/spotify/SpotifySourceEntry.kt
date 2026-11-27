package com.musicone.source.spotify

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import com.musicone.source.spotify.internal.SpotifyAuthentication
import com.musicone.source.spotify.internal.SpotifyTokenStore
import com.musicone.source.spotify.internal.SpotifySearch
import com.musicone.source.spotify.internal.SpotifyLibrary

/** Spotify 主音源入口：账号、搜索、歌单/专辑目录与收藏资料库。 */
class SpotifySourceEntry : SourceEntry {
    override val descriptor = SourceDescriptor(
        id = SPOTIFY_SOURCE_ID,
        name = "Spotify",
        kind = SourceKind.MAIN,
        versionCode = 1,
        versionName = "0.1.0",
        capabilities = setOf(SourceCapability.ACCOUNT, SourceCapability.SEARCH, SourceCapability.PLAYLIST, SourceCapability.ALBUM),
        useCustomPages = false,
        requiresLogin = true
    )

    override val implementedCapabilities: Set<SourceCapability> = descriptor.capabilities
    private lateinit var auth: SpotifyAuthentication
    private lateinit var searchService: SpotifySearch
    private lateinit var libraryService: SpotifyLibrary
    private var registration: AutoCloseable? = null

    override val authentication: SourceAuthentication
        get() = auth

    override val search: SourceSearch
        get() = searchService

    override val collections: SourceCollections
        get() = libraryService

    override val favorites: SourceFavorites
        get() = libraryService

    override fun initialize(environment: SourceEnvironment) {
        auth = SpotifyAuthentication(
            store = SpotifyTokenStore(environment.context),
            onLoginCompleted = environment.host::exitSource
        )
        searchService = SpotifySearch(auth)
        libraryService = SpotifyLibrary(auth, searchService)
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
