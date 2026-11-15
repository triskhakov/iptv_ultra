package com.iptv.ultrapro.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IptvDao {
    @Query("SELECT * FROM playlists ORDER BY id DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET channelCount = :count, lastUpdated = :updated WHERE id = :id")
    suspend fun updatePlaylistStats(id: Long, count: Int, updated: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM channels WHERE (:playlistId = -1 OR playlistId = :playlistId) AND isHidden = 0 ORDER BY name ASC")
    fun observeVisibleChannels(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT id FROM channels WHERE playlistId = :playlistId AND isFavorite = 1")
    suspend fun getFavoriteIds(playlistId: Long): List<String>

    @Query("SELECT id FROM channels WHERE playlistId = :playlistId AND isHidden = 1")
    suspend fun getHiddenIds(playlistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannelsBatch(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :channelId")
    suspend fun setFavorite(channelId: String, isFavorite: Boolean)

    @Query("UPDATE channels SET isHidden = :isHidden WHERE id = :channelId")
    suspend fun setHidden(channelId: String, isHidden: Boolean)

    @Query("UPDATE channels SET isHidden = 1 WHERE lastStatus = -1 AND (:playlistId = -1 OR playlistId = :playlistId)")
    suspend fun hideOfflineChannels(playlistId: Long)

    @Query("UPDATE channels SET lastStatus = :status WHERE id = :channelId")
    suspend fun updateChannelStatus(channelId: String, status: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpgBatch(programs: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE endTime < :cutoffTime")
    suspend fun cleanOldEpg(cutoffTime: Long)

    @Query("SELECT * FROM epg_programs WHERE channelTvgId = :tvgId AND startTime <= :now AND endTime >= :now LIMIT 1")
    suspend fun getCurrentProgram(tvgId: String, now: Long): EpgProgramEntity?

    @Query("SELECT * FROM epg_programs WHERE channelTvgId = :tvgId AND endTime >= :fromTime ORDER BY startTime ASC LIMIT 30")
    suspend fun getChannelSchedule(tvgId: String, fromTime: Long): List<EpgProgramEntity>
}
