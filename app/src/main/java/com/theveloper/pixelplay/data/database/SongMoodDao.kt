package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Lightweight row used to feed songs into [com.theveloper.pixelplay.data.audio.MoodAnalyzer]. */
data class MoodAnalysisSongRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "content_uri_string") val contentUriString: String,
    val genre: String?,
    val duration: Long
)

/** Lightweight row used to match local songs against an imported legacy-analysis file by filename/duration. */
data class MoodImportMatchRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "file_path") val filePath: String,
    val duration: Long,
    val genre: String?
)

@Dao
interface SongMoodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mood: SongMoodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(moods: List<SongMoodEntity>)

    @Query("SELECT * FROM song_moods")
    fun getAllFlow(): Flow<List<SongMoodEntity>>

    @Query("SELECT * FROM song_moods")
    suspend fun getAll(): List<SongMoodEntity>

    @Query("SELECT song_id FROM song_moods WHERE analysis_version = :version")
    suspend fun getAnalyzedSongIds(version: Int = SongMoodEntity.CURRENT_ANALYSIS_VERSION): List<Long>

    @Query("SELECT COUNT(*) FROM song_moods WHERE analysis_version = :version")
    suspend fun getAnalyzedCount(version: Int = SongMoodEntity.CURRENT_ANALYSIS_VERSION): Int

    /** Local (on-device) songs that still need analysis at the current algorithm version. */
    @Query(
        """
        SELECT s.id AS id, s.content_uri_string AS content_uri_string, s.genre AS genre, s.duration AS duration
        FROM songs s
        WHERE s.source_type = 0
          AND s.id NOT IN (
              SELECT song_id FROM song_moods WHERE analysis_version = :version
          )
        LIMIT :limit
        """
    )
    suspend fun getSongsNeedingAnalysis(
        limit: Int,
        version: Int = SongMoodEntity.CURRENT_ANALYSIS_VERSION
    ): List<MoodAnalysisSongRow>

    @Query(
        """
        SELECT COUNT(*) FROM songs WHERE source_type = 0
        """
    )
    suspend fun getLocalSongCount(): Int

    /** All local songs, for matching against an imported legacy-analysis file by filename. */
    @Query(
        """
        SELECT id, file_path, duration, genre FROM songs WHERE source_type = 0
        """
    )
    suspend fun getLocalSongsForImportMatch(): List<MoodImportMatchRow>

    /**
     * Full mood rows with a completed on-device "audio" analysis (as opposed to a genre-only or
     * failed row) — used to export the on-device analysis back out in the same shape
     * [com.theveloper.pixelplay.data.repository.LegacyLibraryImporter] can read in. `SELECT *`
     * here (rather than naming individual feature columns) is deliberate: it maps straight onto
     * [SongMoodEntity]'s existing column mapping instead of needing this query kept in sync with
     * that entity's internal column names.
     */
    @Query("SELECT * FROM song_moods WHERE source = :source")
    suspend fun getAnalyzedForExport(source: String = SongMoodEntity.SOURCE_AUDIO): List<SongMoodEntity>

    @Query("DELETE FROM song_moods WHERE song_id NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphaned()

    @Query("DELETE FROM song_moods")
    suspend fun clearAll()
}
