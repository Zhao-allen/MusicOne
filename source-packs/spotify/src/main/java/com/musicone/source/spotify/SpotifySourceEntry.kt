package com.musicone.source.spotify

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*

/**
 * Spotify 主音源入口。
 *
 * 本阶段只注册独立音源包骨架，不声明尚未实现的搜索、账号或播放能力。
 * 后续服务将按 MusicOne SDK 契约逐项接入；Spotify Web API 不提供可直接交给
 * MusicOne Media3 播放器的通用音频流地址，因此在播放后端完成授权验证前绝不声明 PLAYBACK。
 */
class SpotifySourceEntry : SourceEntry {
    override val descriptor = SourceDescriptor(
        id = SPOTIFY_SOURCE_ID,
        name = "Spotify",
        kind = SourceKind.MAIN,
        versionCode = 1,
        versionName = "0.1.0",
        capabilities = emptySet(),
        useCustomPages = false,
        requiresLogin = false
    )

    override val implementedCapabilities: Set<SourceCapability> = emptySet()

    override fun initialize(environment: SourceEnvironment) = Unit

    @Composable
    override fun Content(route: SourceRoute) = Unit

    override fun close() = Unit
}

const val SPOTIFY_SOURCE_ID = "com.musicone.source.spotify"
