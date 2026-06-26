package com.example.profiledetection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Compose-observable state shared between the Service and the overlay composables. */
class OverlayState {
    var panelOpen by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
}
