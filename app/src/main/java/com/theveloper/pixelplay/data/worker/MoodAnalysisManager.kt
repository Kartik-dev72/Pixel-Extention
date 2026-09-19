package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of the background Music Square analysis pass. */
data class MoodAnalysisProgress(
    val isRunning: Boolean = false,
    val analyzed: Int = 0,
    val total: Int = 0
) {
    val fraction: Float get() = if (total > 0) (analyzed.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isComplete: Boolean get() = total > 0 && analyzed >= total
}

@Singleton
class MoodAnalysisManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /** Kicks off (or resumes) background analysis of any songs not yet analyzed. Safe to call repeatedly. */
    fun ensureAnalysisRunning() {
        workManager.enqueueUniqueWork(
            MoodAnalysisWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            MoodAnalysisWorker.analysisWork()
        )
    }

    val progress: Flow<MoodAnalysisProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(MoodAnalysisWorker.WORK_NAME)
            .map { infos ->
                val info = infos.firstOrNull()
                if (info == null) {
                    MoodAnalysisProgress()
                } else {
                    val data = if (info.state == WorkInfo.State.SUCCEEDED) info.outputData else info.progress
                    MoodAnalysisProgress(
                        isRunning = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                        analyzed = data.getInt(MoodAnalysisWorker.PROGRESS_CURRENT, 0),
                        total = data.getInt(MoodAnalysisWorker.PROGRESS_TOTAL, 0)
                    )
                }
            }
}
