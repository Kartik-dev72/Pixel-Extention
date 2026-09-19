package com.theveloper.pixelplay.data.repository

import android.content.Context
import android.net.Uri
import com.theveloper.pixelplay.data.database.SongMoodDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of exporting the on-device Music Square analysis to a desktop-tool-compatible snapshot. */
data class LegacyExportResult(
    val exported: Int,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null
}

@Serializable
private data class ExportLibraryFile(
    val root: String? = null,
    val tracks: Map<String, ExportTrack>
)

@Serializable
private data class ExportTrack(
    val ok: Boolean,
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
 * Exports the on-device Music Square analysis to the same `library.json` shape produced (and
 * consumed) by the original desktop tool — the mirror image of [LegacyLibraryImporter]. Only
 * songs with a full on-device "audio" analysis are included; genre-only/failed rows carry
 * nothing worth round-tripping.
 *
 * File path/duration come from [SongMoodDao.getLocalSongsForImportMatch], joined against
 * [SongMoodDao.getAnalyzedForExport] by song id in Kotlin — an exact id match, so (unlike the
 * importer's necessarily fuzzy filename-based matching on the way back in) there's no ambiguity
 * here about which song a row belongs to.
 *
 * Brightness/tonality are stored on-device already normalized (brightness against the track's
 * Nyquist frequency, tonality as a -1..1 major/minor score) without keeping the original sample
 * rate around, so this assumes a 44.1kHz basis when converting brightness back to a spectral
 * centroid. That's only an approximation of the track's *actual* original centroid, but since
 * this app's own [LegacyLibraryImporter] re-derives brightness from centroid/samplerate the same
 * way on the way back in, exporting and re-importing with this app reproduces the same
 * brightness value exactly — the assumption only matters if the file is opened by the real
 * desktop tool.
 */
@Singleton
class LegacyLibraryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songMoodDao: SongMoodDao,
    private val json: Json
) {
    suspend fun export(uri: Uri): LegacyExportResult = withContext(Dispatchers.IO) {
        try {
            val moodRows = songMoodDao.getAnalyzedForExport()
            if (moodRows.isEmpty()) {
                return@withContext LegacyExportResult(0, "No analyzed tracks to export yet.")
            }

            val songMetaById = songMoodDao.getLocalSongsForImportMatch().associateBy { it.id }
            val nyquist = ASSUMED_SAMPLE_RATE / 2f

            val tracks = moodRows.mapNotNull { row ->
                val meta = songMetaById[row.songId] ?: return@mapNotNull null
                row.songId.toString() to ExportTrack(
                    ok = true,
                    tempo = row.tempoBpm,
                    rms = row.rmsLoudness,
                    onsetRate = row.onsetDensity,
                    centroid = row.brightness?.times(nyquist),
                    modeScore = row.tonality,
                    duration = meta.duration / 1000f,
                    samplerate = ASSUMED_SAMPLE_RATE,
                    relPath = meta.filePath
                )
            }.toMap()

            if (tracks.isEmpty()) {
                return@withContext LegacyExportResult(0, "No analyzed tracks to export yet.")
            }

            val text = json.encodeToString(ExportLibraryFile(tracks = tracks))
            val wrote = context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(text.toByteArray())
                true
            } ?: false

            if (!wrote) {
                return@withContext LegacyExportResult(0, "Couldn't open that file for writing.")
            }

            LegacyExportResult(exported = tracks.size)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to export Music Square library")
            LegacyExportResult(0, "Export failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "LegacyLibraryExporter"
        private const val ASSUMED_SAMPLE_RATE = 44100
    }
}
