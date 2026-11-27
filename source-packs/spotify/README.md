# MusicOne Spotify 主音源

这是 MusicOne 内置音源包形式的 Spotify 接入工程，采用 `SourceKind.MAIN`，不修改宿主 UI，也不复用 Spotifeur 的登录会话。

## 当前阶段

- 已注册独立 Gradle 模块 `:source-packs:spotify`。
- 已添加 `SpotifySourceEntry` 和 `musicone-source.json`。
- 当前不声明搜索、账号、歌单、歌词或播放能力，避免空实现被宿主误认为可用。
- 当前阶段尚未完成 OAuth、Spotify Web API 数据映射、歌词服务或音频播放适配。

## 接入原则

1. Spotify 账号使用独立 OAuth PKCE 授权和独立令牌存储。
2. Spotify Web API 仅用于其正式允许的账号资料、目录元数据和用户资料库能力。
3. 不把 Spotify Web API 当作音频流接口；不伪造播放 URL。
4. 不实现绕过 Spotify Free/Premium 权限、DRM 或访问控制的逻辑。
5. 只有在获得可用且获授权的音频资源后，才向 MusicOne 声明 `PLAYBACK`。

## 构建

在仓库根目录执行：

```bash
./gradlew :source-packs:spotify:assembleDebug
./gradlew :source-packs:spotify:packageDebugSource
```

产物位于 `source-packs/spotify/build/outputs/source-packs/debug/spotify-0.1.0.mscm`。
