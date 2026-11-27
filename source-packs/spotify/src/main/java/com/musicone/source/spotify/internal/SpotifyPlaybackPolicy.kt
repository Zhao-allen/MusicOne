package com.musicone.source.spotify.internal

import com.musicone.source.sdk.TrackAccessInfo

/**
 * Spotify Web API supplies catalog metadata, not a media URL that MusicOne's
 * Media3 pipeline can consume. Keep this policy centralized so catalog/search
 * results can never accidentally become playable without a separately
 * authorized playback implementation.
 */
internal object SpotifyPlaybackPolicy {
    const val UNAVAILABLE_REASON =
        "Spotify Web API 不提供可供 MusicOne 直接播放的音频流"

    fun metadataOnlyAccess(): TrackAccessInfo =
        TrackAccessInfo(playable = false, reason = UNAVAILABLE_REASON)
}
