package com.musicone.source.spotify.internal

import com.musicone.source.sdk.*
import com.musicone.source.spotify.SPOTIFY_SOURCE_ID
import org.json.JSONArray

/** Spotify album entity adapter. Artist top tracks are intentionally not exposed (removed by Spotify API changes). */
internal class SpotifyEntities(
    private val authentication: SpotifyAuthentication,
    private val library: SpotifyLibrary
) : SourceEntities {
    override val kinds = setOf("album")

    override suspend fun entity(reference: SourceEntityRef): SourceEntity {
        require(reference.kind == "album") { "Spotify 当前只提供专辑实体详情" }
        val json = authentication.authorizedGet("$API/albums/${reference.id}")
        val id = json.optString("id").ifBlank { reference.id }
        return SourceEntity(
            reference = SourceEntityRef(id, json.optString("name").ifBlank { reference.name }, "album"),
            description = json.optString("album_type"),
            artwork = SourceArtwork(url = json.optJSONArray("images")?.optJSONObject(0)?.optString("url"))
        )
    }

    override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        require(reference.kind == "album") { "Spotify 当前只提供专辑曲目列表" }
        val collection = SourceCollection(
            identity = TrackIdentity(SPOTIFY_SOURCE_ID, null, reference.id),
            stableId = "spotify:album:${reference.id}",
            title = reference.name,
            payload = SourcePayload("spotify.collection", 1, "album")
        )
        return library.tracks(collection, request)
    }

    override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection> {
        require(reference.kind == "album") { "Spotify 当前只提供专辑实体详情" }
        return SourcePage(emptyList(), null)
    }

    companion object { private const val API = "https://api.spotify.com/v1" }
}
