package com.musicone.source.spotify

import com.musicone.source.sdk.SourceCapability
import com.musicone.source.spotify.internal.SpotifyPlaybackPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaybackBoundaryTest {
    @Test
    fun metadataOnlyTracksAreNeverMarkedPlayable() {
        val access = SpotifyPlaybackPolicy.metadataOnlyAccess()

        assertFalse(access.playable)
        assertTrue(access.reason.orEmpty().isNotBlank())
    }

    @Test
    fun spotifyDoesNotAdvertisePlaybackOrLyricsCapabilities() {
        val entry = SpotifySourceEntry()

        assertFalse(SourceCapability.PLAYBACK in entry.descriptor.capabilities)
        assertFalse(SourceCapability.LYRICS in entry.descriptor.capabilities)
        assertFalse(SourceCapability.PLAYBACK in entry.implementedCapabilities)
        assertFalse(SourceCapability.LYRICS in entry.implementedCapabilities)
    }
}
