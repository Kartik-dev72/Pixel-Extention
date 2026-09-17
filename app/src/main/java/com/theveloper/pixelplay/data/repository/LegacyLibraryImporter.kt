package com.theveloper.pixelplay.data.repository

import android.content.Context
import android.net.Uri
import com.theveloper.pixelplay.data.audio.MoodGenrePriors
import com.theveloper.pixelplay.data.database.MoodImportMatchRow
import com.theveloper.pixelplay.data.database.SongMoodDao
import com.theveloper.pixelplay.data.database.SongMoodEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Outcome of importing a legacy desktop-tool analysis snapshot. */
data class LegacyImportResult(
    val matched: Int,
    val totalInFile: Int,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null
}

@Serializable
private data class LegacyLibraryFile(
    val root: String? = null,
    val tracks: Map<String, LegacyTrack> = emptyMap()
)

@Serializable
private data class LegacyTrack(
    val ok: Boolean = false,
    val tempo: Float? = null,
    val rms: Float? = null,
    @SerialName("onset_rate") val onsetRate: Float? = null,
    val centroid: Float? = null,
    @SerialName("mode_score") val modeScore: Float? = null,
    val duration: Float? = null,
    val samplerate: Int? = null,
    @SerialName("rel_path") val relPath: String? = null
)

/**
 * Imports a pre-analyzed library snapshot exported by the original desktop Music Square tool
 * (tempo/rms/onset_rate/centroid/mode_score per track, keyed by a hash id — see the tool's
 * `library.json`), matching each entry to a local on-device song so it doesn't need to be
 * re-analyzed by [com.theveloper.pixelplay.data.audio.MoodAnalyzer].
 *
 * Matching is filename-based: the desktop tool and the device library are assumed to share the
 * same underlying files (synced/copied from the same folder), so tracks are matched by the
 * basename of their file path. When several local songs share a basename, the one whose
 * duration is closest to the imported duration wins. Tracks that can't be matched are simply
 * skipped — they'll pick up real on-device analysis the next time the background worker runs.
 */
@Singleton
class LegacyLibraryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songMoodDao: SongMoodDao,
    private val json: Json
) {
    suspend fun import(uri: Uri): LegacyImportResult = withContext(Dispatchers.IO) {
        val snapshot = try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: return@withContext LegacyImportResult(0, 0, "Couldn't open that file.")
            json.decodeFromString<LegacyLibraryFile>(text)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to parse legacy library file")
            return@withContext LegacyImportResult(0, 0, "That doesn't look like a Music Square export.")
        }

        val importableTracks = snapshot.tracks.values.filter { it.ok && it.relPath != null }
        if (importableTracks.isEmpty()) {
            return@withContext LegacyImportResult(0, snapshot.tracks.size, "No analyzed tracks found in that file.")
        }

        val localSongs = songMoodDao.getLocalSongsForImportMatch()
        // basename (lowercase) -> candidate local songs sharing that filename
        val byBasename = localSongs.groupBy { basename(it.filePath) }

        val entities = ArrayList<SongMoodEntity>(importableTracks.size)
        var matched = 0

        for (track in importableTracks) {
            val relPath = track.relPath ?: continue
            val candidates = byBasename[basename(relPath)] ?: continue
            val best = pickBestMatch(candidates, track.duration) ?: continue

            entities += buildEntity(best, track)
            matched++
        }

        if (entities.isNotEmpty()) {
            songMoodDao.upsertAll(entities)
        }

        LegacyImportResult(matched = matched, totalInFile = snapshot.tracks.size)
    }

    private fun pickBestMatch(candidates: List<MoodImportMatchRow>, targetDurationSeconds: Float?): MoodImportMatchRow? {
        if (candidates.size == 1) return candidates[0]
        if (targetDurationSeconds == null) return candidates.first()
        return candidates.minByOrNull { abs(it.duration / 1000f - targetDurationSeconds) }
    }

    private fun buildEntity(match: MoodImportMatchRow, track: LegacyTrack): SongMoodEntity {
        val prior = MoodGenrePriors.priorFor(match.genre)
        val hasFullAudioFeatures = track.tempo != null && track.rms != null &&
            track.onsetRate != null && track.centroid != null && track.modeScore != null

        return if (hasFullAudioFeatures) {
            val nyquist = ((track.samplerate ?: 44100) / 2f).coerceAtLeast(1f)
            SongMoodEntity(
                songId = match.id,
                tempoBpm = track.tempo,
                rmsLoudness = track.rms,
                onsetDensity = track.onsetRate,
                brightness = (track.centroid!! / nyquist).coerceIn(0f, 1f),
                tonality = track.modeScore!!.coerceIn(-1f, 1f),
                genreEnergyPrior = prior.first,
                genreValencePrior = prior.second,
                source = SongMoodEntity.SOURCE_AUDIO
            )
        } else {
            SongMoodEntity(
                songId = match.id,
                tempoBpm = null,
                rmsLoudness = null,
                onsetDensity = null,
                brightness = null,
                tonality = null,
                genreEnergyPrior = prior.first,
                genreValencePrior = prior.second,
                source = SongMoodEntity.SOURCE_FAILED
            )
        }
    }

    private fun basename(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').lowercase()

    companion object {
        private const val TAG = "LegacyLibraryImporter"
    }
}
