package com.aiquotamonitor.ui.viewmodel

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepository
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.calculator.HorizonSimulator
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.widget.QuotaCompactWidget
import com.aiquotamonitor.widget.QuotaWideWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QuotaViewModel(
    private val repository: QuotaRepository,
    private val preferencesStore: PreferencesStore? = null,
    private val context: Context? = null
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionStateFlow
    val settings: StateFlow<SyncSettings> = repository.settingsFlow
    val syncError: StateFlow<String?> = repository.syncErrorFlow

    val isSyncError: StateFlow<Boolean> = repository.syncErrorFlow
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = !repository.syncErrorFlow.value.isNullOrBlank()
        )

    private val _polarity = MutableStateFlow(preferencesStore?.getPolarity() ?: QuotaPolarity.REMAINING)
    val polarity: StateFlow<QuotaPolarity> = _polarity.asStateFlow()

    private val _scrubOffsetHours = MutableStateFlow(0.0)
    val scrubOffsetHours: StateFlow<Double> = _scrubOffsetHours.asStateFlow()

    val isScrubbing: StateFlow<Boolean> = _scrubOffsetHours
        .map { it > 0.0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _isPriming = MutableStateFlow(false)
    val isPriming: StateFlow<Boolean> = _isPriming.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    // Active displayed snapshot: projects base snapshot forward in time when scrubbing!
    val activeSnapshot: StateFlow<QuotaSnapshot> = combine(
        repository.snapshotFlow,
        _scrubOffsetHours
    ) { baseSnapshot, offsetHours ->
        if (offsetHours > 0.0) {
            HorizonSimulator.project(baseSnapshot, offsetHours)
        } else {
            baseSnapshot
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = repository.snapshotFlow.value
    )

    fun togglePolarity() {
        setPolarity(_polarity.value.toggle())
    }

    fun setPolarity(newPolarity: QuotaPolarity) {
        _polarity.value = newPolarity
        preferencesStore?.savePolarity(newPolarity)
        triggerWidgetUpdate()
    }

    fun setScrubOffset(hours: Double) {
        _scrubOffsetHours.value = hours.coerceIn(0.0, 24.0)
    }

    fun resetScrub() {
        _scrubOffsetHours.value = 0.0
    }

    fun refresh(forceTailscale: Boolean = false, isManual: Boolean = true) {
        viewModelScope.launch {
            val result = repository.refresh(forceTailscale = forceTailscale, isManual = isManual)
            if (result.isFailure) {
                _userMessage.value = "Refresh failed: ${result.exceptionOrNull()?.message}"
            } else {
                val err = repository.syncErrorFlow.value
                if (!err.isNullOrBlank()) {
                    _userMessage.value = err
                } else {
                    _userMessage.value = "Quota refreshed successfully"
                }
            }
            triggerWidgetUpdate()
        }
    }

    fun primeGrok() {
        viewModelScope.launch {
            _isPriming.value = true
            val isBlankEndpoint = settings.value.tailscaleUrl.isBlank()
            val result = repository.primeGrok()
            _isPriming.value = false
            if (result.isSuccess) {
                if (isBlankEndpoint) {
                    _userMessage.value = "Grok OAuth token primed (Offline Simulation — no Tailscale daemon endpoint configured)"
                } else {
                    _userMessage.value = "Grok OAuth token primed successfully via Tailscale daemon"
                }
            } else {
                _userMessage.value = "Prime failed: ${result.exceptionOrNull()?.message}"
            }
            triggerWidgetUpdate()
        }
    }

    fun saveSettings(newSettings: SyncSettings) {
        repository.updateSettings(newSettings)
        refresh(forceTailscale = false, isManual = true)
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    private fun triggerWidgetUpdate() {
        context?.let { ctx ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    QuotaCompactWidget().updateAll(ctx)
                    QuotaWideWidget().updateAll(ctx)
                } catch (_: Exception) {}
            }
        }
    }

    class Factory(
        private val repository: QuotaRepository,
        private val preferencesStore: PreferencesStore? = null,
        private val context: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuotaViewModel(repository, preferencesStore, context) as T
        }
    }
}
