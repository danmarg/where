package net.af0.where

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.af0.where.e2ee.QrPayload

/**
 * Store for UI-only state that doesn't belong in the LocationRepository.
 * This includes navigation state, sheet visibility, and transient naming state.
 */
interface UiStateSource {
    val isInviteSheetShowing: StateFlow<Boolean>
    val pendingQrForNaming: StateFlow<QrPayload?>
    val multipleScansDetected: StateFlow<Boolean>

    fun setInviteSheetShowing(showing: Boolean)
    fun onPendingQrForNaming(qr: QrPayload?)
    fun setMultipleScansDetected(detected: Boolean)
    fun reset()
}

class UiStateStore : UiStateSource {
    private val _isInviteSheetShowing = MutableStateFlow(false)
    override val isInviteSheetShowing: StateFlow<Boolean> = _isInviteSheetShowing.asStateFlow()

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    override val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming.asStateFlow()

    private val _multipleScansDetected = MutableStateFlow(false)
    override val multipleScansDetected: StateFlow<Boolean> = _multipleScansDetected.asStateFlow()

    override fun setInviteSheetShowing(showing: Boolean) {
        _isInviteSheetShowing.value = showing
    }

    override fun onPendingQrForNaming(qr: QrPayload?) {
        _pendingQrForNaming.value = qr
    }

    override fun setMultipleScansDetected(detected: Boolean) {
        _multipleScansDetected.value = detected
    }

    override fun reset() {
        _isInviteSheetShowing.value = false
        _pendingQrForNaming.value = null
        _multipleScansDetected.value = false
    }
}
