package com.iptv.ultrapro.data.parser

import com.iptv.ultrapro.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class XtreamCodesClient(private val okHttpClient: OkHttpClient) {

    suspend fun fetchLiveChannels(
        serverUrl: String,
        username: String,
        password: String,
        playlistId: Long,
        favoriteIds: Set<String>,
        hiddenIds: Set<String>,
        parser: M3uStreamParser
    ): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val baseUrl = serverUrl.trimEnd('/')

        val catUrl = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_categories"
        val categoryMap = mutableMapOf<String, String>()
        try {
            okHttpClient.newCall(Request.Builder().url(catUrl).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        categoryMap[obj.optString("category_id")] = obj.optString("category_name", "Live TV")
                    }
                }
            }
        } catch (_: Exception) { }

        val streamsUrl = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_streams"
        val result = mutableListOf<ChannelEntity>()

        okHttpClient.newCall(Request.Builder().url(streamsUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Xtream API Error: HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: throw Exception("Пустой ответ от Xtream сервера")
            val streamsArray = JSONArray(bodyStr)

            for (i in 0 until streamsArray.length()) {
                val item: JSONObject = streamsArray.getJSONObject(i)
                val streamId = item.optInt("stream_id")
                val name = item.optString("name", "Channel #$streamId")
                val catId = item.optString("category_id")
                val group = categoryMap[catId] ?: "Xtream Live"
                val icon = item.optString("stream_icon").takeIf { it.isNotBlank() }
                val epgId = item.optString("epg_channel_id").takeIf { it.isNotBlank() }
                val tvArchive = item.optInt("tv_archive", 0)
                val tvArchiveDuration = item.optInt("tv_archive_duration", 0)

                val streamUrl = "$baseUrl/live/$username/$password/$streamId.ts"
                val id = parser.generateDeterministicId(playlistId, name, streamUrl)

                result.add(
                    ChannelEntity(
                        id = id,
                        playlistId = playlistId,
                        name = name,
                        streamUrl = streamUrl,
                        tvgId = epgId,
                        tvgName = name,
                        logoUrl = icon,
                        groupTitle = group,
                        catchupType = if (tvArchive == 1) "xc" else null,
                        catchupDays = tvArchiveDuration,
                        isFavorite = favoriteIds.contains(id),
                        isHidden = hiddenIds.contains(id),
                        isAdult = group.contains("18+") || group.lowercase().contains("adult")
                    )
                )
            }
        }
        result
    }

    fun buildXmltvUrl(serverUrl: String, username: String, password: String): String {
        val baseUrl = serverUrl.trimEnd('/')
        return "$baseUrl/xmltv.php?username=$username&password=$password"
    }
}
