package com.musicone.source.spotify.internal

import android.net.Uri
import com.musicone.source.sdk.*
import com.musicone.source.spotify.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal class SpotifyAuthentication(
    private val store: SpotifyTokenStore,
    private val onLoginCompleted: () -> Unit
) : SourceAuthentication {
    private data class Pending(val verifier: String, val state: String)
    private val pending = HashMap<String, Pending>()

    override val methods = listOf(SourceLoginMethod("spotify_pkce", "使用 Spotify 账号登录"))

    override fun currentAccount(): SourceAccountState {
        val tokens = store.read() ?: return SourceAccountState(SourceSessionStatus.GUEST, null, 0)
        if (tokens.expiresAtMs <= System.currentTimeMillis() && tokens.refreshToken.isNullOrBlank()) {
            return SourceAccountState(SourceSessionStatus.EXPIRED, null, tokens.expiresAtMs)
        }
        return SourceAccountState(
            if (tokens.expiresAtMs > System.currentTimeMillis()) SourceSessionStatus.AUTHENTICATED else SourceSessionStatus.EXPIRED,
            tokens.accountId.takeIf { it.isNotBlank() }?.let {
                SourceAccount(it, tokens.displayName.ifBlank { it }, tokens.avatarUrl)
            },
            tokens.expiresAtMs
        )
    }

    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge {
        require(methodId == "spotify_pkce") { "不支持的 Spotify 登录方式" }
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        require(clientId.isNotBlank()) { "尚未配置 Spotify Client ID，请在本机 Gradle 属性 spotifyClientId 中设置" }
        val restored = store.readPending()
        val verifier = restored?.second ?: randomString(64)
        val state = restored?.third ?: randomString(32)
        val challengeId = restored?.first ?: UUID.randomUUID().toString()
        pending[challengeId] = Pending(verifier, state)
        if (restored == null) store.savePending(challengeId, verifier, state)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val scopes = listOf("user-read-private", "user-read-email", "user-library-read", "user-library-modify", "playlist-read-private")
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", scopes.joinToString(" "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build().toString()
        return SourceAuthChallenge(
            id = challengeId,
            kind = "oauth_pkce",
            message = "请在浏览器中登录 Spotify 并授权 MusicOne。授权完成后返回应用。",
            displayUrl = url,
            payload = SourcePayload("spotify-pkce", 1, REDIRECT_URI)
        )
    }

    override suspend fun continueAuthentication(
        challenge: SourceAuthChallenge,
        input: Map<String, String>
    ): SourceAuthResult = withContext(Dispatchers.IO) {
        val state = pending[challenge.id] ?: store.readPending(challenge.id)?.let { Pending(it.first, it.second) }
            ?: return@withContext SourceAuthResult(SourceAuthProgress.EXPIRED, "登录流程已过期，请重新开始")
        val callback = input["callbackUrl"] ?: store.takeCallback().orEmpty()
        val code = input["code"] ?: Uri.parse(callback).getQueryParameter("code")
        val returnedState = input["state"] ?: Uri.parse(callback).getQueryParameter("state")
        val error = Uri.parse(callback).getQueryParameter("error")
        if (error != null) {
            pending.remove(challenge.id)
            store.clearPending()
            return@withContext SourceAuthResult(SourceAuthProgress.EXPIRED, "Spotify 拒绝授权：$error")
        }
        if (code.isNullOrBlank() || returnedState.isNullOrBlank()) {
            return@withContext SourceAuthResult(
                SourceAuthProgress.VERIFICATION_REQUIRED,
                "请粘贴 Spotify 授权回调完整地址，或填写 code 和 state"
            )
        }
        if (returnedState != state.state) {
            pending.remove(challenge.id)
            store.clearPending()
            return@withContext SourceAuthResult(SourceAuthProgress.EXPIRED, "OAuth state 校验失败，已拒绝此次登录")
        }
        try {
            val tokenJson = postForm("https://accounts.spotify.com/api/token", mapOf(
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to state.verifier
            ))
            val access = tokenJson.getString("access_token")
            val refresh = tokenJson.optString("refresh_token").takeIf { it.isNotBlank() }
            val expires = System.currentTimeMillis() + tokenJson.optLong("expires_in", 3600) * 1000L
            val profile = getJson("https://api.spotify.com/v1/me", access)
            val images = profile.optJSONArray("images")
            val avatar = if (images != null && images.length() > 0) images.optJSONObject(0)?.optString("url") else null
            val accountId = profile.optString("id")
            val displayName = profile.optString("display_name").ifBlank { accountId }
            store.write(SpotifyTokens(access, refresh, expires, tokenJson.optString("scope"), accountId, displayName, avatar))
            pending.remove(challenge.id)
            store.clearPending()
            onLoginCompleted()
            SourceAuthResult(
                SourceAuthProgress.SUCCEEDED,
                "Spotify 登录成功：$displayName",
                account = SourceAccount(accountId, displayName, avatar)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            pending.remove(challenge.id)
            store.clearPending()
            SourceAuthResult(SourceAuthProgress.EXPIRED, "Spotify 登录失败：${error.message ?: "网络或授权错误"}")
        }
    }

    override suspend fun refreshAccount(): SourceAccountState = withContext(Dispatchers.IO) {
        var tokens = store.read() ?: return@withContext SourceAccountState(SourceSessionStatus.GUEST, null, 0)
        if (tokens.expiresAtMs <= System.currentTimeMillis() + 60_000L) {
            val refresh = tokens.refreshToken
                ?: return@withContext SourceAccountState(SourceSessionStatus.EXPIRED, null, tokens.expiresAtMs)
            val json = postForm("https://accounts.spotify.com/api/token", mapOf(
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh
            ))
            tokens = tokens.copy(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
                expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L,
                scope = json.optString("scope").ifBlank { tokens.scope }
            )
            store.write(tokens)
        }
        val profile = getJson("https://api.spotify.com/v1/me", tokens.accessToken)
        val id = profile.optString("id").ifBlank { tokens.accountId }
        val name = profile.optString("display_name").ifBlank { tokens.displayName.ifBlank { id } }
        val images = profile.optJSONArray("images")
        val avatar = if (images != null && images.length() > 0) images.optJSONObject(0)?.optString("url") else tokens.avatarUrl
        tokens = tokens.copy(accountId = id, displayName = name, avatarUrl = avatar)
        store.write(tokens)
        SourceAccountState(SourceSessionStatus.AUTHENTICATED, SourceAccount(id, name, avatar), tokens.expiresAtMs)
    }

    internal fun cachedAccountId(): String = store.read()?.accountId.orEmpty()

    internal suspend fun authorizedGet(endpoint: String): JSONObject {
        val state = refreshAccount()
        check(state.status == SourceSessionStatus.AUTHENTICATED) { "Spotify 계정에 다시 로그인해야 합니다" }
        val token = store.read()?.accessToken ?: error("Spotify access token missing")
        return withContext(Dispatchers.IO) { getJson(endpoint, token) }
    }

    internal suspend fun authorizedGetArray(endpoint: String): org.json.JSONArray {
        val state = refreshAccount()
        check(state.status == SourceSessionStatus.AUTHENTICATED) { "Spotify 계정에 다시 로그인해야 합니다" }
        val token = store.read()?.accessToken ?: error("Spotify access token missing")
        return withContext(Dispatchers.IO) { org.json.JSONArray(requestText("GET", endpoint, token)) }
    }

    internal suspend fun authorizedRequest(method: String, endpoint: String): JSONObject {
        require(method in setOf("GET", "POST", "PUT", "DELETE")) { "不支持的 Spotify 请求方法" }
        val state = refreshAccount()
        check(state.status == SourceSessionStatus.AUTHENTICATED) { "Spotify 계정에 다시 로그인해야 합니다" }
        val token = store.read()?.accessToken ?: error("Spotify access token missing")
        return withContext(Dispatchers.IO) { requestJson(method, endpoint, token) }
    }

    override suspend fun logout() { store.clear(); pending.clear() }

    override fun exportSession(): SourceSessionSnapshot? {
        val tokens = store.read() ?: return null
        // SDK 会话快照不暴露 token 明文；由本音源自己的加密存储负责持久化。
        return SourceSessionSnapshot("spotify-keystore", 1, mapOf("accountId" to tokens.accountId))
    }

    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt {
        throw UnsupportedOperationException("Spotify 登录令牌不能从其他音源迁移")
    }

    private fun randomString(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

    private fun postForm(endpoint: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status: ${runCatching { JSONObject(text).optString("error_description") }.getOrDefault(text)}")
            JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun getJson(endpoint: String, token: String): JSONObject = requestJson("GET", endpoint, token)

    private fun requestJson(method: String, endpoint: String, token: String): JSONObject {
        val text = requestText(method, endpoint, token)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestText(method: String, endpoint: String, token: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("error_description") }.getOrDefault(text)
                throw IllegalStateException("HTTP $status: ${message.ifBlank { text }}")
            }
            text
        } finally { connection.disconnect() }
    }

    companion object {
        const val REDIRECT_URI = "com.musicone.spotify://callback"
    }
}
