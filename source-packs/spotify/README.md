# MusicOne Spotify 主音源

这是 MusicOne 的独立 `.mscm` 主音源包，使用 Spotify 官方 OAuth 2.0 Authorization Code with PKCE，不复用 Spotifeur 会话，也不修改宿主主程序 UI。

## 已实现

- 独立音源模块注册到 Gradle 和宿主音源包构建流程。
- Spotify OAuth PKCE：随机 verifier、S256 challenge、state 校验、授权码兑换、刷新令牌。
- 使用 Android Keystore AES-GCM 加密保存访问令牌、刷新令牌、OAuth 回调地址和 PKCE verifier/state，不在日志中输出凭据。
- 独立 OAuth 回调 URI：`com.musicone.spotify://callback`；由 MusicOne 宿主 Manifest 接收，再写入 Spotify 音源私有目录，适配 DexClassLoader 音源包运行方式。
- 登录后读取 Spotify 当前用户资料。
- 接入 Spotify 搜索：歌曲、专辑、歌手、歌单；分页使用 API 返回的 next URL，并遵守 Development Mode 搜索每类最多 10 条的限制。
- 接入 Spotify 用户资料库：我创建的歌单、我关注/保存的歌单、Liked Songs 收藏歌曲入口。
- 接入收藏歌曲读取、单曲收藏状态查询，以及收藏/取消收藏（使用新版 `/me/library` 接口，并申请 `user-library-modify` scope）。
- 接入歌单详情和曲目分页；支持专辑详情、专辑曲目分页、艺人资料和艺人专辑列表。
- 当前声明 `ACCOUNT`、`SEARCH`、`PLAYLIST`、`ALBUM`；播放和歌词能力仍未声明。

## 本机配置

Spotify Client ID 不是 Client Secret，可以作为公开客户端标识配置；**不要把 Client Secret 放进 Android 包或提交到仓库**。

在本机 Gradle 用户属性中配置（不要提交到 Git）：

```properties
spotifyClientId=你的Spotify_Client_ID
```

例如放在用户目录的 `~/.gradle/gradle.properties`。Windows 通常为 `%USERPROFILE%\.gradle\gradle.properties`。

在 Spotify Developer Dashboard 的应用设置中添加完全一致的 Redirect URI：

```text
com.musicone.spotify://callback
```

然后构建：

```bash
./gradlew :source-packs:spotify:assembleDebug
./gradlew :source-packs:spotify:packageDebugSource
```

## Spotify 平台限制（重要）

Spotify 当前 Development Mode 对应用所有者有有效 Premium 要求，并限制可授权测试用户。没有满足开发者账号资格时，OAuth/API 联调会被 Spotify 拒绝；这不是 MusicOne 代码能绕过的限制。

Spotify Web API 是目录和用户数据 API，不提供可直接交给 MusicOne Media3 播放器的通用音频流 URL。因此本项目不会伪造播放地址，也不会实现绕过 Free/Premium、DRM 或访问控制的逻辑。只有获得 Spotify 明确授权且可由宿主播放器合法消费的音频资源后，才会声明 `PLAYBACK`。

## 后续计划

1. 完成搜索结果已映射；继续补齐歌手、专辑和歌单详情。
2. 接入用户资料库和收藏。
3. 对歌词采用独立、合法授权的歌词来源；Spotify Web API 不提供歌词。
4. 单独评估可授权的播放方案。
