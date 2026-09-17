package com.theveloper.pixelplay.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.audio.MoodAnalyzer
import com.theveloper.pixelplay.data.preferences.MusicSquarePreferencesRepository
import com.theveloper.pixelplay.data.repository.LegacyExportResult
import com.theveloper.pixelplay.data.repository.LegacyImportResult
import com.theveloper.pixelplay.data.repository.LegacyLibraryExporter
import com.theveloper.pixelplay.data.repository.LegacyLibraryImporter
import com.theveloper.pixelplay.data.repository.MoodPoint
import com.theveloper.pixelplay.data.repository.MoodRepository
import com.theveloper.pixelplay.data.worker.MoodAnalysisManager
import com.theveloper.pixelplay.data.worker.MoodAnalysisProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the "import from the desktop tool" flow, surfaced to the Music Square screen as a snackbar/banner. */
sealed interface LegacyImportUiState {
    data object Idle : LegacyImportUiState
    data object Importing : LegacyImportUiState
    data class Done(val result: LegacyImportResult) : LegacyImportUiState
}

/** State of the "export to the desktop tool's format" flow, surfaced as a toast. */
sealed interface LegacyExportUiState {
    data object Idle : LegacyExportUiState
    data object Exporting : LegacyExportUiState
    data class Done(val result: LegacyExportResult) : LegacyExportUiState
}

