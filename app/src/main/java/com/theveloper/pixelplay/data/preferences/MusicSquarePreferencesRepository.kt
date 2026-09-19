package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.theveloper.pixelplay.data.audio.MoodAnalyzer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences for the Music Square onboarding flow: whether the "welcome" pop-up (import vs.
 * analyze locally) has been resolved yet, and how much of each track local analysis should
 * sample when the user picks "analyze locally".
 */
@Singleton
class MusicSquarePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("music_square_onboarding_completed")
        val ANALYSIS_SNIPPET_SECONDS = intPreferencesKey("music_square_analysis_snippet_seconds")
        val ANALYSIS_START_OFFSET_PERCENT = intPreferencesKey("music_square_analysis_start_offset_percent")
    }

    /** True once the user has picked "import" or "analyze locally" from the welcome pop-up. */
    val hasCompletedOnboardingFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.ONBOARDING_COMPLETED] ?: false
    }

    /** Seconds of each track to sample for local analysis; [MoodAnalyzer.FULL_TRACK] (0) means the whole song. */
    val analysisSnippetSecondsFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[Keys.ANALYSIS_SNIPPET_SECONDS] ?: MoodAnalyzer.DEFAULT_SNIPPET_SECONDS
    }

    /**
     * How far into each track (as a percentage of its duration, 0-90) local analysis should
     * start sampling from — lets a snippet skip past a long intro instead of always starting
     * at 0s. Has no effect when the snippet length is [MoodAnalyzer.FULL_TRACK], since the
     * whole track is analyzed either way. Defaults to [MoodAnalyzer.DEFAULT_START_OFFSET_FRACTION]
     * (30%) so a user who never opens this setting keeps the same intro-skipping behavior
     * local analysis always had, rather than silently switching to "from the start".
     */
    val analysisStartOffsetPercentFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[Keys.ANALYSIS_START_OFFSET_PERCENT]
            ?: (MoodAnalyzer.DEFAULT_START_OFFSET_FRACTION * 100).toInt()
    }

    suspend fun setOnboardingCompleted() =
        dataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_COMPLETED] = true
        }

    suspend fun setAnalysisSnippetSeconds(seconds: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.ANALYSIS_SNIPPET_SECONDS] = seconds.coerceAtLeast(0)
        }

    suspend fun setAnalysisStartOffsetPercent(percent: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.ANALYSIS_START_OFFSET_PERCENT] = percent.coerceIn(0, 90)
        }
}
