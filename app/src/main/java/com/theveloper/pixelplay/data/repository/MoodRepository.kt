package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongMoodDao
import com.theveloper.pixelplay.data.database.SongMoodEntity
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/** A song's position on the Music Square, normalized to 0..1 on both axes. Only produced for songs with real audio analysis — there's no metadata-based fallback position. */
data class MoodPoint(
    val song: Song,
    /** 0 = calm, 1 = exciting. */
    val x: Float,
    /** 0 = sad, 1 = joyful. */
    val y: Float
)

@Singleton
class MoodRepository @Inject constructor(
    private val songMoodDao: SongMoodDao,
    private val musicDao: MusicDao
) {

    /** All *audio*-analyzed songs, positioned and normalized across the current set — songs that haven't been analyzed yet (or failed to analyze) simply aren't in this list. */
    fun moodPointsFlow(): Flow<List<MoodPoint>> =
        songMoodDao.getAllFlow().map { moods -> buildMoodPoints(moods) }

    private suspend fun buildMoodPoints(moods: List<SongMoodEntity>): List<MoodPoint> {
        val audioMoods = moods.filter {
            it.source == SongMoodEntity.SOURCE_AUDIO &&
                it.tempoBpm != null && it.rmsLoudness != null && it.onsetDensity != null &&
                it.brightness != null && it.tonality != null
        }
        if (audioMoods.isEmpty()) return emptyList()

        // Z-score each raw feature across the analyzed library, matching the reference tool's
        // normalization exactly (it standardizes each feature — tempo, loudness, onset
        // strength, brightness, tonality — independently, rather than pre-squashing each one to
        // 0..1 first). Z-scoring is scale-invariant to any linear rescaling of its input, so
        // using our stored `brightness` (centroid/Nyquist) gives identical z-scores to using
        // raw centroid Hz directly — no accuracy lost by reusing the value already on hand.
        val tempoZ = zscores(audioMoods.map { it.tempoBpm!! })
        val rmsZ = zscores(audioMoods.map { it.rmsLoudness!! })
        val onsetZ = zscores(audioMoods.map { it.onsetDensity!! })
        val brightnessZ = zscores(audioMoods.map { it.brightness!! })
        val tonalityZ = zscores(audioMoods.map { it.tonality!! })

        // Reference formula: arousal = 0.45*tempo + 0.35*rms + 0.20*onset (calm<->exciting),
        // valence = 0.55*tonality + 0.45*brightness (sad<->joyful).
        val coords = HashMap<Long, Pair<Float, Float>>(audioMoods.size)
        for (i in audioMoods.indices) {
            val arousalZ = 0.45f * tempoZ[i] + 0.35f * rmsZ[i] + 0.20f * onsetZ[i]
            val valenceZ = 0.55f * tonalityZ[i] + 0.45f * brightnessZ[i]
            // tanh compresses into -1..1 with a soft saturation curve — most songs (within
            // roughly +/-2 std devs of the library's average) spread across most of that range,
            // with only genuine outliers pushed toward the true corners. This preserves the
            // library's real relative distances rather than artificially stretching everything
            // to fill the square evenly.
            val xRaw = kotlin.math.tanh(arousalZ / 1.6f)
            val yRaw = kotlin.math.tanh(valenceZ / 1.6f)
            coords[audioMoods[i].songId] = ((xRaw + 1f) / 2f) to ((yRaw + 1f) / 2f)
        }

        val songIds = audioMoods.map { it.songId }
        val songsById = musicDao.getSongsByIdsListSimple(songIds).associateBy { it.id }

        return audioMoods.mapNotNull { mood ->
            val songEntity = songsById[mood.songId] ?: return@mapNotNull null
            val (x, y) = coords.getValue(mood.songId)
            MoodPoint(song = songEntity.toSong(), x = x, y = y)
        }
    }

    /** Standard z-score ((x - mean) / population-std-dev), matching numpy's default (population, not sample, std). Returns all zeros if the population has ~no variance. */
    private fun zscores(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val mean = values.average().toFloat()
        val variance = values.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / values.size
        val std = sqrt(variance)
        return if (std < 1e-9f) values.map { 0f } else values.map { (it - mean) / std }
    }
}
