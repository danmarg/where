package net.af0.where

import kotlinx.coroutines.flow.MutableStateFlow
import net.af0.where.e2ee.QrPayload

class FakeUiStateStore : UiStateSource {
    override val isInviteSheetShowing = MutableStateFlow(false)
    override val pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    override val multipleScansDetected = MutableStateFlow(false)

    override fun setInviteSheetShowing(showing: Boolean) {
        isInviteSheetShowing.value = showing
    }

    override fun onPendingQrForNaming(qr: QrPayload?) {
        pendingQrForNaming.value = qr
    }

    override fun setMultipleScansDetected(detected: Boolean) {
        multipleScansDetected.value = detected
    }

    override fun reset() {
        isInviteSheetShowing.value = false
        pendingQrForNaming.value = null
        multipleScansDetected.value = false
    }
}
