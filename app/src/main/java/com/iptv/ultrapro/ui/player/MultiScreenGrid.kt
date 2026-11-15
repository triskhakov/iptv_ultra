package com.iptv.ultrapro.ui.player

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.iptv.ultrapro.data.settings.AppSettings
import com.iptv.ultrapro.ui.PlaybackRequest

@Composable
fun MultiScreenOverlay(
    items: List<PlaybackRequest>,
    settings: AppSettings,
    onCloseAll: () -> Unit,
    onRemoveChannel: (String) -> Unit,
    onExpandFullscreen: (PlaybackRequest) -> Unit
) {
    var activeAudioId by remember(items) { mutableStateOf(items.firstOrNull()?.channel?.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090C10))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Мультискрин (${items.size}/4) — Нажмите на экран для звука",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onCloseAll) {
                Text("Выйти")
            }
        }

        val rows = items.chunked(2)
        Column(modifier = Modifier.weight(1f)) {
            rows.forEach { rowItems ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    rowItems.forEach { req ->
                        val isAudioActive = req.channel.id == activeAudioId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .border(
                                    width = if (isAudioActive) 2.dp else 1.dp,
                                    color = if (isAudioActive) Color(0xFF00E5FF) else Color(0xFF30363D)
                                )
                                .clickable { activeAudioId = req.channel.id }
                        ) {
                            MiniStreamCell(
                                req = req,
                                settings = settings,
                                isMuted = !isAudioActive,
                                onRemove = { onRemoveChannel(req.channel.id) },
                                onExpand = { onExpandFullscreen(req) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MiniStreamCell(
    req: PlaybackRequest,
    settings: AppSettings,
    isMuted: Boolean,
    onRemove: () -> Unit,
    onExpand: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(req.resolvedUrl) {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(req.channel.userAgent ?: settings.userAgent)
            .setAllowCrossProtocolRedirects(true)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(req.resolvedUrl)))
                prepare()
                playWhenReady = true
            }
    }

    LaunchedEffect(isMuted) {
        player.volume = if (isMuted) 0f else 1f
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = null,
                tint = if (isMuted) Color.Gray else Color(0xFF00E5FF),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = req.channel.name,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onExpand, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Fullscreen, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
            }
        }
    }
}
