package com.musicone.source.spotify.internal

import android.net.Uri
import com.musicone.source.sdk.*
import com.musicone.source.spotify.SPOTIFY_SOURCE_ID

/** Spotify artist/album metadata adapter. Artist top tracks were removed from the Web API in 2026. */
internal class SpotifyEntities(
    private val authentication: SpotifyAuthentication,
    private val library: SpotifyLibrary,
    private val search: SpotifySearch
) : SourceEntities {
    override val kinds = setOf("album", "artist")

    override suspend fun entity(reference: SourceEntityRef): SourceEntity {
        require(reference.kind in kinds) { "Spotify 不支持该实体类型" }
        val json = authentication.authorizedGet("$API/${reference.kind}/${reference.id}")
        val id = json.optString("id").ifBlank { reference.id }
        val name = json.optString("name").ifBlank { reference.name }
        return SourceEntity(
            reference = SourceEntityRef(id, name, reference.kind),
            description = if (reference.kind == "artist") {
                json.optJSONArray("genres")?.let { genres ->
                    (0 until genres.length()).mapNotNull(genres::optString).joinToString(" · ")
                }.orEmpty()
            } else json.optString("album_type"),
            artwork = SourceArtwork(url = json.optJSONArray("images")?.optJSONObject(0)?.optString("url")),
            attributes = mapOf("spotifyUrl" to json.optJSONObject("external_urls")?.optString("spotify").orEmpty())
        )
    }

    override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        require(reference.kind == "album") {
            "Spotify 已移除艺人热门歌曲接口；请从艺人专辑列表进入专辑曲目"
        }
        val collection = SourceCollection(
            identity = TrackIdentity(SPOTIFY_SOURCE_ID, null, reference.id),
            stableId = "spotify:album:${reference.id}",
            title = reference.name,
            payload = SourcePayload("spotify.collection", 1, "album")
        )
        return library.tracks(collection, request)
    }

    override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection> {
        require(reference.kind == "artist") { "专辑实体没有子专辑列表" }
        val endpoint = request.cursor ?: Uri.parse("$API/artists/${reference.id}/albums").buildUpon()
            .appendQueryParameter("include_groups", "album,single,compilation,appears_on")
            .appendQueryParameter("limit", request.limit.coerceIn(1, 50).toString())
            .build().toString()
        val page = authentication.authorizedGet(endpoint)
        return SourcePage(
            search.parseCollections(page.optJSONArray("items"), "album"),
            page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    companion object { private const val API = "https://api.spotify.com/v1" }
}
