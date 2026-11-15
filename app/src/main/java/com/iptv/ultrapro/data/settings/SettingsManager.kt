package com.iptv.ultrapro.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "iptv_ultra_settings")

data class AppSettings(
    val userAgent: String = "IPTVUltraPro/1.0 (Linux; Android 14)",
    val bufferMinMs: Int = 15000,
    val bufferMaxMs: Int = 35000,
    val autoReconnect: Boolean = true,
    val reconnectDelaySec: Int = 3,
    val udpProxyUrl: String = "",
    val parentalPin: String = "",
    val hideAdultByDefault: Boolean = true,
    val autoPlayLastChannel: Boolean = false,
    val lastChannelId: String = "",
    val selectedPlaylistId: Long = -1L
)

class SettingsManager(private val context: Context) {
    companion object {
        val KEY_USER_AGENT = stringPreferencesKey("user_agent")
        val KEY_BUFFER_MIN = intPreferencesKey("buffer_min")
        val KEY_BUFFER_MAX = intPreferencesKey("buffer_max")
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEY_RECONNECT_DELAY = intPreferencesKey("reconnect_delay")
        val KEY_UDP_PROXY = stringPreferencesKey("udp_proxy")
        val KEY_PARENTAL_PIN = stringPreferencesKey("parental_pin")
        val KEY_HIDE_ADULT = booleanPreferencesKey("hide_adult")
        val KEY_AUTOPLAY_LAST = booleanPreferencesKey("autoplay_last")
        val KEY_LAST_CHANNEL = stringPreferencesKey("last_channel")
        val KEY_SELECTED_PLAYLIST = longPreferencesKey("selected_playlist")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            userAgent = prefs[KEY_USER_AGENT] ?: "IPTVUltraPro/1.0 (Linux; Android 14)",
            bufferMinMs = prefs[KEY_BUFFER_MIN] ?: 15000,
            bufferMaxMs = prefs[KEY_BUFFER_MAX] ?: 35000,
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
            reconnectDelaySec = prefs[KEY_RECONNECT_DELAY] ?: 3,
            udpProxyUrl = prefs[KEY_UDP_PROXY] ?: "",
            parentalPin = prefs[KEY_PARENTAL_PIN] ?: "",
            hideAdultByDefault = prefs[KEY_HIDE_ADULT] ?: true,
            autoPlayLastChannel = prefs[KEY_AUTOPLAY_LAST] ?: false,
            lastChannelId = prefs[KEY_LAST_CHANNEL] ?: "",
            selectedPlaylistId = prefs[KEY_SELECTED_PLAYLIST] ?: -1L
        )
    }

    suspend fun updateSettings(transform: (MutablePreferences) -> Unit) {
        context.dataStore.edit(transform)
    }
}
