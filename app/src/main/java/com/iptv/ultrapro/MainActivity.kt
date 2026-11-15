package com.iptv.ultrapro

import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.iptv.ultrapro.data.db.ChannelEntity
import com.iptv.ultrapro.data.db.EpgProgramEntity
import com.iptv.ultrapro.data.settings.SettingsManager
import com.iptv.ultrapro.ui.IptvViewModel
import com.iptv.ultrapro.ui.player.MultiScreenOverlay
import com.iptv.ultrapro.ui.player.UltraPlayerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val darkCyberScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            onPrimary = Color(0xFF001F24),
            secondary = Color(0xFF7C4DFF),
            background = Color(0xFF0D1117),
            surface = Color(0xFF161B22),
            onBackground = Color(0xFFF0F6FC),
            onSurface = Color(0xFFF0F6FC)
        )

        setContent {
            MaterialTheme(colorScheme = darkCyberScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IptvUltraRoot(
                        onEnterPip = { enterPipMode() }
                    )
                }
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvUltraRoot(
    vm: IptvViewModel = viewModel(),
    onEnterPip: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val playlists by vm.playlists.collectAsState()
    val selectedPlaylistId by vm.selectedPlaylistId.collectAsState()
    val categories by vm.categories.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
    val channels by vm.filteredChannels.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val statusBanner by vm.statusBanner.collectAsState()
    val activePlayback by vm.activePlayback.collectAsState()
    val settings by vm.settings.collectAsState()
    val epgMap by vm.currentEpgMap.collectAsState()
    val adultUnlocked by vm.adultUnlocked.collectAsState()
    val multiScreenItems by vm.multiScreenChannels.collectAsState()
    val isMultiScreenActive by vm.isMultiScreenActive.collectAsState()

    var currentTab by remember { mutableIntStateOf(0) }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var activeSchedule by remember { mutableStateOf<List<EpgProgramEntity>>(emptyList()) }

    LaunchedEffect(activePlayback?.channel?.id) {
        val ch = activePlayback?.channel
        activeSchedule = if (ch != null) vm.getScheduleForChannel(ch) else emptyList()
    }

    if (activePlayback != null) {
        BackHandler { vm.stopPlayback() }
        UltraPlayerScreen(
            playback = activePlayback!!,
            settings = settings,
            schedule = activeSchedule,
            onClose = { vm.stopPlayback() },
            onNextChannel = { vm.switchChannelRelative(1) },
            onPrevChannel = { vm.switchChannelRelative(-1) },
            onSelectArchiveProgram = { prog ->
                vm.playArchive(activePlayback!!.channel, prog)
            },
            onEnterPip = onEnterPip,
            onAddToMultiScreen = {
                val ch = activePlayback!!.channel
                vm.stopPlayback()
                vm.addToMultiScreen(ch)
            }
        )
        return
    }

    if (isMultiScreenActive && multiScreenItems.isNotEmpty()) {
        BackHandler { vm.setMultiScreenActive(false) }
        MultiScreenOverlay(
            items = multiScreenItems,
            settings = settings,
            onCloseAll = { vm.setMultiScreenActive(false) },
            onRemoveChannel = { vm.removeFromMultiScreen(it) },
            onExpandFullscreen = { req ->
                vm.setMultiScreenActive(false)
                vm.playLive(req.channel)
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("IPTV ULTRA PRO", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = Color(0xFF00E5FF)) {
                            Text("${channels.size}", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                actions = {
                    if (multiScreenItems.isNotEmpty()) {
                        IconButton(onClick = { vm.setMultiScreenActive(true) }) {
                            Icon(Icons.Default.GridView, "Открыть Мультискрин", tint = Color(0xFF00E5FF))
                        }
                    }
                    if (settings.parentalPin.isNotEmpty()) {
                        IconButton(onClick = {
                            if (adultUnlocked) vm.lockAdult() else showPinDialog = true
                        }) {
                            Icon(
                                imageVector = if (adultUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = "Родительский контроль",
                                tint = if (adultUnlocked) Color(0xFFFFAB00) else Color.Gray
                            )
                        }
                    }
                    IconButton(onClick = { showAddPlaylistDialog = true }) {
                        Icon(Icons.Default.AddCircle, "Добавить плейлист", tint = Color(0xFF00E5FF))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161B22)) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.LiveTv, null) },
                    label = { Text("Каналы") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.PlaylistPlay, null) },
                    label = { Text("Плейлисты (${playlists.size})") }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Tune, null) },
                    label = { Text("Настройки") }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF00E5FF)
                )
            }

            if (statusBanner != null) {
                Surface(
                    color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBanner!!,
                            color = Color(0xFF00E5FF),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.clearBanner() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray)
                        }
                    }
                }
            }

            when (currentTab) {
                0 -> ChannelsTabContent(
                    playlists = playlists,
                    selectedPlaylistId = selectedPlaylistId,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    channels = channels,
                    searchQuery = searchQuery,
                    epgMap = epgMap,
                    onSelectPlaylist = { vm.selectPlaylist(it) },
                    onSelectCategory = { vm.selectCategory(it) },
                    onSearchChange = { vm.updateSearchQuery(it) },
                    onChannelClick = { vm.playLive(it) },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                    onHideChannel = { vm.hideChannel(it) },
                    onAddMultiScreen = { vm.addToMultiScreen(it) },
                    onRequestEpg = { vm.loadEpgForChannel(it) },
                    onOpenAddDialog = { showAddPlaylistDialog = true },
                    onRunHealthCheck = { vm.runHealthCheck() },
                    onHideOffline = { vm.hideOfflineChannels() }
                )

                1 -> PlaylistsTabContent(
                    playlists = playlists,
                    selectedPlaylistId = selectedPlaylistId,
                    onSelect = {
                        vm.selectPlaylist(it.id)
                        currentTab = 0
                    },
                    onRefresh = { vm.refreshPlaylist(it) },
                    onDelete = { vm.deletePlaylist(it) },
                    onOpenAdd = { showAddPlaylistDialog = true }
                )

                2 -> SettingsTabContent(
                    settings = settings,
                    onSaveSettings = { transform ->
                        scope.launch { vm.settingsManager.updateSettings(transform) }
                    },
                    onSyncCustomEpg = { vm.syncCustomEpg(it) }
                )
            }
        }
    }

    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            onDismiss = { showAddPlaylistDialog = false },
            onAddM3uUrl = { name, url, epg ->
                showAddPlaylistDialog = false
                vm.addM3uUrlPlaylist(name, url, epg)
            },
            onAddLocalUri = { name, uri ->
                showAddPlaylistDialog = false
                vm.addLocalM3uPlaylist(name, uri)
            },
            onAddXtream = { name, srv, user, pass ->
                showAddPlaylistDialog = false
                vm.addXtreamPlaylist(name, srv, user, pass)
            }
        )
    }

    if (showPinDialog) {
        ParentalPinDialog(
            onDismiss = { showPinDialog = false },
            onSubmit = { pin ->
                if (vm.unlockAdultWithPin(pin)) {
                    showPinDialog = false
                }
            }
        )
    }
}

