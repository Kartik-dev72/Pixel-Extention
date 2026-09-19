package com.theveloper.pixelplay.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.audio.MoodAnalyzer
import com.theveloper.pixelplay.data.audio.MoodGenrePriors
import com.theveloper.pixelplay.data.database.SongMoodDao
import com.theveloper.pixelplay.data.database.SongMoodEntity
import com.theveloper.pixelplay.data.preferences.MusicSquarePreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Analyzes local songs for the Music Square in the background, a batch at a time, so opening
 * the screen on a large library doesn't block on decoding every track up front.
 *
 * Each song gets a "genre_only" row immediately (cheap, so it appears on the square right away)
 * and is then upgraded to a full "audio" row once real decode+DSP analysis completes for it.
 * Re-running this worker only touches songs that are missing or at an older
 * [SongMoodEntity.CURRENT_ANALYSIS_VERSION].
 *
 * Promoted to a foreground service (via [setForeground]) whenever there's actually outstanding
 * work, with an ongoing "Analyzing your library" notification showing progress. A plain
 * background [CoroutineWorker] has no such promotion and is subject to Doze/App Standby
 * throttling — decoding audio is CPU-heavy enough that without this, analysis visibly stalls
 * the moment the screen turns off or the app is backgrounded, since the OS is free to suspend a
 * regular deferred job's CPU time in that state. A foreground service is exempt from that.
 */
@HiltWorker
class MoodAnalysisWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val songMoodDao: SongMoodDao,
    private val preferencesRepository: MusicSquarePreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    private val analyzerContext: Context = appContext

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val snippetSeconds = preferencesRepository.analysisSnippetSecondsFlow.first()
            val startOffsetPercent = preferencesRepository.analysisStartOffsetPercentFlow.first()
            val analyzer = MoodAnalyzer(analyzerContext, snippetSeconds, startOffsetPercent / 100f)

            var analyzedSoFar = songMoodDao.getAnalyzedCount()
            val totalToAnalyze = songMoodDao.getLocalSongCount()

            setProgress(workDataOf(PROGRESS_CURRENT to analyzedSoFar, PROGRESS_TOTAL to totalToAnalyze))

            // Only promote to a foreground service if there's actually outstanding work — a
            // no-op run (everything already analyzed) shouldn't flash a notification.
            if (analyzedSoFar < totalToAnalyze) {
                ensureNotificationChannel()
                setForeground(foregroundInfo(analyzedSoFar, totalToAnalyze))
            }

            val concurrency = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val semaphore = Semaphore(concurrency)

            while (true) {
                if (isStopped) return@withContext Result.success()

                val batch = songMoodDao.getSongsNeedingAnalysis(limit = BATCH_SIZE)
                if (batch.isEmpty()) break

                coroutineScope {
                    val jobs = batch.map { row ->
                        async {
                            semaphore.withPermit {
                                val prior = MoodGenrePriors.priorFor(row.genre)
                                val features = try {
                                    analyzer.analyze(row.contentUriString, row.duration)
                                } catch (t: Throwable) {
                                    Timber.tag(TAG).w(t, "Analysis threw for song ${row.id}")
                                    null
                                }
                                if (features != null) {
                                    SongMoodEntity(
                                        songId = row.id,
                                        tempoBpm = features.tempoBpm,
                                        rmsLoudness = features.rmsLoudness,
                                        onsetDensity = features.onsetDensity,
                                        brightness = features.brightness,
                                        tonality = features.tonality,
                                        genreEnergyPrior = prior.first,
                                        genreValencePrior = prior.second,
                                        source = SongMoodEntity.SOURCE_AUDIO
                                    )
                                } else {
                                    SongMoodEntity(
                                        songId = row.id,
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
                        }
                    }
                    val results = jobs.awaitAll()
                    songMoodDao.upsertAll(results)
                }

                analyzedSoFar += batch.size
                setProgress(workDataOf(PROGRESS_CURRENT to analyzedSoFar, PROGRESS_TOTAL to totalToAnalyze))
                // Keep the foreground notification's progress bar in step with the work — cheap
                // to call repeatedly; WorkManager just re-notifies with the updated Notification.
                setForeground(foregroundInfo(analyzedSoFar, totalToAnalyze))
            }

            songMoodDao.deleteOrphaned()
            Result.success()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "MoodAnalysisWorker failed")
            Result.failure()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = analyzerContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            analyzerContext.getString(R.string.music_square_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW // Progress updates only — no sound/heads-up.
        )
        manager.createNotificationChannel(channel) // Idempotent: a no-op if it already exists.
    }

    private fun buildNotification(analyzed: Int, total: Int): Notification {
        return NotificationCompat.Builder(analyzerContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(analyzerContext.getString(R.string.music_square_notification_title))
            .setContentText(
                analyzerContext.getString(R.string.music_square_analyzing_progress, analyzed, total)
            )
            .setSmallIcon(R.drawable.rounded_grid_view_24)
            .setProgress(total, analyzed, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Don't re-alert on every progress update.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun foregroundInfo(analyzed: Int, total: Int): ForegroundInfo {
        val notification = buildNotification(analyzed, total)
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val WORK_NAME = "com.theveloper.pixelplay.data.worker.MoodAnalysisWorker"
        private const val TAG = "MoodAnalysisWorker"
        private const val BATCH_SIZE = 12

        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"

        private const val NOTIFICATION_CHANNEL_ID = "music_square_analysis"
        private const val NOTIFICATION_ID = 4711

        fun analysisWork() = OneTimeWorkRequestBuilder<MoodAnalysisWorker>().build()

        val existingWorkPolicy: ExistingWorkPolicy get() = ExistingWorkPolicy.KEEP
    }
}
