package com.musicone.source.spotify

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceAuthProgress
import com.musicone.source.sdk.SourceAuthChallenge
import com.musicone.source.sdk.SourceAuthenticationPage
import com.musicone.source.sdk.SourceSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spotify OAuth must use the system browser/custom tab. Embedding accounts.spotify.com
 * in the host WebView can render as a blank page on some Android WebView builds.
 */
internal class SpotifyAuthenticationPage(
    private val authentication: com.musicone.source.spotify.internal.SpotifyAuthentication
) : SourceAuthenticationPage {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var challenge by remember { mutableStateOf<SourceAuthChallenge?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var account by remember { mutableStateOf(authentication.currentAccount()) }

        fun startLogin() {
            scope.launch {
                busy = true
                error = null
                message = "正在准备 Spotify 官方授权页面…"
                try {
                    val next = authentication.begin("spotify_pkce", emptyMap())
                    val url = requireNotNull(next.displayUrl) { "Spotify 未返回授权地址" }
                    challenge = next
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    message = "已尝试打开系统浏览器。请在浏览器中完成授权，再返回 MusicOne。"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    challenge = null
                    error = failure.message ?: "无法打开 Spotify 登录页面"
                    message = null
                } finally {
                    busy = false
                }
            }
        }

        val active = challenge
        LaunchedEffect(active?.id) {
            if (active == null) return@LaunchedEffect
            while (true) {
                delay(900)
                try {
                    val result = authentication.continueAuthentication(active, emptyMap())
                    message = result.message
                    when (result.progress) {
                        SourceAuthProgress.SUCCEEDED -> {
                            account = authentication.currentAccount()
                            challenge = null
                            break
                        }
                        SourceAuthProgress.EXPIRED -> {
                            error = result.message
                            challenge = null
                            break
                        }
                        // No callback yet: keep waiting. The callback activity writes it
                        // to the source's encrypted private data file.
                        SourceAuthProgress.VERIFICATION_REQUIRED,
                        SourceAuthProgress.WAITING,
                        SourceAuthProgress.CONFIRMATION_REQUIRED -> Unit
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Spotify 授权检查失败"
                    challenge = null
                    break
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Spotify", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            if (account.status == SourceSessionStatus.AUTHENTICATED && account.account != null) {
                Text("已登录：${account.account.displayName}", style = MaterialTheme.typography.titleMedium)
                Text("Spotify 账号已连接到 MusicOne。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "使用 Spotify 官方网页完成账号授权。登录页面将在系统浏览器中打开，不再嵌入 MusicOne 的网页容器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
            if (busy || active != null) CircularProgressIndicator()
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::startLogin,
                enabled = !busy && active == null && account.status != SourceSessionStatus.AUTHENTICATED,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (active != null) "等待浏览器授权…" else "使用 Spotify 登录") }
            if (active != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { challenge = null; message = "已停止等待授权。你可以重新开始登录。" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消") }
            }
        }
    }
}
