package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw, un-normalized audio-feature measurements for a single local song, used to power the
 * Music Square screen (see [com.theveloper.pixelplay.data.audio.MoodAnalyzer]).
 *
 * A song's position comes entirely from on-device audio analysis (tempo, loudness, rhythmic
 * density, spectral brightness, major/minor tonality) sampled from the decoded audio — there is
 * deliberately no genre-tag fallback: a song that hasn't been (or can't be) analyzed simply
 * doesn't appear on the square rather than being placed by a metadata guess. (An earlier version
 * of this did blend in a genre-derived prior; the `genre_energy_prior`/`genre_valence_prior`
 * columns below are unused leftovers from that, kept only so existing installs don't need a
 * schema migration — always written as 0 now, and ignored on read.)
 *
 * Coordinates are NOT stored normalized here — [com.theveloper.pixelplay.data.repository.MoodRepository]
 * z-score-normalizes raw values against the whole analyzed library at read time, mirroring how
 * the original desktop tool spreads a library across the square relative to itself rather than
 * against fixed absolute thresholds.
 */
@Entity(
    tableName = "song_moods",
    indices = [
        Index(value = ["analysis_version"], unique = false),
        Index(value = ["source"], unique = false)
    ]
)
data class SongMoodEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,

    /** Estimated tempo in beats-per-minute from onset-autocorrelation. Null if analysis failed. */
    @ColumnInfo(name = "tempo_bpm")
    val tempoBpm: Float?,

    /** Root-mean-square loudness of the sampled window, roughly 0..1. */
    @ColumnInfo(name = "rms_loudness")
    val rmsLoudness: Float?,

    /** Onset (rhythmic event) density, events per second over the sampled window. */
    @ColumnInfo(name = "onset_density")
    val onsetDensity: Float?,

    /** Spectral centroid normalized by Nyquist frequency, 0 (dark) .. 1 (bright). */
    @ColumnInfo(name = "brightness")
    val brightness: Float?,

    /** Major-vs-minor tonality correlation score, roughly -1 (minor) .. +1 (major). */
    @ColumnInfo(name = "tonality")
    val tonality: Float?,

    /** Unused leftover from a removed genre-fallback feature — always 0 now, ignored on read. Kept only to avoid a schema migration. */
    @ColumnInfo(name = "genre_energy_prior")
    val genreEnergyPrior: Float = 0f,

    /** Unused leftover from a removed genre-fallback feature — always 0 now, ignored on read. Kept only to avoid a schema migration. */
    @ColumnInfo(name = "genre_valence_prior")
    val genreValencePrior: Float = 0f,

    /** "audio" if real decode+DSP succeeded; anything else (see [SOURCE_FAILED]) means this song has no position and won't be shown on the square. */
    @ColumnInfo(name = "source", defaultValue = "'genre_only'")
    val source: String = SOURCE_FAILED,

    /** Bumped whenever [com.theveloper.pixelplay.data.audio.MoodAnalyzer]'s algorithm changes,
     * so previously analyzed songs get re-analyzed instead of keeping stale features. */
    @ColumnInfo(name = "analysis_version", defaultValue = "1")
    val analysisVersion: Int = CURRENT_ANALYSIS_VERSION,

    @ColumnInfo(name = "analyzed_at")
    val analyzedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_AUDIO = "audio"

        /** Marks a song that was attempted and couldn't be decoded/analyzed (corrupt file, DRM,
         * unsupported codec) — stops the worker retrying it every single pass, but the song is
         * excluded from the square entirely rather than being positioned by a guess. Value kept
         * as the historical "genre_only" string for schema compatibility with existing rows. */
        const val SOURCE_FAILED = "genre_only"

        const val CURRENT_ANALYSIS_VERSION = 1
    }
}
