package com.iptv.ultrapro.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceUrl: String,
    val type: String, // "M3U_URL", "M3U_LOCAL", "XTREAM"
    val xtreamServer: String? = null,
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    val epgUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val channelCount: Int = 0
)

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["groupTitle"]),
        Index(value = ["isFavorite"]),
        Index(value = ["tvgId"])
    ]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String = "Общие",
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isAdult: Boolean = false,
    val lastStatus: Int = 0 // 0 = unknown, 1 = online, -1 = offline
)

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["channelTvgId", "startTime", "endTime"])
    ]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelTvgId: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long
)
