package com.aiquotamonitor.data

import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.remote.GistRelayClient
import com.aiquotamonitor.data.remote.TailscaleApiClient
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepositoryImpl
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QuotaRepositoryImplTest {

    private lateinit var preferencesStore: PreferencesStore

    @Before
    fun setUp() {
        preferencesStore = PreferencesStore()
    }

    private class FakeTailscaleClient(
        var mockSnapshot: QuotaSnapshot = SeedData.createSeedSnapshot(),
        var mockPrimeStatus: SmartPrimeStatus = SeedData.createSeedSnapshot().smartPrime,
        var shouldFailFetch: Boolean = false,
        var shouldFailRefresh: Boolean = false,
        var shouldFailPrime: Boolean = false
    ) : TailscaleApiClient() {
        var fetchQuotaCalled = false
        var triggerRefreshCalled = false
        var primeGrokCalled = false

        override fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot {
            fetchQuotaCalled = true
            if (shouldFailFetch) throw IOException("Tailscale fetch network error")
            return mockSnapshot
        }

        override fun triggerRefresh(baseUrl: String, token: String?): QuotaSnapshot {
            triggerRefreshCalled = true
            if (shouldFailRefresh) throw IOException("Tailscale refresh daemon error")
            return mockSnapshot
        }

        override fun primeGrok(baseUrl: String, token: String?): SmartPrimeStatus {
            primeGrokCalled = true
            if (shouldFailPrime) throw IOException("Tailscale grok prime error")
            return mockPrimeStatus
        }
    }

    private class FakeGistClient(
        var mockSnapshot: QuotaSnapshot = SeedData.createSeedSnapshot(),
        var shouldFail: Boolean = false
    ) : GistRelayClient() {
        var fetchCalled = false

        override fun fetchAndDecryptSnapshot(gistId: String, aesKey: String): QuotaSnapshot {
            fetchCalled = true
            if (shouldFail) throw IOException("Gist relay 404 or decryption failed")
            return mockSnapshot
        }
    }

    @Test
    fun testTailscalePrimaryRefreshSuccess() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val fakeGist = FakeGistClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                tailscaleToken = "test_token"
            )
        )

        // Manual refresh should invoke triggerRefresh()
        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertTrue("triggerRefresh should be called for manual refresh", fakeTailscale.triggerRefreshCalled)
        assertEquals(ConnectionState.TAILSCALE_LIVE, repository.connectionStateFlow.value)
        assertNull(repository.syncErrorFlow.value)
    }

    @Test
    fun testTailscaleFailureFallsBackToGistSuccess() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true, shouldFailFetch = true)
        val expectedSnapshot = SeedData.createSeedSnapshot().copy(host = "gist-cloud-relay")
        val fakeGist = FakeGistClient(mockSnapshot = expectedSnapshot)

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                gistId = "gist_abc123",
                gistAesKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
        )

        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertTrue("Gist client should be invoked on Tailscale failure", fakeGist.fetchCalled)
        assertEquals(ConnectionState.CLOUD_RELAY_SYNCED, repository.connectionStateFlow.value)
        assertEquals("gist-cloud-relay", repository.snapshotFlow.value.host)
        assertNull(repository.syncErrorFlow.value)
    }

    @Test
    fun testForceTailscaleFailurePropagatesError() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val fakeGist = FakeGistClient()

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                gistId = "gist_abc123",
                gistAesKey = "some_key"
            )
        )

        val result = repository.refresh(forceTailscale = true, isManual = true)

        assertTrue(result.isFailure)
        assertTrue("Gist client should NOT be called when forceTailscale is true", !fakeGist.fetchCalled)
        assertEquals(ConnectionState.OFFLINE, repository.connectionStateFlow.value)
        assertNotNull(repository.syncErrorFlow.value)
        assertTrue(repository.syncErrorFlow.value!!.contains("Tailscale refresh failed"))
    }

    @Test
    fun testAllEndpointsFailSurfacesDescriptiveError() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val fakeGist = FakeGistClient(shouldFail = true)

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                gistId = "gist_abc123",
                gistAesKey = "key_123"
            )
        )

        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        // Falls back to seed or offline cache
        assertEquals(ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)
        val errorMsg = repository.syncErrorFlow.value
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Tailscale live daemon unreachable"))
        assertTrue(errorMsg.contains("Cloud relay failed"))
    }

    @Test
    fun testPrimeGrokVerifiesFollowUpRefreshOutcome() = runTest {
        val fakeTailscale = FakeTailscaleClient(
            mockPrimeStatus = SmartPrimeStatus(
                policy = "active_hours",
                activeHoursStart = "08:00",
                activeHoursEnd = "22:00",
                grokTokenExpiresAt = "2026-11-01T20:00:00Z",
                grokTokenRemainingSeconds = 5400L,
                lastPrimedAt = "2026-11-01T17:00:00Z"
            ),
            // Prime succeeds, but follow-up refresh fails!
            shouldFailPrime = false,
            shouldFailRefresh = true
        )

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080"
            )
        )

        val primeResult = repository.primeGrok()

        assertTrue("primeGrok must fail if follow-up refresh fails", primeResult.isFailure)
        assertTrue(primeResult.exceptionOrNull()?.message?.contains("Tailscale refresh daemon error") == true)
    }

    @Test
    fun testPrimeGrokInOfflineModeLabelsSimulationClearly() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // Blank tailscale URL simulates offline mode
        repository.updateSettings(SyncSettings(tailscaleUrl = ""))

        val primeResult = repository.primeGrok()

        assertTrue(primeResult.isSuccess)
        val errorMsg = repository.syncErrorFlow.value
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Offline simulation"))
    }
}
