package com.iptv.ultrapro.data.repository

import android.content.Context
import android.net.Uri
import com.iptv.ultrapro.data.db.*
import com.iptv.ultrapro.data.parser.M3uStreamParser
import com.iptv.ultrapro.data.parser.XmlTvStreamParser
import com.iptv.ultrapro.data.parser.XtreamCodesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class IptvRepository(
    private val context: Context,
    private val dao: IptvDao
) {
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val healthCheckClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val m3uParser = M3uStreamParser()
    private val xmlTvParser = XmlTvStreamParser()
    private val xtreamClient = XtreamCodesClient(okHttpClient)

    fun observePlaylists(): Flow<List<PlaylistEntity>> = dao.observePlaylists()

    fun observeChannels(playlistId: Long): Flow<List<ChannelEntity>> =
        dao.observeVisibleChannels(playlistId)

    suspend fun importM3uFromUrl(name: String, url: String, customEpgUrl: String? = null): Long =
        withContext(Dispatchers.IO) {
            val playlistId = dao.insertPlaylist(
                PlaylistEntity(
                    name = name.ifBlank { "Плейлист M3U" },
                    sourceUrl = url.trim(),
                    type = "M3U_URL",
                    epgUrl = customEpgUrl?.takeIf { it.isNotBlank() }
                )
            )
            syncM3uUrlPlaylist(playlistId, url.trim(), customEpgUrl)
            playlistId
        }

    suspend fun importM3uFromLocalUri(name: String, uri: Uri): Long =
        withContext(Dispatchers.IO) {
            val playlistId = dao.insertPlaylist(
                PlaylistEntity(
                    name = name.ifBlank { "Локальный M3U" },
                    sourceUrl = uri.toString(),
                    type = "M3U_LOCAL"
                )
            )
            var discoveredEpg: String? = null
            var totalCount = 0
            val batch = mutableListOf<ChannelEntity>()

            context.contentResolver.openInputStream(uri)?.use { stream ->
                m3uParser.parse(
                    inputStream = stream,
                    playlistId = playlistId,
                    favoriteIds = emptySet(),
                    hiddenIds = emptySet(),
                    onHeaderParsed = { discoveredEpg = it.epgUrl }
                ).collect { channel ->
                    batch.add(channel)
                    totalCount++
                    if (batch.size >= 500) {
                        dao.insertChannelsBatch(batch.toList())
                        batch.clear()
                    }
                }
            } ?: throw Exception("Не удалось открыть выбранный файл")

            if (batch.isNotEmpty()) {
                dao.insertChannelsBatch(batch)
            }
            dao.updatePlaylistStats(playlistId, totalCount)

            if (!discoveredEpg.isNullOrBlank()) {
                runCatching { syncEpgFromUrl(discoveredEpg!!) }
            }
            playlistId
        }

    suspend fun importXtreamPlaylist(
        name: String,
        serverUrl: String,
        username: String,
        password: String
    ): Long = withContext(Dispatchers.IO) {
        val epgUrl = xtreamClient.buildXmltvUrl(serverUrl, username, password)
        val playlistId = dao.insertPlaylist(
            PlaylistEntity(
                name = name.ifBlank { "Xtream TV" },
                sourceUrl = serverUrl.trim(),
                type = "XTREAM",
                xtreamServer = serverUrl.trim(),
                xtreamUsername = username.trim(),
                xtreamPassword = password.trim(),
                epgUrl = epgUrl
            )
        )
        syncXtreamPlaylist(playlistId, serverUrl.trim(), username.trim(), password.trim())
        playlistId
    }

    suspend fun refreshPlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        when (playlist.type) {
            "M3U_URL" -> syncM3uUrlPlaylist(playlist.id, playlist.sourceUrl, playlist.epgUrl)
            "XTREAM" -> syncXtreamPlaylist(
                playlist.id,
                playlist.xtreamServer ?: playlist.sourceUrl,
                playlist.xtreamUsername ?: "",
                playlist.xtreamPassword ?: ""
            )
            "M3U_LOCAL" -> importM3uFromLocalUri(playlist.name, Uri.parse(playlist.sourceUrl))
        }
    }

    private suspend fun syncM3uUrlPlaylist(playlistId: Long, url: String, existingEpgUrl: String?) {
        val favIds = dao.getFavoriteIds(playlistId).toSet()
        val hiddenIds = dao.getHiddenIds(playlistId).toSet()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "IPTVUltraPro/1.0")
            .build()

        var discoveredEpg: String? = existingEpgUrl
        var totalCount = 0
        val batch = mutableListOf<ChannelEntity>()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Ошибка загрузки плейлиста: HTTP ${response.code}")
            val bodyStream = response.body?.byteStream() ?: throw Exception("Сервер вернул пустой файл")

            dao.deleteChannelsByPlaylist(playlistId)

            m3uParser.parse(
                inputStream = bodyStream,
                playlistId = playlistId,
                favoriteIds = favIds,
                hiddenIds = hiddenIds,
                onHeaderParsed = { meta ->
                    if (discoveredEpg.isNullOrBlank()) discoveredEpg = meta.epgUrl
                }
            ).collect { channel ->
                batch.add(channel)
                totalCount++
                if (batch.size >= 500) {
                    dao.insertChannelsBatch(batch.toList())
                    batch.clear()
                }
            }
        }

        if (batch.isNotEmpty()) {
            dao.insertChannelsBatch(batch)
        }
        dao.updatePlaylistStats(playlistId, totalCount)

        if (!discoveredEpg.isNullOrBlank()) {
            runCatching { syncEpgFromUrl(discoveredEpg!!) }
        }
    }

    private suspend fun syncXtreamPlaylist(
        playlistId: Long,
        serverUrl: String,
        username: String,
        password: String
    ) {
        val favIds = dao.getFavoriteIds(playlistId).toSet()
        val hiddenIds = dao.getHiddenIds(playlistId).toSet()

        val channels = xtreamClient.fetchLiveChannels(
            serverUrl = serverUrl,
            username = username,
            password = password,
            playlistId = playlistId,
            favoriteIds = favIds,
            hiddenIds = hiddenIds,
            parser = m3uParser
        )

        dao.deleteChannelsByPlaylist(playlistId)
        channels.chunked(500).forEach { chunk ->
            dao.insertChannelsBatch(chunk)
        }
        dao.updatePlaylistStats(playlistId, channels.size)
    }

    suspend fun syncEpgFromUrl(epgUrl: String) = withContext(Dispatchers.IO) {
        val urls = epgUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        dao.cleanOldEpg(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)

        for (singleUrl in urls) {
            val request = Request.Builder().url(singleUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Ошибка EPG: HTTP ${response.code}")
                val stream = response.body?.byteStream() ?: return@use
                val batch = mutableListOf<EpgProgramEntity>()

                xmlTvParser.parse(stream, isGzipHint = singleUrl.endsWith(".gz", true))
                    .collect { program ->
                        batch.add(program)
                        if (batch.size >= 1000) {
                            dao.insertEpgBatch(batch.toList())
                            batch.clear()
                        }
                    }

                if (batch.isNotEmpty()) {
                    dao.insertEpgBatch(batch)
                }
            }
        }
    }

    suspend fun toggleFavorite(channel: ChannelEntity) {
        dao.setFavorite(channel.id, !channel.isFavorite)
    }

    suspend fun hideChannel(channel: ChannelEntity) {
        dao.setHidden(channel.id, true)
    }

    suspend fun hideOfflineChannels(playlistId: Long) {
        dao.hideOfflineChannels(playlistId)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        dao.deletePlaylist(playlistId)
        dao.deleteChannelsByPlaylist(playlistId)
    }

    suspend fun getCurrentProgram(tvgId: String?): EpgProgramEntity? {
        if (tvgId.isNullOrBlank()) return null
        return dao.getCurrentProgram(tvgId, System.currentTimeMillis())
    }

    suspend fun getChannelSchedule(tvgId: String?): List<EpgProgramEntity> {
        if (tvgId.isNullOrBlank()) return emptyList()
        val from = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        return dao.getChannelSchedule(tvgId, from)
    }

    suspend fun runHealthCheck(
        channels: List<ChannelEntity>,
        onProgress: (checked: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(12)
        var checked = 0
        val total = channels.size

        coroutineScope {
            channels.map { channel ->
                async {
                    semaphore.withPermit {
                        val isAlive = checkSingleStream(channel)
                        dao.updateChannelStatus(channel.id, if (isAlive) 1 else -1)
                        checked++
                        onProgress(checked, total)
                    }
                }
            }.awaitAll()
        }
    }

    private fun checkSingleStream(channel: ChannelEntity): Boolean {
        val url = channel.streamUrl
        if (url.startsWith("udp://", true) || url.startsWith("rtp://", true)) return true
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", channel.userAgent ?: "IPTVUltraPro/1.0")
                .get()
                .build()
            healthCheckClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..399
            }
        } catch (_: Exception) {
            false
        }
    }
}
