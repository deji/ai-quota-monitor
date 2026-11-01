package com.aiquotamonitor.data.local

import android.content.Context
import android.content.SharedPreferences
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.model.QuotaPolarity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesStore(
    context: Context? = null,
    sharedPreferences: SharedPreferences? = null,
    private val secretStore: KeystoreSecretStore = KeystoreSecretStore()
) {

    private val prefs: SharedPreferences = sharedPreferences
        ?: context?.getSharedPreferences("ai_quota_monitor_prefs", Context.MODE_PRIVATE)
        ?: InMemorySharedPreferences()

    private val _settingsFlow = MutableStateFlow(readSettings())
    val settingsFlow: StateFlow<SyncSettings> = _settingsFlow.asStateFlow()

    private fun readSettings(): SyncSettings {
        val encryptedToken = prefs.getString(KEY_TAILSCALE_TOKEN, "") ?: ""
        val encryptedGistKey = prefs.getString(KEY_GIST_KEY, "") ?: ""

        return SyncSettings(
            tailscaleUrl = prefs.getString(KEY_TAILSCALE_URL, "") ?: "",
            tailscaleToken = secretStore.decrypt(encryptedToken),
            gistId = prefs.getString(KEY_GIST_ID, "") ?: "",
            gistAesKey = secretStore.decrypt(encryptedGistKey),
            activeHoursStart = prefs.getString(KEY_HOURS_START, "08:00") ?: "08:00",
            activeHoursEnd = prefs.getString(KEY_HOURS_END, "22:00") ?: "22:00"
        )
    }

    fun saveSettings(settings: SyncSettings) {
        val encryptedToken = secretStore.encrypt(settings.tailscaleToken)
        val encryptedGistKey = secretStore.encrypt(settings.gistAesKey)

        prefs.edit()
            .putString(KEY_TAILSCALE_URL, settings.tailscaleUrl)
            .putString(KEY_TAILSCALE_TOKEN, encryptedToken)
            .putString(KEY_GIST_ID, settings.gistId)
            .putString(KEY_GIST_KEY, encryptedGistKey)
            .putString(KEY_HOURS_START, settings.activeHoursStart)
            .putString(KEY_HOURS_END, settings.activeHoursEnd)
            .apply()
        _settingsFlow.value = settings
    }

    fun saveCachedSnapshotJson(json: String) {
        prefs.edit().putString(KEY_CACHED_SNAPSHOT, json).apply()
    }

    fun getCachedSnapshotJson(): String? {
        return prefs.getString(KEY_CACHED_SNAPSHOT, null)
    }

    fun savePolarity(polarity: QuotaPolarity) {
        prefs.edit().putString(KEY_POLARITY, polarity.name).apply()
    }

    fun getPolarity(): QuotaPolarity {
        val name = prefs.getString(KEY_POLARITY, QuotaPolarity.REMAINING.name)
        return try {
            QuotaPolarity.valueOf(name ?: QuotaPolarity.REMAINING.name)
        } catch (_: Exception) {
            QuotaPolarity.REMAINING
        }
    }

    companion object {
        private const val KEY_TAILSCALE_URL = "tailscale_url"
        private const val KEY_TAILSCALE_TOKEN = "tailscale_token"
        private const val KEY_GIST_ID = "gist_id"
        private const val KEY_GIST_KEY = "gist_key"
        private const val KEY_HOURS_START = "hours_start"
        private const val KEY_HOURS_END = "hours_end"
        private const val KEY_CACHED_SNAPSHOT = "cached_snapshot"
        private const val KEY_POLARITY = "quota_polarity"
    }
}

/**
 * Lightweight in-memory SharedPreferences fallback for pure JVM unit tests without Android Context.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? {
        val v = key?.let { data[it] }
        return (v as? String) ?: defValue
    }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return (key?.let { data[it] } as? Set<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = (key?.let { data[it] } as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (key?.let { data[it] } as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (key?.let { data[it] } as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (key?.let { data[it] } as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = key != null && data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl : SharedPreferences.Editor {
        private val temp = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value != null) temp[key] = value else temp[key] = null
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) {
                if (values != null) temp[key] = values else temp[key] = null
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) {
                data.clear()
            }
            for ((k, v) in temp) {
                if (v == null) {
                    data.remove(k)
                } else {
                    data[k] = v
                }
            }
        }
    }
}
