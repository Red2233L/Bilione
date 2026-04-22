package com.android.bilione

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.ui.PlayerView
import com.android.purebilibili.core.store.TokenManager
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(bvid: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(bvid) {
        scope.launch {
            try {
                // ... 获取视频 URL 的代码保持不变 ...
                val videoUrl = "获取到的URL"

                val cookie = "buvid3=${TokenManager.buvid3Cache ?: ""}; SESSDATA=${TokenManager.sessDataCache ?: ""}"
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to "https://www.bilibili.com",
                        "Cookie" to cookie
                    ))

                val newPlayer = ExoPlayer.Builder(context).build()
                val mediaSource = DashMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(videoUrl))

                newPlayer.setMediaSource(mediaSource)
                newPlayer.prepare()
                newPlayer.playWhenReady = true

                player = newPlayer
                isLoading = false
            } catch (e: Exception) {
                errorMessage = e.message
                isLoading = false
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player?.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> player?.playWhenReady = true
                Lifecycle.Event.ON_DESTROY -> player?.release()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val currentPlayer = player
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            errorMessage != null -> Text("播放出错: $errorMessage", modifier = Modifier.align(Alignment.Center))
            else ->AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = currentPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }
    }
}