package net.af0.where

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.af0.where.model.UserLocation

/** Minimal interface over the shared location state, making it injectable for tests. */
interface LocationSource {
    val lastLocation: StateFlow<Pair<Double, Double>?>
    val users: StateFlow<List<UserLocation>>
    fun onLocation(lat: Double, lng: Double)
    fun onUsersUpdate(users: List<UserLocation>)
}

/**
 * Singleton that bridges the LocationService (which has no ViewModel access)
 * with the LocationViewModel via simple StateFlows.
 */
object LocationRepository : LocationSource {
    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation.asStateFlow()

    private val _users = MutableStateFlow<List<UserLocation>>(emptyList())
    override val users: StateFlow<List<UserLocation>> = _users.asStateFlow()

    override fun onLocation(lat: Double, lng: Double) {
        _lastLocation.value = Pair(lat, lng)
    }

    override fun onUsersUpdate(users: List<UserLocation>) {
        _users.value = users
    }
}
