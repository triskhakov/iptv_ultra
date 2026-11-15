package com.iptv.ultrapro.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.ultrapro.data.db.AppDatabase
import com.iptv.ultrapro.data.db.ChannelEntity
import com.iptv.ultrapro.data.db.EpgProgramEntity
import com.iptv.ultrapro.data.db.PlaylistEntity
import com.iptv.ultrapro.data.parser.StreamUrlResolver
import com.iptv.ultrapro.data.repository.IptvRepository
import com.iptv.ultrapro.data.settings.AppSettings
import com.iptv.ultrapro.data.settings.SettingsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PlaybackRequest(
    val channel: ChannelEntity,
    val resolvedUrl: String,
    val archiveProgram: EpgProgramEntity? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class IptvViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = IptvRepository(application, db.iptvDao())
    val settingsManager = SettingsManager(application)

    val settings: StateFlow<AppSettings> = settingsManager.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlaylistId = MutableStateFlow(-1L)
    val selectedPlaylistId: StateFlow<Long> = _selectedPlaylistId.asStateFlow()

    val allChannels: StateFlow<List<ChannelEntity>> = _selectedPlaylistId
        .flatMapLatest { plId -> repository.observeChannels(plId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCategory = MutableStateFlow("Все")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _adultUnlocked = MutableStateFlow(false)
    val adultUnlocked: StateFlow<Boolean> = _adultUnlocked.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _activePlayback = MutableStateFlow<PlaybackRequest?>(null)
    val activePlayback: StateFlow<PlaybackRequest?> = _activePlayback.asStateFlow()

    private val _multiScreenChannels = MutableStateFlow<List<PlaybackRequest>>(emptyList())
    val multiScreenChannels: StateFlow<List<PlaybackRequest>> = _multiScreenChannels.asStateFlow()

    private val _isMultiScreenActive = MutableStateFlow(false)
    val isMultiScreenActive: StateFlow<Boolean> = _isMultiScreenActive.asStateFlow()

    private val _currentEpgMap = MutableStateFlow<Map<String, EpgProgramEntity>>(emptyMap())
    val currentEpgMap: StateFlow<Map<String, EpgProgramEntity>> = _currentEpgMap.asStateFlow()

    val categories: StateFlow<List<String>> = combine(
        allChannels, settings, _adultUnlocked
    ) { channels, cfg, unlocked ->
        val visible = if (cfg.hideAdultByDefault && !unlocked) {
            channels.filter { !it.isAdult }
        } else channels

        val groups = visible.map { it.groupTitle }.distinct().sorted()
        val hasFavorites = visible.any { it.isFavorite }
        buildList {
            add("Все")
            if (hasFavorites) add("★ Избранное")
            addAll(groups)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("Все"))

    val filteredChannels: StateFlow<List<ChannelEntity>> = combine(
        allChannels, _selectedCategory, _searchQuery, settings, _adultUnlocked
    ) { channels, category, query, cfg, unlocked ->
        channels.filter { ch ->
            val adultAllowed = !(cfg.hideAdultByDefault && !unlocked && ch.isAdult)
            val catMatch = when (category) {
                "Все" -> true
                "★ Избранное" -> ch.isFavorite
                else -> ch.groupTitle == category
            }
            val queryMatch = query.isBlank() ||
                    ch.name.contains(query, ignoreCase = true) ||
                    ch.groupTitle.contains(query, ignoreCase = true)
            adultAllowed && catMatch && queryMatch
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val initialSettings = settingsManager.settingsFlow.first()
            _selectedPlaylistId.value = initialSettings.selectedPlaylistId
        }
    }

    fun selectPlaylist(playlistId: Long) {
        _selectedPlaylistId.value = playlistId
        _selectedCategory.value = "Все"
        viewModelScope.launch {
            settingsManager.updateSettings { it[SettingsManager.KEY_SELECTED_PLAYLIST] = playlistId }
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun unlockAdultWithPin(pin: String): Boolean {
        val savedPin = settings.value.parentalPin
        return if (savedPin.isEmpty() || savedPin == pin) {
            _adultUnlocked.value = true
            true
        } else {
            false
        }
    }

    fun lockAdult() {
        _adultUnlocked.value = false
    }

    fun addM3uUrlPlaylist(name: String, url: String, epgUrl: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            _statusBanner.value = "Загрузка и парсинг M3U плейлиста..."
            try {
                val id = repository.importM3uFromUrl(name, url, epgUrl)
                selectPlaylist(id)
                _statusBanner.value = "Плейлист успешно добавлен!"
            } catch (e: Exception) {
                _statusBanner.value = "Ошибка: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addLocalM3uPlaylist(name: String, uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _statusBanner.value = "Чтение локального файла M3U..."
            try {
                val id = repository.importM3uFromLocalUri(name, uri)
                selectPlaylist(id)
                _statusBanner.value = "Файл успешно импортирован!"
            } catch (e: Exception) {
                _statusBanner.value = "Ошибка импорта: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addXtreamPlaylist(name: String, server: String, user: String, pass: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _statusBanner.value = "Подключение к Xtream Codes API..."
            try {
                val id = repository.importXtreamPlaylist(name, server, user, pass)
                selectPlaylist(id)
                _statusBanner.value = "Xtream плейлист загружен!"
            } catch (e: Exception) {
                _statusBanner.value = "Ошибка Xtream: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshPlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            _isLoading.value = true
            _statusBanner.value = "Обновление '${playlist.name}'..."
            try {
                repository.refreshPlaylist(playlist)
                _statusBanner.value = "Плейлист обновлен"
            } catch (e: Exception) {
                _statusBanner.value = "Ошибка обновления: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id)
            if (_selectedPlaylistId.value == playlist.id) {
                selectPlaylist(-1L)
            }
        }
    }

    fun syncCustomEpg(epgUrl: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _statusBanner.value = "Загрузка телепрограммы XMLTV..."
            try {
                repository.syncEpgFromUrl(epgUrl)
                _statusBanner.value = "Телепрограмма (EPG) обновлена!"
            } catch (e: Exception) {
                _statusBanner.value = "Ошибка EPG: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadEpgForChannel(channel: ChannelEntity) {
        val tvgId = channel.tvgId ?: return
        if (_currentEpgMap.value.containsKey(tvgId)) return
        viewModelScope.launch {
            val prog = repository.getCurrentProgram(tvgId)
            if (prog != null) {
                _currentEpgMap.value = _currentEpgMap.value + (tvgId to prog)
            }
        }
    }

    suspend fun getScheduleForChannel(channel: ChannelEntity): List<EpgProgramEntity> {
        return repository.getChannelSchedule(channel.tvgId)
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { repository.toggleFavorite(channel) }
    }

    fun hideChannel(channel: ChannelEntity) {
        viewModelScope.launch { repository.hideChannel(channel) }
    }

    fun hideOfflineChannels() {
        viewModelScope.launch {
            repository.hideOfflineChannels(_selectedPlaylistId.value)
            _statusBanner.value = "Неработающие каналы скрыты"
        }
    }

    fun runHealthCheck() {
        val list = filteredChannels.value.take(150)
        if (list.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            repository.runHealthCheck(list) { checked, total ->
                _statusBanner.value = "Проверка потоков: $checked / $total"
            }
            _isLoading.value = false
            _statusBanner.value = "Проверка завершена. Мертвые каналы помечены красным."
        }
    }

    fun playLive(channel: ChannelEntity) {
        val url = StreamUrlResolver.resolveLiveUrl(channel, settings.value.udpProxyUrl)
        _activePlayback.value = PlaybackRequest(channel = channel, resolvedUrl = url)
        viewModelScope.launch {
            settingsManager.updateSettings { it[SettingsManager.KEY_LAST_CHANNEL] = channel.id }
        }
    }

    fun playArchive(channel: ChannelEntity, program: EpgProgramEntity) {
        val url = StreamUrlResolver.resolveCatchupUrl(channel, program, settings.value.udpProxyUrl)
        _activePlayback.value = PlaybackRequest(
            channel = channel,
            resolvedUrl = url,
            archiveProgram = program
        )
    }

    fun switchChannelRelative(delta: Int) {
        val current = _activePlayback.value?.channel ?: return
        val list = filteredChannels.value
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == current.id }
        if (idx == -1) return
        val nextIdx = (idx + delta + list.size) % list.size
        playLive(list[nextIdx])
    }

    fun stopPlayback() {
        _activePlayback.value = null
    }

    fun addToMultiScreen(channel: ChannelEntity) {
        val current = _multiScreenChannels.value
        if (current.any { it.channel.id == channel.id }) {
            _isMultiScreenActive.value = true
            return
        }
        val url = StreamUrlResolver.resolveLiveUrl(channel, settings.value.udpProxyUrl)
        val updated = (current + PlaybackRequest(channel, url)).takeLast(4)
        _multiScreenChannels.value = updated
        _isMultiScreenActive.value = true
        _statusBanner.value = "Канал добавлен в Мультискрин (${updated.size}/4)"
    }

    fun removeFromMultiScreen(channelId: String) {
        val updated = _multiScreenChannels.value.filterNot { it.channel.id == channelId }
        _multiScreenChannels.value = updated
        if (updated.isEmpty()) _isMultiScreenActive.value = false
    }

    fun setMultiScreenActive(active: Boolean) {
        _isMultiScreenActive.value = active
    }

    fun clearBanner() {
        _statusBanner.value = null
    }
}
