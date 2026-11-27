package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicSource
import com.musicone.MusicTrack
import com.musicone.source.sdk.SourceCursorRequest
import com.musicone.source.sdk.SourceSearchKind
import com.musicone.source.sdk.SourceTrackDetails
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/**
 * Resolves Spotify catalog metadata to a playable track from another installed
 * MusicOne main source. It never obtains or fabricates Spotify audio URLs.
 */
internal class SpotifyCrossSourceResolver(context: Context) {
    private val appContext = context.applicationContext

    suspend fun findPlayableAlternative(spotifyTrack: MusicTrack): MusicTrack? {
        require(spotifyTrack.source.id == SPOTIFY_SOURCE_ID) { "仅支持 Spotify 曲目匹配" }
        val packages = SourcePackageController.get(appContext)
        packages.awaitStartup()

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
                if (access?.playable == true) return details.toMusicTrack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed availability probe is not evidence that a track is playable.
            }
        }
        return null
    }

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
        private const val SPOTIFY_SOURCE_ID = "com.musicone.source.spotify"
        private const val TITLE_WEIGHT = 0.72
        private const val ARTIST_WEIGHT = 0.28
        private const val MIN_ARTIST_SCORE = 0.35
        private const val MIN_MATCH_SCORE = 0.82
        private const val MAX_AVAILABILITY_CHECKS = 3
    }
}
