package com.kazemail.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kazemail.app.KazeMailApp
import com.kazemail.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class InitialBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1L)
        if (accountId <= 0L) return Result.failure()

        ensureSyncChannel(applicationContext)
        runCatching {
            setForeground(createForegroundInfo(applicationContext.getString(R.string.sync_notification_starting)))
        }

        val container = (applicationContext as KazeMailApp).container
        return try {
            container.syncEngine.runPhase1InboxSync(accountId)
            container.syncEngine.runPhase2Backfill(
                accountId = accountId,
                shouldContinue = { !isStopped },
                onProgress = { progress ->
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_FOLDER to progress.folderPath,
                            KEY_PROGRESS_SYNCED to progress.syncedInFolder,
                            KEY_PROGRESS_TOTAL to progress.serverTotalInFolder,
                        ),
                    )
                    runCatching {
                        setForeground(
                            createForegroundInfo(
                                "${progress.folderPath}: ${progress.syncedInFolder}/${progress.serverTotalInFolder}",
                            ),
                        )
                    }
                },
            )
            if (isStopped) Result.retry() else Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(applicationContext.getString(R.string.sync_notification_starting))

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val cancelPendingIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kazemail)
            .setColor(NOTIFICATION_ACCENT_COLOR)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.sync_cancel),
                cancelPendingIntent,
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_PROGRESS_FOLDER = "progress_folder"
        const val KEY_PROGRESS_SYNCED = "progress_synced"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        private const val CHANNEL_ID = "kazemail_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ACCENT_COLOR = 0xFF0B556B.toInt()
        private const val MAX_RETRIES = 6

        fun ensureSyncChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.sync_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }
}

internal data class IncrementalSyncBatchSummary(
    val succeededCount: Int,
    val failedCount: Int,
)

internal suspend fun runIncrementalSyncBatch(
    accountIds: List<Long>,
    isStopped: () -> Boolean,
    syncAccount: suspend (Long) -> Unit,
) : IncrementalSyncBatchSummary {
    var succeeded = 0
    var failed = 0
    for (accountId in accountIds) {
        if (isStopped()) break
        try {
            syncAccount(accountId)
            succeeded += 1
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            failed += 1
        }
    }
    return IncrementalSyncBatchSummary(succeededCount = succeeded, failedCount = failed)
}

class IncrementalSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KazeMailApp).container
        val targetAccountId = inputData.getLong(InitialBackfillWorker.KEY_ACCOUNT_ID, -1L)
        return try {
            val db = container.awaitDatabase()
            val accounts = if (targetAccountId > 0L) {
                listOfNotNull(db.accountDao().getById(targetAccountId))
            } else {
                db.accountDao().getAll()
            }.filter { !it.authType.equals("LOCAL", ignoreCase = true) }

            val notificationsEnabled = container.appPreferences.notificationsEnabled.value
            val summary = runIncrementalSyncBatch(
                accountIds = accounts.map { it.accountId },
                isStopped = { isStopped },
            ) { accId ->
                val result = container.syncEngine.runIncrementalSync(accId)
                if (notificationsEnabled && result.newInboxUnreadMessages.isNotEmpty()) {
                    container.notificationManager.notifyNewMessages(
                        accountId = accId,
                        newUnreadMessages = result.newInboxUnreadMessages,
                    )
                }
            }

            when {
                isStopped && summary.failedCount > 0 -> Result.retry()
                summary.failedCount > 0 && runAttemptCount < 4 -> Result.retry()
                summary.failedCount > 0 && summary.succeededCount == 0 -> Result.failure()
                else -> Result.success()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}

class OutboxSendWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KazeMailApp).container
        return try {
            val summary = container.outboxManager.processPendingOutbox()
            if (summary.shouldScheduleRetry && runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

data class BackfillWorkProgress(
    val isRunning: Boolean,
    val folderPath: String?,
    val syncedInFolder: Int,
    val totalInFolder: Int,
)

class SyncScheduler(private val context: Context) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun observeBackfillWorkInfo(accountId: Long): Flow<BackfillWorkProgress?> =
        workManager.getWorkInfosForUniqueWorkFlow("kazemail_backfill_$accountId")
            .map { infos ->
                val active = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: return@map null
                BackfillWorkProgress(
                    isRunning = active.state == WorkInfo.State.RUNNING,
                    folderPath = active.progress.getString(InitialBackfillWorker.KEY_PROGRESS_FOLDER),
                    syncedInFolder = active.progress.getInt(InitialBackfillWorker.KEY_PROGRESS_SYNCED, 0),
                    totalInFolder = active.progress.getInt(InitialBackfillWorker.KEY_PROGRESS_TOTAL, 0),
                )
            }

    fun enqueueInitialBackfill(accountId: Long, wifiOnly: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<InitialBackfillWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(InitialBackfillWorker.KEY_ACCOUNT_ID to accountId))
            .build()

        workManager.enqueueUniqueWork(
            "kazemail_backfill_$accountId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelBackfill(accountId: Long) {
        workManager.cancelUniqueWork("kazemail_backfill_$accountId")
    }

    fun enqueueImmediateIncrementalSync(accountId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(InitialBackfillWorker.KEY_ACCOUNT_ID to accountId))
            .build()

        workManager.enqueueUniqueWork(
            "kazemail_inc_once_$accountId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enqueueOutboxSend() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OutboxSendWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            OUTBOX_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun ensurePeriodicIncrementalSync(intervalMinutes: Long = 15L) {
        if (intervalMinutes <= 0L) {
            workManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
            return
        }
        val safeMinutes = intervalMinutes.coerceAtLeast(15L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<IncrementalSyncWorker>(safeMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun updatePeriodicIncrementalSync(intervalMinutes: Long) {
        if (intervalMinutes <= 0L) {
            workManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
            return
        }
        val safeMinutes = intervalMinutes.coerceAtLeast(15L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<IncrementalSyncWorker>(safeMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        private const val PERIODIC_SYNC_WORK_NAME = "kazemail_periodic_incremental_sync"
        private const val OUTBOX_UNIQUE_WORK_NAME = "kazemail_outbox_send"
    }
}