@HiltViewModel
class MusicSquareViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val moodAnalysisManager: MoodAnalysisManager,
    private val preferencesRepository: MusicSquarePreferencesRepository,
    private val legacyLibraryImporter: LegacyLibraryImporter,
    private val legacyLibraryExporter: LegacyLibraryExporter
) : ViewModel() {

    val moodPoints: StateFlow<List<MoodPoint>> = moodRepository.moodPointsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val analysisProgress: StateFlow<MoodAnalysisProgress> = moodAnalysisManager.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MoodAnalysisProgress())

    /**
     * Whether the "Welcome to Music Square" pop-up has been resolved yet (import or analyze
     * locally chosen). Null while the underlying preference is still loading, so the screen can
     * avoid flashing the pop-up — or starting analysis — before it knows the real answer.
     */
    val hasCompletedOnboarding: StateFlow<Boolean?> = preferencesRepository.hasCompletedOnboardingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _importState = MutableStateFlow<LegacyImportUiState>(LegacyImportUiState.Idle)
    val importState: StateFlow<LegacyImportUiState> = _importState.asStateFlow()

    private val _exportState = MutableStateFlow<LegacyExportUiState>(LegacyExportUiState.Idle)
    val exportState: StateFlow<LegacyExportUiState> = _exportState.asStateFlow()

    /**
     * Whether the Music Square settings dialog (import / analyze locally / export) is open.
     * Lives here rather than as local Compose state so the cog in the top library action row
     * (LibraryActionRow, outside MusicSquareScreen's own composable tree) can open it too —
     * both grab this same screen-scoped ViewModel instance via hiltViewModel().
     */
    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    fun openSettingsDialog() {
        _showSettingsDialog.value = true
    }

    fun closeSettingsDialog() {
        _showSettingsDialog.value = false
    }

    /** Current analysis settings, so re-opening "Analyze locally" from Settings starts from what's already saved rather than always resetting to the defaults. */
    val analysisSnippetSeconds: StateFlow<Int> = preferencesRepository.analysisSnippetSecondsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MoodAnalyzer.DEFAULT_SNIPPET_SECONDS)

    val analysisStartOffsetPercent: StateFlow<Int> = preferencesRepository.analysisStartOffsetPercentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Starts (or resumes) background analysis. Cheap to call from onStart / a button — it's a no-op if already running or done. */
    fun ensureAnalysisRunning() {
        moodAnalysisManager.ensureAnalysisRunning()
    }

    /** Marks onboarding as resolved without showing the pop-up — used when the library already has mood data from before this flow existed. Safe to call repeatedly. */
    fun markOnboardingCompleted() {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted()
        }
    }

    /**
     * User picked "Analyze locally" (from the welcome pop-up or from Settings) with the given
     * snippet length ([MoodAnalyzer.FULL_TRACK] = whole song) and, for a partial snippet, how
     * far into each track (as a percentage of its duration) to start sampling from.
     *
     * NOTE: [MoodAnalysisManager.ensureAnalysisRunning] only processes songs that are missing a
     * mood row or on an old [com.theveloper.pixelplay.data.database.SongMoodEntity.CURRENT_ANALYSIS_VERSION].
     * Changing these settings from Settings (as opposed to first-time onboarding) won't
     * re-analyze songs that were already successfully analyzed under the old settings unless
     * the DAO/version-bump logic is extended to invalidate them — see chat notes.
     */
    fun chooseLocalAnalysis(snippetSeconds: Int, startOffsetPercent: Int = 0) {
        viewModelScope.launch {
            preferencesRepository.setAnalysisSnippetSeconds(snippetSeconds)
            preferencesRepository.setAnalysisStartOffsetPercent(startOffsetPercent)
            preferencesRepository.setOnboardingCompleted()
            moodAnalysisManager.ensureAnalysisRunning()
        }
    }

    /** User picked "Import my data" from the welcome pop-up and selected a file. */
    fun importLegacyLibrary(uri: Uri) {
        viewModelScope.launch {
            _importState.value = LegacyImportUiState.Importing
            val result = legacyLibraryImporter.import(uri)
            _importState.value = LegacyImportUiState.Done(result)
            preferencesRepository.setOnboardingCompleted()
            // Anything the import couldn't match still needs on-device analysis.
            moodAnalysisManager.ensureAnalysisRunning()
        }
    }

    fun dismissImportResult() {
        _importState.value = LegacyImportUiState.Idle
    }

    /** User picked "Export my data" from Settings and chose where to save the file. */
    fun exportLegacyLibrary(uri: Uri) {
        viewModelScope.launch {
            _exportState.value = LegacyExportUiState.Exporting
            val result = legacyLibraryExporter.export(uri)
            _exportState.value = LegacyExportUiState.Done(result)
        }
    }

    fun dismissExportResult() {
        _exportState.value = LegacyExportUiState.Idle
    }

    /**
     * Builds an ordered song-id list for a playlist "seeded" from [seedSongIds]: the seeds
     * themselves lead the list (in the order they were picked), followed by their nearest
     * neighbors on the Music Square, taken round-robin across all seeds so the result reflects
     * every seed's area rather than just the average of their moods — e.g. two very different
     * seeds ("chill" + "intense") produce a mix spanning both areas, not one that lands in a
     * bland middle ground neither seed actually sounds like.
     *
     * Seeds that haven't been mood-analyzed yet (or aren't in the library at all) are silently
     * skipped rather than failing the whole build.
     */
    fun buildSeedPlaylist(seedSongIds: List<String>, targetSize: Int = 30): List<String> {
        val points = moodPoints.value
        if (points.isEmpty() || seedSongIds.isEmpty()) return emptyList()

        val pointsById = points.associateBy { it.song.id }
        val seedPoints = seedSongIds.mapNotNull { pointsById[it] }
        if (seedPoints.isEmpty()) return emptyList()

        val result = LinkedHashSet<String>()
        // Seeds themselves lead the playlist, in the order they were picked.
        seedSongIds.forEach { id -> if (pointsById.containsKey(id)) result.add(id) }

        // One nearest-neighbor iterator per seed, walked round-robin so no single seed's
        // neighborhood dominates the result.
        val neighborIterators = seedPoints.map { seed ->
            points.asSequence()
                .filter { it.song.id != seed.song.id }
                .sortedBy { candidate ->
                    val dx = candidate.x - seed.x
                    val dy = candidate.y - seed.y
                    dx * dx + dy * dy
                }
                .map { it.song.id }
                .iterator()
        }

        while (result.size < targetSize) {
            var addedAnyThisRound = false
            for (iterator in neighborIterators) {
                if (result.size >= targetSize) break
                while (iterator.hasNext()) {
                    val candidateId = iterator.next()
                    if (result.add(candidateId)) {
                        addedAnyThisRound = true
                        break
                    }
                }
            }
            if (!addedAnyThisRound) break // every neighbor list is exhausted
        }

        return result.toList()
    }
}
