package com.example.miro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.miro.data.AppPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    val pin: StateFlow<String?> = prefs.pin.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val deviceId: StateFlow<String?> = prefs.deviceId.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    init {
        viewModelScope.launch {
            prefs.deviceId.collect { id ->
                if (id == null) {
                    prefs.saveDeviceId(UUID.randomUUID().toString().take(6).uppercase())
                }
            }
        }
    }

    fun savePin(pin: String) {
        viewModelScope.launch {
            prefs.savePin(pin)
        }
    }
}
