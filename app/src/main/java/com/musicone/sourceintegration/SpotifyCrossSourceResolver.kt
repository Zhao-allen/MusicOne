package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicTrack
import com.musicone.source.sdk.SourceCursorRequest
import com.musicone.source.sdk.SourceSearchKind
import com.musicone.source.sdk.SourceTrackDetails
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/**
 * Resolves Spotify catalog metadata to a playable track from another installed
 * MusicOne main source. It never obtains or fabricates Spotify audio URLs.
 */
internal class SpotifyCrossSourceResolver(context: Context) {
    private val appContext = context.applicationContext
    private val matchCache = LinkedHashMap<String, CachedMatch>(CACHE_SIZE, 0.75f, true)

    suspend fun resolvePlayableAlternatives(tracks: List<MusicTrack>): List<MusicTrack> {
        val resolved = ArrayList<MusicTrack>(tracks.size)
        tracks.forEach { track ->
            currentCoroutineContext().ensureActive()
            if (track.source.id == SPOTIFY_SOURCE_ID) {
                findPlayableAlternative(track)?.let(resolved::add)
            } else if (track.playable) resolved += track
        }
        return resolved.distinctBy { it.source.id to it.id }
    }

    suspend fun findPlayableAlternative(spotifyTrack: MusicTrack): MusicTrack? {
        require(spotifyTrack.source.id == SPOTIFY_SOURCE_ID) { "仅支持 Spotify 曲目匹配" }
        val packages = SourcePackageController.get(appContext)
        packages.awaitStartup()

        cached(spotifyTrack.id)?.let { (sourceId, details) ->
            val instance = packages.running(sourceId)
            val playback = instance?.entry?.mediaPlayback
            if (playback != null) {
                try {
                    val access = playback.availability(listOf(details)).firstOrNull()
                    if (access?.playable == true) {
                    synchronized(matchCache) {
                        matchCache[spotifyTrack.id] = CachedMatch(instance.entry.descriptor.id, details, System.currentTimeMillis())
                        while (matchCache.size > CACHE_SIZE) matchCache.entries.iterator().run { if (hasNext()) { next(); remove() } }
                    }
                    return details.copy(access = access).toMusicTrack()
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { }
            }
            synchronized(matchCache) { matchCache.remove(spotifyTrack.id) }
        }

        val query = listOf(spotifyTrack.title, spotifyTrack.artists)
            .filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null

        val candidates = packages.available(com.musicone.source.sdk.SourceKind.MAIN)
            .filter { it.entry.descriptor.id != SPOTIFY_SOURCE_ID }
            .mapNotNull { instance ->
                val search = instance.entry.search ?: return@mapNotNull null
                if (SourceSearchKind.TRACK !in search.kinds) return@mapNotNull null
                try {
                    search.search(query, SourceSearchKind.TRACK, SourceCursorRequest(limit = 10))
                        .tracks.map { details -> details to instance }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }.flatten()

        if (candidates.isEmpty()) return null
        val ranked = candidates.mapNotNull { (details, instance) ->
            val score = matchScore(spotifyTrack, details)
            if (score < MIN_MATCH_SCORE) return@mapNotNull null
            Triple(score, details, instance)
        }.sortedByDescending { it.first }

        // Verify the best few candidates against the target source's own
        // availability endpoint before handing one to the host Media3 player.
        for ((_, details, instance) in ranked.take(MAX_AVAILABILITY_CHECKS)) {
            val playback = instance.entry.mediaPlayback ?: continue
            try {
                val access = playback.availability(listOf(details)).firstOrNull()
                if (access?.playable == true) return details.copy(access = access).toMusicTrack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed availability probe is not evidence that a track is playable.
            }
        }
        return null
    }

    private fun cached(key: String): Pair<String, SourceTrackDetails>? = synchronized(matchCache) {
        val match = matchCache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - match.savedAtMs > CACHE_TTL_MS) {
            matchCache.remove(key)
            null
        } else match.sourceId to match.details
    }

    private data class CachedMatch(
        val sourceId: String,
        val details: SourceTrackDetails,
        val savedAtMs: Long,
    )

    private fun matchScore(source: MusicTrack, candidate: SourceTrackDetails): Double {
        val titleA = normalize(source.title)
        val titleB = normalize(candidate.track.title)
        if (titleA.isBlank() || titleB.isBlank()) return 0.0
        val titleScore = if (titleA == titleB) 1.0 else tokenDice(titleA, titleB)
        val artistA = normalize(source.artists)
        val artistB = normalize(candidate.artistsText.ifBlank { candidate.track.artists.joinToString(" ") })
        val artistScore = when {
            artistA.isBlank() || artistB.isBlank() -> 0.0
            artistA == artistB -> 1.0
            else -> tokenDice(artistA, artistB)
        }
        // Artist agreement is mandatory unless title is an exact match and the
        // source result has no artist metadata.
        if (artistScore < MIN_ARTIST_SCORE && !(titleScore == 1.0 && artistB.isBlank())) return 0.0
        return titleScore * TITLE_WEIGHT + artistScore * ARTIST_WEIGHT
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*\\]|【[^】]*】"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun tokenDice(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val left = a.windowed(2).toSet()
        val right = b.windowed(2).toSet()
        val overlap = left.intersect(right).size
        return 2.0 * overlap / (left.size + right.size).coerceAtLeast(1)
    }

    companion object {
        @Volatile private var shared: SpotifyCrossSourceResolver? = null

        fun get(context: Context): SpotifyCrossSourceResolver = shared ?: synchronized(this) {
            shared ?: SpotifyCrossSourceResolver(context.applicationContext).also { shared = it }
        }

        private const val SPOTIFY_SOURCE_ID = "com.musicone.source.spotify"
        private const val TITLE_WEIGHT = 0.72
        private const val ARTIST_WEIGHT = 0.28
        private const val MIN_ARTIST_SCORE = 0.35
        private const val MIN_MATCH_SCORE = 0.82
        private const val MAX_AVAILABILITY_CHECKS = 3
        private const val CACHE_SIZE = 300
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
