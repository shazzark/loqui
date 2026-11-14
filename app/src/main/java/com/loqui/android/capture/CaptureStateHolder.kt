package com.loqui.android.capture

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the current dictation state. The capture service
 * writes it; the bubble and the app UI read it.
 */
@Singleton
class CaptureStateHolder @Inject constructor() {

    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)

    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    fun update(newState: CaptureState) {
        mutableState.value = newState
    }

    fun reset() {
        mutableState.value = CaptureState.Idle
    }
}
