package com.aiquotamonitor.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepositoryProvider
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.model.QuotaSnapshot
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Refreshes both home-screen widgets from the same cached snapshot. Each widget is
 * updated inside its own try/catch so a composition or RemoteViews failure on one
 * tile never blocks (or desynchronizes) the other.
 */
class WidgetUpdater(
    private val compactUpdate: suspend () -> Unit,
    private val wideUpdate: suspend () -> Unit
) {

    /** Updates the `2x2` compact tile, then the `4x3` wide deck, isolating failures per widget. */
    suspend fun updateAllWidgets() {
        try {
            compactUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the other widget in sync even if this one fails.
        }
        try {
            wideUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The compact tile has already been refreshed.
        }
    }

    companion object {
        fun forContext(context: Context): WidgetUpdater = WidgetUpdater(
            compactUpdate = { QuotaCompactWidget().updateAll(context) },
            wideUpdate = { QuotaWideWidget().updateAll(context) }
        )

        /** Refreshes both widgets for [context] without letting one failure block the other. */
        suspend fun updateAllWidgets(context: Context) {
            forContext(context).updateAllWidgets()
        }
    }
}

/** Outcome of one background widget sync, used to bound WorkManager retries. */
sealed interface WidgetSyncResult {
    data object Success : WidgetSyncResult
    data object Unconfigured : WidgetSyncResult
    data object TransientFailure : WidgetSyncResult
}

/**
 * One background widget refresh: fetch a fresh snapshot when a transport is configured,
 * then repaint both widgets from the repository's cached snapshot.
 *
 * The repository is always asked for the background (`isManual = false`) Tailscale-live
 * path with the encrypted Gist relay fallback, so a successful live read is cached while
 * demo `SeedData` is never persisted. Both widgets are updated even when the transport is
 * unconfigured or the network fails, so their snapshot and age labels can never drift.
 */
class WidgetSyncCoordinator(
    private val settingsProvider: () -> SyncSettings,
    private val refresh: suspend () -> Result<QuotaSnapshot>,
    private val updateAllWidgets: suspend () -> Unit
) {

    suspend fun sync(): WidgetSyncResult {
        val settings = settingsProvider()
        if (!settings.hasUsableTransport) {
            runWidgetUpdate()
            return WidgetSyncResult.Unconfigured
        }

        val outcome = try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure<QuotaSnapshot>(IllegalStateException("Widget background refresh failed"))
        }

        runWidgetUpdate()
        return if (outcome.isSuccess) WidgetSyncResult.Success else WidgetSyncResult.TransientFailure
    }

    private suspend fun runWidgetUpdate() {
        try {
            updateAllWidgets()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A widget repaint must never turn a background sync into a hard failure.
        }
    }

    companion object {
        /** Production entry point used by [WidgetSyncWorker]. */
        suspend fun sync(context: Context): WidgetSyncResult = forContext(context).sync()

        /**
         * Maps a repository refresh result + resulting [ConnectionState] into a strict
         * live/relay outcome. `QuotaRepositoryImpl.refresh` returns `Result.success(cached)`
         * on offline/demo fallbacks, so we must inspect [connectionState] to recognize
         * transient network failures while treating an in-flight foreground `SYNCING`
         * skip as non-failing.
         */
        internal fun evaluateRefreshOutcome(
            result: Result<QuotaSnapshot>,
            connectionState: ConnectionState,
            syncError: String?
        ): Result<QuotaSnapshot> {
            if (result.isFailure) return result
            return when (connectionState) {
                ConnectionState.TAILSCALE_LIVE,
                ConnectionState.CLOUD_RELAY_SYNCED,
                ConnectionState.SYNCING -> result
                ConnectionState.OFFLINE,
                ConnectionState.STALE,
                ConnectionState.DEMO_SEED -> Result.failure(
                    IOException(syncError ?: "Widget sync fell back to $connectionState")
                )
            }
        }

        fun forContext(context: Context): WidgetSyncCoordinator {
            val appContext = context.applicationContext
            val preferencesStore = QuotaRepositoryProvider.getPreferencesStore(appContext)
            // Share the process-scoped QuotaRepositoryImpl with MainActivity so foreground
            // and background syncs serialize through the same syncMutex and syncGeneration
            // counter (APP-SYNC1 / APP-SYNC2).
            val repository = QuotaRepositoryProvider.getRepository(appContext)
            return WidgetSyncCoordinator(
                settingsProvider = { preferencesStore.settingsFlow.value },
                refresh = {
                    val result = repository.refreshForWidget()
                    evaluateRefreshOutcome(
                        result = result,
                        connectionState = repository.connectionStateFlow.value,
                        syncError = repository.syncErrorFlow.value
                    )
                },
                updateAllWidgets = { WidgetUpdater.updateAllWidgets(appContext) }
            )
        }
    }
}

