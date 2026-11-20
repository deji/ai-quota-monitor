package com.aiquotamonitor.widget

import androidx.glance.appwidget.action.ActionCallback
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.SyncSettings
import java.io.IOException
import java.lang.reflect.Modifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSyncCoordinatorTest {

    private fun configuredSettings() = SyncSettings(
        tailscaleUrl = "http://100.64.0.1:8741",
        tailscaleToken = "synthetic_token"
    )

    @Test
    fun configuredTransportRefreshesAndUpdatesBothWidgets() = runTest {
        val updated = mutableListOf<String>()
        var refreshCalls = 0
        val coordinator = WidgetSyncCoordinator(
            settingsProvider = { configuredSettings() },
            refresh = {
                refreshCalls += 1
                Result.success(SeedData.createSeedSnapshot())
            },
            updateAllWidgets = {
                WidgetUpdater(
                    compactUpdate = { updated += "compact" },
                    wideUpdate = { updated += "wide" }
                ).updateAllWidgets()
            }
        )

        val outcome = coordinator.sync()

        assertEquals(WidgetSyncResult.Success, outcome)
        assertEquals("Configured transport must refresh exactly once", 1, refreshCalls)
        assertEquals(
            "Both widgets must be repainted from the same refresh",
            listOf("compact", "wide"),
            updated
        )
    }

    @Test
    fun unconfiguredTransportSkipsNetworkAndStillUpdatesBothWidgets() = runTest {
        val updated = mutableListOf<String>()
        var refreshCalls = 0
        val coordinator = WidgetSyncCoordinator(
            settingsProvider = { SyncSettings() },
            refresh = {
                refreshCalls += 1
                Result.success(SeedData.createSeedSnapshot())
            },
            updateAllWidgets = {
                WidgetUpdater(
                    compactUpdate = { updated += "compact" },
                    wideUpdate = { updated += "wide" }
                ).updateAllWidgets()
            }
        )

        val outcome = coordinator.sync()

        assertEquals(WidgetSyncResult.Unconfigured, outcome)
        assertEquals("Unconfigured sync must not hit the network", 0, refreshCalls)
        assertEquals(
            "Both widgets must still repaint so age labels do not drift",
            listOf("compact", "wide"),
            updated
        )
    }

    @Test
    fun networkFailureStillUpdatesBothWidgetsAndReportsTransientFailure() = runTest {
        val updated = mutableListOf<String>()
        val coordinator = WidgetSyncCoordinator(
            settingsProvider = { configuredSettings() },
            refresh = { throw IOException("synthetic offline failure") },
            updateAllWidgets = {
                WidgetUpdater(
                    compactUpdate = { updated += "compact" },
                    wideUpdate = { updated += "wide" }
                ).updateAllWidgets()
            }
        )

        val outcome = coordinator.sync()

        assertEquals(WidgetSyncResult.TransientFailure, outcome)
        assertEquals(
            "A thrown refresh must not skip the widget repaint",
            listOf("compact", "wide"),
            updated
        )
    }

    @Test
    fun failedRefreshResultStillUpdatesBothWidgets() = runTest {
        val updated = mutableListOf<String>()
        val coordinator = WidgetSyncCoordinator(
            settingsProvider = { configuredSettings() },
            refresh = { Result.failure(IOException("synthetic relay failure")) },
            updateAllWidgets = {
                WidgetUpdater(
                    compactUpdate = { updated += "compact" },
                    wideUpdate = { updated += "wide" }
                ).updateAllWidgets()
            }
        )

        val outcome = coordinator.sync()

        assertEquals(WidgetSyncResult.TransientFailure, outcome)
        assertEquals(listOf("compact", "wide"), updated)
    }

    @Test
    fun widgetUpdaterIsolatesFailuresPerWidget() = runTest {
        val updated = mutableListOf<String>()
        val updater = WidgetUpdater(
            compactUpdate = {
                updated += "compact"
                throw IllegalStateException("synthetic compact failure")
            },
            wideUpdate = { updated += "wide" }
        )

        updater.updateAllWidgets()

        assertEquals(
            "A compact failure must never block the wide widget",
            listOf("compact", "wide"),
            updated
        )
    }

    @Test
    fun widgetUpdaterIsolatesWideFailureAfterCompactSucceeds() = runTest {
        val updated = mutableListOf<String>()
        val updater = WidgetUpdater(
            compactUpdate = { updated += "compact" },
            wideUpdate = {
                updated += "wide"
                throw IllegalStateException("synthetic wide failure")
            }
        )

        updater.updateAllWidgets()

        assertEquals(listOf("compact", "wide"), updated)
    }

    @Test
    fun transientFailuresRespectBoundedRetryAttempts() {
        assertTrue(
            "First transient failure should retry",
            WidgetSyncWorker.shouldRetry(0, WidgetSyncResult.TransientFailure)
        )
        assertTrue(
            "Second transient failure should retry",
            WidgetSyncWorker.shouldRetry(1, WidgetSyncResult.TransientFailure)
        )
        assertFalse(
            "Retry budget is exhausted after MAX_RETRY_ATTEMPTS",
            WidgetSyncWorker.shouldRetry(2, WidgetSyncResult.TransientFailure)
        )
        assertFalse(
            "Successful syncs never retry",
            WidgetSyncWorker.shouldRetry(0, WidgetSyncResult.Success)
        )
        assertFalse(
            "Unconfigured syncs never retry",
            WidgetSyncWorker.shouldRetry(0, WidgetSyncResult.Unconfigured)
        )
        assertEquals(2, WidgetSyncWorker.MAX_RETRY_ATTEMPTS)
    }

    @Test
    fun refreshWidgetActionHasPublicZeroArgumentConstructor() {
        val constructor = RefreshWidgetAction::class.java.getDeclaredConstructor()
        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals(0, constructor.parameterCount)
        assertTrue(ActionCallback::class.java.isAssignableFrom(RefreshWidgetAction::class.java))
    }

    @Test
    fun bothWidgetReceiversHavePublicZeroArgumentConstructors() {
        listOf(
            QuotaCompactWidgetReceiver::class.java,
            QuotaWideWidgetReceiver::class.java
        ).forEach { type ->
            val constructor = type.getDeclaredConstructor()
            assertTrue("${type.simpleName} constructor must be public", Modifier.isPublic(constructor.modifiers))
            assertEquals("${type.simpleName} must keep a zero-argument constructor", 0, constructor.parameterCount)
        }
    }

    @Test
    fun evaluateRefreshOutcomeTreatsOfflineAndDemoFallbacksAsTransientFailure() = runTest {
        val snapshot = SeedData.createSeedSnapshot()
        val offlineResult = WidgetSyncCoordinator.evaluateRefreshOutcome(
            result = Result.success(snapshot),
            connectionState = ConnectionState.OFFLINE,
            syncError = "Tailscale live daemon unreachable"
        )
        assertTrue("OFFLINE fallback must fail for bounded retry", offlineResult.isFailure)

        val demoResult = WidgetSyncCoordinator.evaluateRefreshOutcome(
            result = Result.success(snapshot),
            connectionState = ConnectionState.DEMO_SEED,
            syncError = "Sync endpoints unreachable"
        )
        assertTrue("DEMO_SEED fallback must fail for bounded retry", demoResult.isFailure)

        val liveResult = WidgetSyncCoordinator.evaluateRefreshOutcome(
            result = Result.success(snapshot),
            connectionState = ConnectionState.TAILSCALE_LIVE,
            syncError = null
        )
        assertTrue("TAILSCALE_LIVE must succeed", liveResult.isSuccess)

        val relayResult = WidgetSyncCoordinator.evaluateRefreshOutcome(
            result = Result.success(snapshot),
            connectionState = ConnectionState.CLOUD_RELAY_SYNCED,
            syncError = null
        )
        assertTrue("CLOUD_RELAY_SYNCED must succeed", relayResult.isSuccess)

        val inFlightSyncResult = WidgetSyncCoordinator.evaluateRefreshOutcome(
            result = Result.success(snapshot),
            connectionState = ConnectionState.SYNCING,
            syncError = null
        )
        assertTrue("Skipped tick during active foreground SYNCING must not retry-storm", inFlightSyncResult.isSuccess)

        // End-to-end check through coordinator: offline fallback triggers TransientFailure + dual widget repaint.
        val updated = mutableListOf<String>()
        val coordinator = WidgetSyncCoordinator(
            settingsProvider = { configuredSettings() },
            refresh = {
                WidgetSyncCoordinator.evaluateRefreshOutcome(
                    result = Result.success(snapshot),
                    connectionState = ConnectionState.OFFLINE,
                    syncError = "Offline cache"
                )
            },
            updateAllWidgets = {
                WidgetUpdater(
                    compactUpdate = { updated += "compact" },
                    wideUpdate = { updated += "wide" }
                ).updateAllWidgets()
            }
        )
        assertEquals(WidgetSyncResult.TransientFailure, coordinator.sync())
        assertEquals(listOf("compact", "wide"), updated)
    }
}
