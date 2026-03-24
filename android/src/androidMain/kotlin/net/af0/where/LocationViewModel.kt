package net.af0.where

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.af0.where.model.UserLocation

class LocationViewModel(app: Application) : AndroidViewModel(app) {

    val userId: String = UserPrefs.getUserId(app)
    val friendsStore = FriendsStore(app, userId)

    // All users from server, filtered to self + friends
    val visibleUsers: StateFlow<List<UserLocation>> = combine(
        LocationRepository.users,
        friendsStore.friendIds,
    ) { users, friends ->
        users.filter { it.userId == userId || friends.contains(it.userId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val syncClient = LocationSyncClient(
        serverWsUrl = BuildConfig.SERVER_WS_URL,
        userId = userId,
        onLocationsUpdate = { LocationRepository.onUsersUpdate(it) },
    )

    init {
        syncClient.connect()
        viewModelScope.launch {
            var prevSharing = true
            combine(
                LocationRepository.lastLocation,
                friendsStore.isSharingLocation,
            ) { loc, sharing -> Pair(loc, sharing) }.collect { (loc, sharing) ->
                if (loc != null && sharing) {
                    syncClient.sendLocation(loc.first, loc.second)
                } else if (prevSharing && !sharing) {
                    syncClient.sendLocationRemove()
                }
                if (prevSharing != sharing) {
                    val intent = Intent(getApplication(), LocationService::class.java)
                    if (sharing) {
                        getApplication<Application>().startForegroundService(intent)
                    } else {
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