/**
 * WorkManager worker that keeps Glance widgets fresh while the app is closed.
 *
 * Transient failures are retried a bounded number of times ([MAX_RETRY_ATTEMPTS]) and then
 * reported as success so WorkManager cannot enter an exponential backoff storm.
 */
class WidgetSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val outcome = WidgetSyncCoordinator.sync(applicationContext)
        return if (shouldRetry(runAttemptCount, outcome)) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.success()
        }
    }

    companion object {
        const val MAX_RETRY_ATTEMPTS = 2

        /** True only for a transient failure that still has retry budget left. */
        fun shouldRetry(runAttemptCount: Int, outcome: WidgetSyncResult): Boolean =
            outcome is WidgetSyncResult.TransientFailure && runAttemptCount < MAX_RETRY_ATTEMPTS
    }
}

/**
 * Schedules background widget syncs.
 *
 * `onUpdate` may only enqueue the unique *periodic* request: a one-time request that
 * finishes disables WorkManager's `RescheduleReceiver`, which re-broadcasts `ACTION_PACKAGE_CHANGED`
 * and re-triggers `onUpdate` in a loop. Immediate one-time syncs are enqueued only from
 * explicit user actions ([RefreshWidgetAction]).
 */
object WidgetSyncScheduler {

    const val PERIODIC_WORK_NAME = "ai_quota_monitor_widget_periodic_sync"
    const val IMMEDIATE_WORK_NAME = "ai_quota_monitor_widget_immediate_sync"
    const val PERIODIC_INTERVAL_MINUTES = 15L

    private fun connectedConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private fun periodicRequest() = PeriodicWorkRequestBuilder<WidgetSyncWorker>(
        PERIODIC_INTERVAL_MINUTES,
        TimeUnit.MINUTES
    )
        .setConstraints(connectedConstraints())
        .build()

    private fun immediateRequest() = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
        .setConstraints(connectedConstraints())
        .build()

    /** Queues (or keeps) the unique 15-minute periodic sync. Safe to call from `onUpdate`. */
    fun ensurePeriodicSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest()
        )
    }

    fun cancelPeriodicSyncIfNoWidgets(context: Context) {
        if (!hasInstalledWidgets(context)) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    /** Aligns the periodic schedule with the widgets currently placed on the home screen. */
    fun syncScheduleWithInstalledWidgets(context: Context) {
        if (hasInstalledWidgets(context)) {
            ensurePeriodicSync(context)
        } else {
            cancelPeriodicSyncIfNoWidgets(context)
        }
    }

    /** Enqueues one unique immediate refresh (used by explicit widget actions only). */
    fun enqueueImmediateSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            immediateRequest()
        )
    }

    /** True when at least one compact or wide widget is currently placed on a launcher. */
    fun hasInstalledWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val compactIds = manager.getAppWidgetIds(
            ComponentName(context, QuotaCompactWidgetReceiver::class.java)
        )
        if (compactIds.isNotEmpty()) return true
        val wideIds = manager.getAppWidgetIds(
            ComponentName(context, QuotaWideWidgetReceiver::class.java)
        )
        return wideIds.isNotEmpty()
    }
}

/**
 * Explicit widget action callback. Must keep a public zero-argument constructor because
 * Glance creates callbacks reflectively.
 */
class RefreshWidgetAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        WidgetSyncScheduler.enqueueImmediateSync(context)
    }
}
