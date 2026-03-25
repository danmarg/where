package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.af0.where.model.UserLocation

class LocationViewModel(
    app: Application,
    private val locationSource: LocationSource = LocationRepository,
) : AndroidViewModel(app) {

    val userId: String = UserPrefs.getUserId(app)
    private val friendsStore = FriendsStore(app, userId)

    val friendIds: StateFlow<Set<String>> = friendsStore.friendIds
    val isSharingLocation: StateFlow<Boolean> = friendsStore.isSharingLocation

    fun addFriend(id: String) = friendsStore.add(id)
    fun removeFriend(id: String) = friendsStore.remove(id)
    fun toggleSharing() = friendsStore.toggleSharing()

    // All users from server, filtered to self + friends
    val visibleUsers: StateFlow<List<UserLocation>> = combine(
        locationSource.users,
        friendsStore.friendIds,
    ) { users, friends ->
        users.filter { it.userId == userId || friends.contains(it.userId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val syncClient = LocationSyncClient(
        serverWsUrl = BuildConfig.SERVER_WS_URL,
        userId = userId,
        onLocationsUpdate = { locationSource.onUsersUpdate(it) },
    )

    init {
        syncClient.connect()
        viewModelScope.launch {
            var prevSharing = friendsStore.isSharingLocation.value
            combine(
                locationSource.lastLocation,
                friendsStore.isSharingLocation,
            ) { loc, sharing -> Pair(loc, sharing) }.collect { (loc, sharing) ->
                if (loc != null && sharing) {
                    syncClient.sendLocation(loc.first, loc.second)
                } else if (prevSharing && !sharing) {
                    syncClient.sendLocationRemove()
                }
                if (prevSharing != sharing) {
                    val intent = Intent(getApplication(), LocationService::class.java)
                    val hasLocationPermission = ContextCompat.checkSelfPermission(
                        getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (sharing && hasLocationPermission) {
                        getApplication<Application>().startForegroundService(intent)
                    } else if (!sharing) {
                        getApplication<Application>().stopService(intent)
                    }
                }
                prevSharing = sharing
            }
        }
    }

    override fun onCleared() {
        syncClient.disconnect()
        super.onCleared()
    }
}
