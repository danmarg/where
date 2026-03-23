package net.af0.where

import android.app.Application
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
        serverWsUrl = SERVER_WS_URL,
        userId = userId,
        onLocationsUpdate = { LocationRepository.onUsersUpdate(it) },
    )

    init {
        syncClient.connect()
        viewModelScope.launch {
            combine(
                LocationRepository.lastLocation,
                friendsStore.isSharingLocation,
            ) { loc, sharing -> Pair(loc, sharing) }.collect { (loc, sharing) ->
                if (loc != null && sharing) {
                    syncClient.sendLocation(loc.first, loc.second)
                }
            }
        }
    }

    override fun onCleared() {
        syncClient.disconnect()
        super.onCleared()
    }

    companion object {
        private const val SERVER_WS_URL = "ws://10.0.2.2:8080/ws"
    }
}
