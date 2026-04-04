package net.af0.where

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun onLocation(
        lat: Double,
        lng: Double,
    )

    fun onFriendUpdate(update: UserLocation)
    fun onConnectionStatus(status: ConnectionStatus)
    fun setAppForeground(foreground: Boolean)
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

    override fun onLocation(
        lat: Double,
        lng: Double,
    ) {
        _lastLocation.value = Pair(lat, lng)
    }

    override fun onFriendUpdate(update: UserLocation) {
        _friendLocations.value += (update.userId to update)
        _friendLastPing.value += (update.userId to System.currentTimeMillis())
    }

    override fun onConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    fun setInitialFriendLocations(locations: Map<String, UserLocation>, pings: Map<String, Long>) {
        _friendLocations.value = locations
        _friendLastPing.value = pings
    }
}