@Composable
private fun ChannelsTabContent(
    playlists: List<com.iptv.ultrapro.data.db.PlaylistEntity>,
    selectedPlaylistId: Long,
    categories: List<String>,
    selectedCategory: String,
    channels: List<ChannelEntity>,
    searchQuery: String,
    epgMap: Map<String, EpgProgramEntity>,
    onSelectPlaylist: (Long) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onChannelClick: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onHideChannel: (ChannelEntity) -> Unit,
    onAddMultiScreen: (ChannelEntity) -> Unit,
    onRequestEpg: (ChannelEntity) -> Unit,
    onOpenAddDialog: () -> Unit,
    onRunHealthCheck: () -> Unit,
    onHideOffline: () -> Unit
) {
    if (playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.Tv, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text("Нет загруженных плейлистов", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Добавьте M3U ссылку, локальный файл, Xtream Codes или загрузите встроенный бесплатный плейлист.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onOpenAddDialog) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить плейлист")
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Поиск канала...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(onClick = onRunHealthCheck) {
                Icon(Icons.Default.NetworkCheck, "Проверить потоки")
            }
            FilledTonalIconButton(onClick = onHideOffline) {
                Icon(Icons.Default.VisibilityOff, "Скрыть нерабочие")
            }
        }

        if (playlists.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = selectedPlaylistId == -1L,
                        onClick = { onSelectPlaylist(-1L) },
                        label = { Text("Все плейлисты") }
                    )
                }
                items(playlists) { pl ->
                    FilterChip(
                        selected = selectedPlaylistId == pl.id,
                        onClick = { onSelectPlaylist(pl.id) },
                        label = { Text(pl.name) }
                    )
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            items(categories) { category ->
                FilterChip(
                    selected = category == selectedCategory,
                    onClick = { onSelectCategory(category) },
                    label = { Text(category) }
                )
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                LaunchedEffect(channel.id) { onRequestEpg(channel) }
                val currentProgram = channel.tvgId?.let { epgMap[it] }

                ChannelRowCard(
                    channel = channel,
                    currentProgram = currentProgram,
                    onClick = { onChannelClick(channel) },
                    onToggleFavorite = { onToggleFavorite(channel) },
                    onHide = { onHideChannel(channel) },
                    onMultiScreen = { onAddMultiScreen(channel) }
                )
            }
        }
    }
}

