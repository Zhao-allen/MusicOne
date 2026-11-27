package com.musicone.source.spotify.internal

import android.net.Uri
import com.musicone.source.sdk.*
import com.musicone.source.spotify.SPOTIFY_SOURCE_ID
import org.json.JSONArray

/** Spotify Web API search adapter. Uses the current 10-result maximum and next URLs. */
internal class SpotifySearch(private val authentication: SpotifyAuthentication) : SourceSearch {
    override val kinds = setOf(
        SourceSearchKind.TRACK,
        SourceSearchKind.COLLECTION,
        SourceSearchKind.ARTIST,
        SourceSearchKind.ALBUM
    )

    override suspend fun search(
        query: String,
        kind: SourceSearchKind,
        request: SourceCursorRequest
    ): SourceSearchResult {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        require(kind in kinds) { "Spotify 暂不支持此搜索类型" }
        val types = when (kind) {
            SourceSearchKind.TRACK -> "track"
            SourceSearchKind.COLLECTION -> "playlist"
            SourceSearchKind.ARTIST -> "artist"
            SourceSearchKind.ALBUM -> "album"
            else -> error("Unsupported search kind")
        }
        val endpoint = request.cursor ?: Uri.parse("https://api.spotify.com/v1/search").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", types)
            .appendQueryParameter("limit", request.limit.coerceIn(1, 10).toString())
            .build().toString()
        val root = authentication.authorizedGet(endpoint)
        val tracks = ArrayList<SourceTrackDetails>()
        val collections = ArrayList<SourceCollection>()
        val entities = ArrayList<SourceEntity>()
        var next: String? = null

        root.optJSONObject("tracks")?.let { page ->
            tracks += parseTracks(page.optJSONArray("items"))
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" } ?: next
        }
        root.optJSONObject("albums")?.let { page ->
            collections += parseCollections(page.optJSONArray("items"), "album")
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" } ?: next
        }
        root.optJSONObject("playlists")?.let { page ->
            collections += parseCollections(page.optJSONArray("items"), "playlist")
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" } ?: next
        }
        root.optJSONObject("artists")?.let { page ->
            entities += parseArtists(page.optJSONArray("items"))
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" } ?: next
        }
        return SourceSearchResult(tracks = tracks, collections = collections, entities = entities, nextCursor = next)
    }

    internal fun parseTracks(items: JSONArray?): List<SourceTrackDetails> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            if (item.optString("id").isBlank()) return@mapNotNull null
            val id = item.getString("id")
            val artistsJson = item.optJSONArray("artists")
            val artists = (0 until (artistsJson?.length() ?: 0)).mapNotNull { artistIndex ->
                artistsJson?.optJSONObject(artistIndex)?.let { artist ->
                    SourceEntityRef(artist.optString("id"), artist.optString("name"), "artist")
                }
            }
            val album = item.optJSONObject("album")
            val albumId = album?.optString("id").orEmpty()
            val albumRef = album?.takeIf { albumId.isNotBlank() }?.let { SourceEntityRef(albumId, it.optString("name"), "album") }
            val track = SourceTrack(
                identity = TrackIdentity(SPOTIFY_SOURCE_ID, null, id),
                title = item.optString("name"),
                artists = artists.map { it.name },
                artworkUrl = firstImage(album?.optJSONArray("images"))
            )
            SourceTrackDetails(
                track = track,
                stableId = "spotify:$id",
                album = album?.optString("name").orEmpty(),
                durationMs = item.optLong("duration_ms"),
                artwork = SourceArtwork(url = track.artworkUrl),
                artists = artists,
                albumRef = albumRef,
                access = SpotifyPlaybackPolicy.metadataOnlyAccess()
            )
        }
    }

    internal fun parseCollections(items: JSONArray?, kind: String): List<SourceCollection> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val count = if (kind == "album") item.optInt("total_tracks").takeIf { item.has("total_tracks") }
                else item.optJSONObject("items")?.optInt("total")
            SourceCollection(
                identity = TrackIdentity(SPOTIFY_SOURCE_ID, null, id),
                stableId = "spotify:$kind:$id",
                title = item.optString("name"),
                subtitle = if (kind == "album") item.optString("album_type") else item.optJSONObject("owner")?.optString("display_name").orEmpty(),
                description = item.optString("description"),
                count = count,
                artwork = SourceArtwork(url = firstImage(item.optJSONArray("images"))),
                owned = item.optJSONObject("owner")?.optString("id") == authentication.cachedAccountId(),
                payload = SourcePayload("spotify.collection", 1, kind)
            )
        }
    }

    private fun parseArtists(items: JSONArray?): List<SourceEntity> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            SourceEntity(
                reference = SourceEntityRef(id, item.optString("name"), "artist"),
                artwork = SourceArtwork(url = firstImage(item.optJSONArray("images"))),
                attributes = mapOf("spotifyUrl" to item.optJSONObject("external_urls")?.optString("spotify").orEmpty())
            )
        }
    }

    private fun firstImage(images: JSONArray?): String? =
        images?.optJSONObject(0)?.optString("url")?.takeIf { !it.isNullOrBlank() && it != "null" }
}
