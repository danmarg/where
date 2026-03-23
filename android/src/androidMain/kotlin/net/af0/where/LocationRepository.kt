package net.af0.where

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.af0.where.model.UserLocation

/**
 * Singleton that bridges the LocationService (which has no ViewModel access)
 * with the LocationViewModel via simple StateFlows.
 */
object LocationRepository {
    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation.asStateFlow()

    private val _users = MutableStateFlow<List<UserLocation>>(emptyList())
    val users: StateFlow<List<UserLocation>> = _users.asStateFlow()

    fun onLocation(lat: Double, lng: Double) {
        _lastLocation.value = Pair(lat, lng)
    }

    fun onUsersUpdate(users: List<UserLocation>) {
        _users.value = users
    }
}
