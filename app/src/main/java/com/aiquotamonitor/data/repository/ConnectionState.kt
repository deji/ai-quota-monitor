package com.aiquotamonitor.data.repository

enum class ConnectionState(val label: String, val isStale: Boolean = false) {
    TAILSCALE_LIVE("Tailscale Live"),
    CLOUD_RELAY_SYNCED("Cloud Relay"),
    DEMO_SEED("Demo Mode"),
    SYNCING("Refreshing..."),
    OFFLINE("Offline Cache"),
    STALE("Stale Snapshot", isStale = true)
}

data class SyncSettings(
    val tailscaleUrl: String = "",
    val tailscaleToken: String = "",
    val gistId: String = "",
    val gistAesKey: String = "",
    val githubToken: String = "",
    val activeHoursStart: String = "08:00",
    val activeHoursEnd: String = "22:00"
)
