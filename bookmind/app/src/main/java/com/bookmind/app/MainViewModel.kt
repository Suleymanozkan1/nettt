package com.bookmind.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AppState(val loading: Boolean = true, val onboardingDone: Boolean = false, val theme: ThemeMode = ThemeMode.SYSTEM)

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<AppState> = settings.settings
        .map { AppState(false, it.onboardingDone, it.theme) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState())
}
