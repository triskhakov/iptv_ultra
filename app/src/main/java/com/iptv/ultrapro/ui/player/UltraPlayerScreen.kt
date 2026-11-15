package com.iptv.ultrapro.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.ultrapro.data.db.EpgProgramEntity
import com.iptv.ultrapro.data.settings.AppSettings
import com.iptv.ultrapro.ui.PlaybackRequest
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun UltraPlayerScreen(
    playback: PlaybackRequest,
    settings: AppSettings,
    schedule: List<EpgProgramEntity>,
    onClose: () -> Unit,
    onNextChannel: () -> Unit,
    onPrevChannel: () -> Unit,
    onSelectArchiveProgram: (EpgProgramEntity) -> Unit,
    onEnterPip: () -> Unit,
    onAddToMultiScreen: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var showControls by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var reconnectCount by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var gestureHudText by remember { mutableStateOf<String?>(null) }

    var showTrackDialog by remember { mutableStateOf(false) }
    var showEpgDrawer by remember { mutableStateOf(false) }

    val resizeLabels = mapOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "16:9 (Fit)",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Растянуть",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Зум (Crop)"
    )

    val trackSelector = remember { DefaultTrackSelector(context) }

    val exoPlayer = remember(playback.resolvedUrl) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                settings.bufferMinMs,
                settings.bufferMaxMs,
                2000,
                4000
            )
            .build()

        val headers = mutableMapOf<String, String>()
        playback.channel.referer?.let { headers["Referer"] = it }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(playback.channel.userAgent ?: settings.userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(playback.resolvedUrl)))
                prepare()
                playWhenReady = true
            }
    }

    LaunchedEffect(showControls, showTrackDialog, showEpgDrawer) {
        if (showControls && !showTrackDialog && !showEpgDrawer) {
            delay(5000)
            showControls = false
        }
    }

    LaunchedEffect(gestureHudText) {
        if (gestureHudText != null) {
            delay(1200)
            gestureHudText = null
        }
    }

    LaunchedEffect(reconnectCount) {
        if (reconnectCount > 0 && settings.autoReconnect) {
            statusText = "Буферизация / Переподключение (#$reconnectCount)..."
            delay(settings.reconnectDelaySec * 1000L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        statusText = null
                        reconnectCount = 0
                    }
                    Player.STATE_BUFFERING -> {
                        if (reconnectCount == 0) statusText = "Загрузка потока..."
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (settings.autoReconnect) {
                    reconnectCount += 1
                } else {
                    statusText = "Ошибка воспроизведения: ${error.errorCodeName}"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        gestureHudText = "Экран: ${resizeLabels[resizeMode]}"
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val screenWidth = size.width
                    val isLeftSide = change.position.x < screenWidth / 2f
                    val deltaY = -dragAmount.y / 600f

                    if (isLeftSide && activity != null) {
                        val lp = activity.window.attributes
                        val current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                        val updated = (current + deltaY).coerceIn(0.05f, 1.0f)
                        lp.screenBrightness = updated
                        activity.window.attributes = lp
                        gestureHudText = "Яркость: ${(updated * 100).toInt()}%"
                    } else {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        if (kotlin.math.abs(dragAmount.y) > 12f) {
                            val step = if (dragAmount.y < 0) 1 else -1
                            val nextVol = (curVol + step).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                            val pct = ((nextVol.toFloat() / maxVol) * 100).toInt()
                            gestureHudText = "Громкость: $pct%"
                        }
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        if (statusText != null) {
            Surface(
                color = Color(0xCC0D1117),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFF00E5FF),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(statusText!!, color = Color.White, fontSize = 14.sp)
                }
            }
        }

        if (gestureHudText != null) {
            Surface(
                color = Color(0xDD000000),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
            ) {
                Text(
                    text = gestureHudText!!,
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }

        if (showControls) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent, Color(0xDD000000))
                        )
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playback.channel.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = playback.archiveProgram?.let { "АРХИВ: ${it.title}" }
                            ?: playback.channel.groupTitle
                        Text(
                            text = sub,
                            color = if (playback.archiveProgram != null) Color(0xFFFFAB00) else Color(0xFF00E5FF),
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = onAddToMultiScreen) {
                        Icon(Icons.Default.GridView, "Мультискрин", tint = Color.White)
                    }
                    IconButton(onClick = onEnterPip) {
                        Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = Color.White)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(onClick = onPrevChannel) {
                        Icon(Icons.Default.SkipPrevious, "Пред. канал")
                    }
                    FilledTonalIconButton(onClick = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }) {
                        Icon(
                            if (exoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Пауза/Пуск"
                        )
                    }
                    FilledTonalIconButton(onClick = onNextChannel) {
                        Icon(Icons.Default.SkipNext, "След. канал")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { showTrackDialog = true },
                        label = { Text("Дорожки / Субтитры") },
                        leadingIcon = { Icon(Icons.Default.Subtitles, null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        label = { Text(resizeLabels[resizeMode] ?: "16:9") },
                        leadingIcon = { Icon(Icons.Default.AspectRatio, null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = { showEpgDrawer = true },
                        label = { Text("Программа / Архив") },
                        leadingIcon = { Icon(Icons.Default.Schedule, null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
        }

        if (showTrackDialog) {
            TrackSelectionDialog(
                player = exoPlayer,
                onDismiss = { showTrackDialog = false }
            )
        }

        if (showEpgDrawer) {
            EpgArchiveDialog(
                channelName = playback.channel.name,
                hasCatchup = playback.channel.catchupDays > 0 || !playback.channel.catchupType.isNullOrBlank(),
                schedule = schedule,
                onSelectProgram = { prog ->
                    showEpgDrawer = false
                    onSelectArchiveProgram(prog)
                },
                onDismiss = { showEpgDrawer = false }
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TrackSelectionDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    val tracks = player.currentTracks
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Аудиодорожки и субтитры") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Звуковые дорожки:", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF)) }
                if (audioGroups.isEmpty()) {
                    item { Text("Дополнительные аудиодорожки не найдены", fontSize = 13.sp) }
                }
                items(audioGroups) { group ->
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val label = "${format.language ?: "Дорожка ${i + 1}"} (${format.sampleMimeType ?: "audio"})"
                        val selected = group.isTrackSelected(i)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        .build()
                                    onDismiss()
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Субтитры:", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                onDismiss()
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Отключить субтитры")
                    }
                }
                items(textGroups) { group ->
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val label = format.language ?: "Субтитры ${i + 1}"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        .build()
                                    onDismiss()
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = group.isTrackSelected(i), onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun EpgArchiveDialog(
    channelName: String,
    hasCatchup: Boolean,
    schedule: List<EpgProgramEntity>,
    onSelectProgram: (EpgProgramEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Программа: $channelName") },
        text = {
            if (schedule.isEmpty()) {
                Text("Для этого канала нет загруженной телепрограммы (EPG). Добавьте ссылку XMLTV в настройках плейлиста.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(schedule) { prog ->
                        val isPast = prog.endTime < now
                        val isLive = prog.startTime <= now && prog.endTime >= now
                        val canPlayArchive = isPast && hasCatchup

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isLive) Color(0xFF1A3A4A) else Color(0xFF1E242B)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canPlayArchive) { onSelectProgram(prog) }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${timeFmt.format(Date(prog.startTime))} - ${timeFmt.format(Date(prog.endTime))}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF00E5FF)
                                    )
                                    Spacer(Modifier.weight(1f))
                                    if (isLive) {
                                        Text("В ЭФИРЕ", fontSize = 11.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                                    } else if (canPlayArchive) {
                                        Text("▶ АРХИВ", fontSize = 11.sp, color = Color(0xFFFFAB00), fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(prog.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}