@Composable
private fun ChannelRowCard(
    channel: ChannelEntity,
    currentProgram: EpgProgramEntity?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onMultiScreen: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) Color(0xFF1F3242) else Color(0xFF161B22))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color(0xFF00E5FF) else Color(0xFF262C36),
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF21262D))
                    .padding(4.dp)
            )
            if (channel.lastStatus != 0) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(if (channel.lastStatus == 1) Color(0xFF00E676) else Color(0xFFFF5252))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (channel.catchupDays > 0 || !channel.catchupType.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = Color(0x33FFAB00),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "АРХИВ",
                            color = Color(0xFFFFAB00),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            if (currentProgram != null) {
                Text(
                    text = "Сейчас: ${currentProgram.title}",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = channel.groupTitle,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }

        IconButton(onClick = onMultiScreen, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.GridView, "В мультискрин", tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (channel.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Избранное",
                tint = if (channel.isFavorite) Color(0xFFFFD600) else Color.Gray
            )
        }
        IconButton(onClick = onHide, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.VisibilityOff, "Скрыть", tint = Color.DarkGray, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<com.iptv.ultrapro.data.db.PlaylistEntity>,
    selectedPlaylistId: Long,
    onSelect: (com.iptv.ultrapro.data.db.PlaylistEntity) -> Unit,
    onRefresh: (com.iptv.ultrapro.data.db.PlaylistEntity) -> Unit,
    onDelete: (com.iptv.ultrapro.data.db.PlaylistEntity) -> Unit,
    onOpenAdd: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = onOpenAdd,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить новый плейлист (M3U / Xtream / Файл)")
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(playlists, key = { it.id }) { pl ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (pl.id == selectedPlaylistId) Color(0xFF1A2D3D) else Color(0xFF161B22)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(pl) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = "${pl.type} • Каналов: ${pl.channelCount}",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp
                            )
                            Text(
                                text = pl.sourceUrl,
                                color = Color.Gray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onRefresh(pl) }) {
                            Icon(Icons.Default.Refresh, "Обновить", tint = Color(0xFF00E5FF))
                        }
                        IconButton(onClick = { onDelete(pl) }) {
                            Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFFF5252))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabContent(
    settings: com.iptv.ultrapro.data.settings.AppSettings,
    onSaveSettings: ((androidx.datastore.preferences.core.MutablePreferences) -> Unit) -> Unit,
    onSyncCustomEpg: (String) -> Unit
) {
    var userAgent by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }
    var udpProxy by remember(settings.udpProxyUrl) { mutableStateOf(settings.udpProxyUrl) }
    var pinCode by remember(settings.parentalPin) { mutableStateOf(settings.parentalPin) }
    var bufferSec by remember(settings.bufferMinMs) { mutableFloatStateOf(settings.bufferMinMs / 1000f) }
    var customEpgUrl by remember { mutableStateOf("https://iptvx.one/epg/epg.xml.gz") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Плеер и Буферизация", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 16.sp)
        }

        item {
            Column {
                Text("Размер буфера потока: ${bufferSec.toInt()} сек.")
                Slider(
                    value = bufferSec,
                    onValueChange = { bufferSec = it },
                    valueRange = 5f..60f,
                    onValueChangeFinished = {
                        val minMs = (bufferSec.toInt() * 1000)
                        onSaveSettings {
                            it[SettingsManager.KEY_BUFFER_MIN] = minMs
                            it[SettingsManager.KEY_BUFFER_MAX] = minMs * 2
                        }
                    }
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Умный автореконнект")
                    Text("Переподключать при обрыве потока", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = settings.autoReconnect,
                    onCheckedChange = { checked ->
                        onSaveSettings { it[SettingsManager.KEY_AUTO_RECONNECT] = checked }
                    }
                )
            }
        }

        item {
            OutlinedTextField(
                value = userAgent,
                onValueChange = { userAgent = it },
                label = { Text("Глобальный User-Agent") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        onSaveSettings { it[SettingsManager.KEY_USER_AGENT] = userAgent }
                    }) {
                        Icon(Icons.Default.Check, "Сохранить")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = udpProxy,
                onValueChange = { udpProxy = it },
                label = { Text("UDP-прокси (udpxy, напр. http://192.168.1.1:4022)") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        onSaveSettings { it[SettingsManager.KEY_UDP_PROXY] = udpProxy }
                    }) {
                        Icon(Icons.Default.Check, "Сохранить")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            HorizontalDivider()
            Text("Телепрограмма (XMLTV EPG)", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 16.sp)
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customEpgUrl,
                    onValueChange = { customEpgUrl = it },
                    label = { Text("URL EPG (.xml / .xml.gz)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSyncCustomEpg(customEpgUrl) }) {
                    Text("Скачать")
                }
            }
        }

        item {
            HorizontalDivider()
            Text("Родительский контроль (18+)", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 16.sp)
        }

        item {
            OutlinedTextField(
                value = pinCode,
                onValueChange = { if (it.length <= 6) pinCode = it },
                label = { Text("PIN-код доступа к каналам 18+ (пусто = без пароля)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        onSaveSettings { it[SettingsManager.KEY_PARENTAL_PIN] = pinCode }
                    }) {
                        Icon(Icons.Default.Check, "Сохранить PIN")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onAddM3uUrl: (String, String, String?) -> Unit,
    onAddLocalUri: (String, Uri) -> Unit,
    onAddXtream: (String, String, String, String) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("Мой IPTV") }
    var m3uUrl by remember { mutableStateOf("https://iptv-org.github.io/iptv/languages/rus.m3u") }
    var epgUrl by remember { mutableStateOf("") }

    var xtreamServer by remember { mutableStateOf("http://") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPass by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAddLocalUri(name, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить плейлист") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("M3U URL") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Файл") })
                    FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("Xtream") })
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название плейлиста") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                when (mode) {
                    0 -> {
                        OutlinedTextField(
                            value = m3uUrl,
                            onValueChange = { m3uUrl = it },
                            label = { Text("Ссылка M3U / M3U8") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = epgUrl,
                            onValueChange = { epgUrl = it },
                            label = { Text("Ссылка EPG XMLTV (необязательно)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    1 -> {
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Выбрать .m3u / .m3u8 файл")
                        }
                    }
                    2 -> {
                        OutlinedTextField(
                            value = xtreamServer,
                            onValueChange = { xtreamServer = it },
                            label = { Text("Сервер (http://domain:port)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = xtreamUser,
                            onValueChange = { xtreamUser = it },
                            label = { Text("Логин (Username)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = xtreamPass,
                            onValueChange = { xtreamPass = it },
                            label = { Text("Пароль (Password)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (mode != 1) {
                Button(onClick = {
                    if (mode == 0) onAddM3uUrl(name, m3uUrl, epgUrl)
                    else onAddXtream(name, xtreamServer, xtreamUser, xtreamPass)
                }) {
                    Text("Загрузить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun ParentalPinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Введите PIN-код 18+") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text("PIN-код") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onSubmit(pin) }) { Text("Разблокировать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
