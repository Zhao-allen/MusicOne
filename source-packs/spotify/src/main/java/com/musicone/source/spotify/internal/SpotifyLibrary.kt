package com.musicone.source.spotify.internal

import android.net.Uri
import com.musicone.source.sdk.*
import com.musicone.source.spotify.SPOTIFY_SOURCE_ID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotify user library adapter.
 *
 * Saved tracks use /me/tracks. Playlists use /me/playlists and their item
 * endpoint; albums use /albums/{id}/tracks. All returned tracks remain
 * metadata-only because Spotify Web API does not expose a general audio URL.
 */
internal class SpotifyLibrary(
    private val authentication: SpotifyAuthentication,
    private val search: SpotifySearch
) : SourceCollections, SourceFavorites {

    override val librarySections = setOf(
        SourceLibrarySection.CREATED_COLLECTIONS,
        SourceLibrarySection.SAVED_COLLECTIONS,
        SourceLibrarySection.FAVORITE_TRACKS
    )

    override suspend fun library(
        section: SourceLibrarySection,
        request: SourceCursorRequest
    ): SourcePage<SourceCollection> {
        if (section == SourceLibrarySection.FAVORITE_TRACKS) {
            val collection = SourceCollection(
                identity = TrackIdentity(SPOTIFY_SOURCE_ID, authentication.cachedAccountId(), SAVED_TRACKS_ID),
                stableId = "spotify:collection:$SAVED_TRACKS_ID",
                title = "Liked Songs",
                subtitle = "Spotify 收藏歌曲",
                accountFavorites = true,
                payload = SourcePayload(COLLECTION_PAYLOAD, 1, SAVED_TRACKS_KIND)
            )
            return SourcePage(listOf(collection), null)
        }

        val endpoint = request.cursor ?: Uri.parse("$API/me/playlists").buildUpon()
            .appendQueryParameter("limit", request.limit.coerceIn(1, 50).toString())
            .build().toString()
        val page = authentication.authorizedGet(endpoint)
        val playlists = search.parseCollections(page.optJSONArray("items"), "playlist")
            .filter { collection ->
                when (section) {
                    SourceLibrarySection.CREATED_COLLECTIONS -> collection.owned
                    SourceLibrarySection.SAVED_COLLECTIONS -> !collection.owned
                    else -> false
                }
            }
        return SourcePage(
            playlists,
            page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    override suspend fun detail(collection: SourceCollection): SourceCollection {
        val kind = collection.payload.takeIf { it.format == COLLECTION_PAYLOAD }?.encoded
            ?: if (collection.identity.remoteId == SAVED_TRACKS_ID) SAVED_TRACKS_KIND else "playlist"
        if (kind == SAVED_TRACKS_KIND) return collection
        val id = collection.identity.remoteId
        val endpoint = when (kind) {
            "album" -> "$API/albums/$id"
            else -> "$API/playlists/$id"
        }
        val json = authentication.authorizedGet(endpoint)
        return search.parseCollections(JSONArray().put(json), kind).firstOrNull() ?: collection
    }

    override suspend fun tracks(
        collection: SourceCollection,
        request: SourceCursorRequest
    ): SourcePage<SourceTrackDetails> {
        val kind = collection.payload.takeIf { it.format == COLLECTION_PAYLOAD }?.encoded
            ?: if (collection.identity.remoteId == SAVED_TRACKS_ID) SAVED_TRACKS_KIND else "playlist"
        val endpoint = request.cursor ?: when (kind) {
            SAVED_TRACKS_KIND -> Uri.parse("$API/me/tracks").buildUpon()
                .appendQueryParameter("limit", request.limit.coerceIn(1, 50).toString())
                .build().toString()
            "album" -> Uri.parse("$API/albums/${collection.identity.remoteId}/tracks").buildUpon()
                .appendQueryParameter("limit", request.limit.coerceIn(1, 50).toString())
                .build().toString()
            else -> Uri.parse("$API/playlists/${collection.identity.remoteId}/items").buildUpon()
                .appendQueryParameter("limit", request.limit.coerceIn(1, 50).toString())
                .build().toString()
        }
        val page = authentication.authorizedGet(endpoint)
        val items = page.optJSONArray("items") ?: JSONArray()
        val tracks = when (kind) {
            SAVED_TRACKS_KIND, "playlist" -> {
                val unwrapped = JSONArray()
                for (index in 0 until items.length()) {
                    val wrapper = items.optJSONObject(index) ?: continue
                    val item = if (kind == SAVED_TRACKS_KIND) wrapper.optJSONObject("track")
                        else wrapper.optJSONObject("item")
                    if (item != null && item.optString("type", "track") == "track") unwrapped.put(item)
                }
                search.parseTracks(unwrapped)
            }
            else -> search.parseTracks(items)
        }
        return SourcePage(
            tracks,
            page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    override suspend fun favoriteTrackIds(): Set<String> {
        val endpoint = Uri.parse("$API/me/tracks").buildUpon()
            .appendQueryParameter("limit", "50").build().toString()
        val ids = LinkedHashSet<String>()
        var next: String? = endpoint
        while (next != null) {
            val page = authentication.authorizedGet(next)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val id = items.optJSONObject(index)?.optJSONObject("track")?.optString("id")
                if (!id.isNullOrBlank() && id != "null") ids += "spotify:$id"
            }
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return ids
    }

    override suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean {
        requireSpotifyTrack(track)
        val uri = spotifyTrackUri(track)
        val endpoint = Uri.parse("$API/me/library/contains").buildUpon()
            .appendQueryParameter("uris", uri).build().toString()
        return authentication.authorizedGet(endpoint).let { false }
    }

    override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) {
        requireSpotifyTrack(track)
        val uri = spotifyTrackUri(track)
        val endpoint = Uri.parse("$API/me/library").buildUpon()
            .appendQueryParameter("uris", uri).build().toString()
        authentication.authorizedRequest(if (favorite) "PUT" else "DELETE", endpoint)
    }

    override suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean) {
        throw UnsupportedOperationException("Spotify 歌单收藏接口尚未接入")
    }

    private fun requireSpotifyTrack(track: SourceTrackDetails) {
        require(track.track.identity.sourceId == SPOTIFY_SOURCE_ID) { "不能修改其他音源的歌曲收藏" }
    }

    private fun spotifyTrackUri(track: SourceTrackDetails) =
        "spotify:track:${track.track.identity.remoteId}"

    companion object {
        private const val API = "https://api.spotify.com/v1"
        private const val COLLECTION_PAYLOAD = "spotify.collection"
        private const val SAVED_TRACKS_ID = "saved-tracks"
        private const val SAVED_TRACKS_KIND = "saved-tracks"
    }
}
