package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.af0.where.e2ee.E2eeMailboxClient
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.EncryptedLocationPayload
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationPlaintext
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.discoveryToken
import net.af0.where.model.UserLocation
import java.security.MessageDigest

class LocationViewModel(
    app: Application,
    private val locationSource: LocationSource = LocationRepository,
) : AndroidViewModel(app) {
    private val identityKeys = IdentityKeyStore.getOrCreate(app)
    private val e2eeStore = E2eeStore(SharedPrefsE2eeStorage(app), identityKeys)

    private val myFp: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(identityKeys.ik.pub + identityKeys.sigIk.pub)
    }

    val userId: String by lazy { myFp.toHexStr().substring(0, 20) }

    private val sharingPrefs = app.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
    private val _isSharingLocation = MutableStateFlow(sharingPrefs.getBoolean("is_sharing", true))
    val isSharingLocation: StateFlow<Boolean> = _isSharingLocation

    private val _friendIds = MutableStateFlow(e2eeStore.listFriends().map { it.id }.toSet())
    val friendIds: StateFlow<Set<String>> = _friendIds

    private val friendLocations = MutableStateFlow(emptyMap<String, UserLocation>())

    private val _pendingInviteQr = MutableStateFlow<QrPayload?>(null)
    val pendingInviteQr: StateFlow<QrPayload?> = _pendingInviteQr

    val visibleUsers: StateFlow<List<UserLocation>> =
        combine(locationSource.lastLocation, _isSharingLocation, friendLocations) { myLoc, sharing, friendLocs ->
            buildList {
                if (myLoc != null && sharing) {
                    add(UserLocation(userId, myLoc.first, myLoc.second, System.currentTimeMillis() / 1000))
                }
                addAll(friendLocs.values)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { pollLoop() }
        viewModelScope.launch {
            var prevSharing = _isSharingLocation.value
            combine(locationSource.lastLocation, _isSharingLocation) { loc, sharing ->
                loc to sharing
            }.collect { (loc, sharing) ->
                if (loc != null && sharing) {
                    sendEncryptedLocation(loc.first, loc.second)
                }
                if (prevSharing != sharing) {
                    manageForegroundService(sharing)
                }
                prevSharing = sharing
            }
        }
    }

    fun toggleSharing() {
        val new = !_isSharingLocation.value
        _isSharingLocation.value = new
        sharingPrefs.edit().putBoolean("is_sharing", new).apply()
    }

    // QR-based friend adding is handled via the invite/scan flow; this stub satisfies the UI API.
    fun addFriend(
        @Suppress("UNUSED_PARAMETER") id: String,
    ) = Unit

    fun removeFriend(id: String) {
        e2eeStore.deleteFriend(id)
        _friendIds.value = e2eeStore.listFriends().map { it.id }.toSet()
        friendLocations.value -= id
    }

    fun createInvite() {
        _pendingInviteQr.value = e2eeStore.createInvite("Me")
    }

    fun clearInvite() {
        e2eeStore.clearInvite()
        _pendingInviteQr.value = null
    }

    /** Bob: parse scanned URL, run key exchange, POST init to discovery token. Returns true on success. */
    fun processQrUrl(url: String): Boolean {
        val qr = QrUtils.urlToPayload(url) ?: return false
        return try {
            val (initPayload, _) = e2eeStore.processScannedQr(qr)
            _friendIds.value = e2eeStore.listFriends().map { it.id }.toSet()
            viewModelScope.launch {
                try {
                    val discoveryHex = qr.discoveryToken().toHexStr()
                    E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                } catch (_: Exception) {
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            pollAllFriends()
            pollPendingInvite()
            delay(60_000)
        }
    }

    private suspend fun pollPendingInvite() {
        val qr = e2eeStore.pendingQrPayload ?: return
        try {
            val discoveryHex = qr.discoveryToken().toHexStr()
            val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, discoveryHex)
            val initPayload = messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull() ?: return
            val entry =
                try {
                    e2eeStore.processKeyExchangeInit(initPayload, "Friend")
                } catch (_: IllegalArgumentException) {
                    return // bad signature — skip this payload
                } ?: return
            _pendingInviteQr.value = null
            _friendIds.value = e2eeStore.listFriends().map { it.id }.toSet()
        } catch (_: Exception) {
        }
    }

    private suspend fun pollAllFriends() {
        for (friend in e2eeStore.listFriends()) {
            try {
                val hexToken = friend.session.routingToken.toHexStr()
                val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, hexToken)
                val friendFp = MessageDigest.getInstance("SHA-256").digest(friend.ikPub + friend.sigIkPub)
                var session = friend.session
                for (msg in messages.filterIsInstance<EncryptedLocationPayload>().sortedBy { it.seqAsLong() }) {
                    val result =
                        Session.decryptLocation(
                            state = session,
                            ct = msg.ct,
                            seq = msg.seqAsLong(),
                            senderFp = friendFp,
                            recipientFp = myFp,
                        ) ?: continue
                    session = result.first
                    val location = result.second
                    friendLocations.value += (friend.id to UserLocation(friend.id, location.lat, location.lng, location.ts))
                }
                if (session !== friend.session) {
                    e2eeStore.updateSession(friend.id, session)
                }
            } catch (_: Exception) {
                // ignore transient poll failures
            }
        }
    }

    private suspend fun sendEncryptedLocation(
        lat: Double,
        lng: Double,
    ) {
        val ts = System.currentTimeMillis() / 1000
        val plaintext = LocationPlaintext(lat = lat, lng = lng, acc = 0.0, ts = ts)
        for (friend in e2eeStore.listFriends()) {
            try {
                val friendFp = MessageDigest.getInstance("SHA-256").digest(friend.ikPub + friend.sigIkPub)
                val (newSession, ct) =
                    Session.encryptLocation(
                        state = friend.session,
                        location = plaintext,
                        senderFp = myFp,
                        recipientFp = friendFp,
                    )
                e2eeStore.updateSession(friend.id, newSession)
                E2eeMailboxClient.post(
                    baseUrl = BuildConfig.SERVER_HTTP_URL,
                    token = friend.session.routingToken.toHexStr(),
                    payload =
                        EncryptedLocationPayload(
                            epoch = newSession.epoch,
                            seq = newSession.sendSeq.toString(),
                            ct = ct,
                        ),
                )
            } catch (_: Exception) {
                // ignore transient send failures
            }
        }
    }

    private fun manageForegroundService(sharing: Boolean) {
        val intent = Intent(getApplication(), LocationService::class.java)
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (sharing && hasLocationPermission) {
            getApplication<Application>().startForegroundService(intent)
        } else if (!sharing) {
            getApplication<Application>().stopService(intent)
        }
    }

    private fun ByteArray.toHexStr(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
