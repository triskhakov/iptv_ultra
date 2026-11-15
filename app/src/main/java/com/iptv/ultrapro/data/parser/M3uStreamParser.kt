package com.iptv.ultrapro.data.parser

import com.iptv.ultrapro.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest

data class ParsedPlaylistMeta(
    val epgUrl: String? = null
)

class M3uStreamParser {
    companion object {
        private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")
        private val ADULT_KEYWORDS = listOf("18+", "adult", "xxx", "erotic", "эротика", "взрослые", "порно")
    }

    fun parse(
        inputStream: InputStream,
        playlistId: Long,
        favoriteIds: Set<String>,
        hiddenIds: Set<String>,
        onHeaderParsed: ((ParsedPlaylistMeta) -> Unit)? = null
    ): Flow<ChannelEntity> = flow {
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 65536).use { reader ->
            var currentName: String? = null
            var attrs: Map<String, String> = emptyMap()
            var extGrp: String? = null
            var userAgent: String? = null
            var referer: String? = null

            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> Unit

                    trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val headerAttrs = parseAttributes(trimmed)
                        val epgUrl = headerAttrs["url-tvg"] ?: headerAttrs["x-tvg-url"]
                        if (!epgUrl.isNullOrBlank()) {
                            onHeaderParsed?.invoke(ParsedPlaylistMeta(epgUrl = epgUrl))
                        }
                    }

                    trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
                        extGrp = null
                        userAgent = null
                        referer = null

                        val commaIndex = findLastUnquotedComma(trimmed)
                        val metaPart = if (commaIndex != -1) {
                            trimmed.substring(8, commaIndex)
                        } else {
                            trimmed.substring(8)
                        }

                        currentName = if (commaIndex != -1 && commaIndex + 1 < trimmed.length) {
                            trimmed.substring(commaIndex + 1).trim()
                        } else {
                            "Без названия"
                        }

                        attrs = parseAttributes(metaPart)
                    }

                    trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        extGrp = trimmed.substring(8).trim()
                    }

                    trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = trimmed.substring(11).trim()
                        when {
                            opt.startsWith("http-user-agent=", ignoreCase = true) ->
                                userAgent = opt.substringAfter("=")
                            opt.startsWith("http-referrer=", ignoreCase = true) ||
                            opt.startsWith("http-referer=", ignoreCase = true) ->
                                referer = opt.substringAfter("=")
                        }
                    }

                    !trimmed.startsWith("#") -> {
                        val name = currentName ?: "Канал"
                        val group = extGrp
                            ?: attrs["group-title"]?.takeIf { it.isNotBlank() }
                            ?: "Общие"
                        val id = generateDeterministicId(playlistId, name, trimmed)
                        val isAdult = isAdultContent(name, group)

                        emit(
                            ChannelEntity(
                                id = id,
                                playlistId = playlistId,
                                name = name,
                                streamUrl = trimmed,
                                tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                                tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                                logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                                groupTitle = group,
                                catchupType = attrs["catchup"] ?: attrs["catchup-type"],
                                catchupDays = attrs["catchup-days"]?.toIntOrNull()
                                    ?: attrs["tvg-rec"]?.toIntOrNull()
                                    ?: 0,
                                catchupSource = attrs["catchup-source"],
                                userAgent = userAgent,
                                referer = referer,
                                isFavorite = favoriteIds.contains(id),
                                isHidden = hiddenIds.contains(id),
                                isAdult = isAdult
                            )
                        )

                        currentName = null
                        attrs = emptyMap()
                    }
                }
                line = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseAttributes(metadata: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(metadata).forEach { m ->
            map[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
        }
        return map
    }

    private fun findLastUnquotedComma(line: String): Int {
        var inQuotes = false
        for (i in line.length - 1 downTo 0) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun isAdultContent(name: String, group: String): Boolean {
        val combined = "$name $group".lowercase()
        return ADULT_KEYWORDS.any { combined.contains(it) }
    }

    fun generateDeterministicId(playlistId: Long, name: String, url: String): String {
        val raw = "$playlistId|$name|$url"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
