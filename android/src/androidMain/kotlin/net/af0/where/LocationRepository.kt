package net.af0.where

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.model.UserLocation

sealed class ConnectionStatus {
    object Ok : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

/** Minimal interface over the shared location state, making it injectable for tests. */
interface LocationSource {
    val lastLocation: StateFlow<Pair<Double, Double>?>
    val friendLocations: StateFlow<Map<String, UserLocation>>
    val friendLastPing: StateFlow<Map<String, Long>>
    val connectionStatus: StateFlow<ConnectionStatus>
    val isAppInForeground: StateFlow<Boolean>
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?>

    fun onLocation(
        lat: Double,
        lng: Double,
    )

    fun onFriendUpdate(update: UserLocation)
    fun onFriendRemoved(id: String)
    fun onConnectionStatus(status: ConnectionStatus)
    fun onConnectionError(e: Throwable)
    fun setAppForeground(foreground: Boolean)
    fun onPendingInit(payload: KeyExchangeInitPayload?)
}

/**
 * Singleton that bridges the LocationService (which has no ViewModel access)
 * with the LocationViewModel via simple StateFlows.
 */
object LocationRepository : LocationSource {
    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation.asStateFlow()

    private val _friendLocations = MutableStateFlow<Map<String, UserLocation>>(emptyMap())
    override val friendLocations: StateFlow<Map<String, UserLocation>> = _friendLocations.asStateFlow()

    private val _friendLastPing = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _isAppInForeground = MutableStateFlow(false)
    override val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    override val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload.asStateFlow()

    override fun onLocation(
        lat: Double,
        lng: Double,
    ) {
        _lastLocation.value = Pair(lat, lng)
    }

    override fun onFriendUpdate(update: UserLocation) {
        _friendLocations.update { it + (update.userId to update) }
        _friendLastPing.update { it + (update.userId to System.currentTimeMillis()) }
    }

    override fun onFriendRemoved(id: String) {
        _friendLocations.update { it - id }
        _friendLastPing.update { it - id }
    }

    override fun onConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    override fun onConnectionError(e: Throwable) {
        val msg = when {
            e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "not resolved"
            e.message?.contains("timeout", ignoreCase = true) == true -> "timeout"
            e.message?.contains("ConnectException", ignoreCase = true) == true -> "no connection"
            e.message?.contains("Failed to post to mailbox: 500", ignoreCase = true) == true -> "server error 500"
            else -> e.message?.take(32) ?: "unknown error"
        }
        _connectionStatus.value = ConnectionStatus.Error(msg)
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(payload: KeyExchangeInitPayload?) {
        _pendingInitPayload.value = payload
    }

    fun setInitialFriendLocations(locations: Map<String, UserLocation>, pings: Map<String, Long>) {
        // Merge with current state to avoid overwriting live updates that arrived before initial load
        _friendLocations.update { locations + it }
        _friendLastPing.update { pings + it }
    }
}
