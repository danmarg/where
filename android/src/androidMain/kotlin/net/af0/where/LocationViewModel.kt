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
import net.af0.where.e2ee.EpochRotationPayload
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationPlaintext
import net.af0.where.e2ee.PreKeyBundlePayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.RatchetAckPayload
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

    private val _displayName = MutableStateFlow(sharingPrefs.getString("display_name", "") ?: "")
    val displayName: StateFlow<String> = _displayName

    private val _friends = MutableStateFlow(e2eeStore.listFriends())
    val friends: StateFlow<List<FriendEntry>> = _friends

    private val _pausedFriendIds = MutableStateFlow(
        sharingPrefs.getString("paused_friends", "")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    )
    val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds

    private val friendLocations = MutableStateFlow(emptyMap<String, UserLocation>())

    private val _pendingInviteQr = MutableStateFlow<QrPayload?>(null)
    val pendingInviteQr: StateFlow<QrPayload?> = _pendingInviteQr

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload

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

    fun setDisplayName(name: String) {
        _displayName.value = name
        sharingPrefs.edit().putString("display_name", name).apply()
    }

    fun toggleSharing() {
        val new = !_isSharingLocation.value
        _isSharingLocation.value = new
        sharingPrefs.edit().putBoolean("is_sharing", new).apply()
    }

    fun togglePauseFriend(id: String) {
        val current = _pausedFriendIds.value
        val new = if (id in current) current - id else current + id
        _pausedFriendIds.value = new
        sharingPrefs.edit().putString("paused_friends", new.joinToString(",")).apply()
    }

    fun removeFriend(id: String) {
        e2eeStore.deleteFriend(id)
        _friends.value = e2eeStore.listFriends()
        friendLocations.value -= id
        if (id in _pausedFriendIds.value) {
            val newPaused = _pausedFriendIds.value - id
            _pausedFriendIds.value = newPaused
            sharingPrefs.edit().putString("paused_friends", newPaused.joinToString(",")).apply()
        }
    }

    fun createInvite() {
        _pendingInviteQr.value = e2eeStore.createInvite(_displayName.value.ifEmpty { "Me" })
    }

    fun clearInvite() {
        e2eeStore.clearInvite()
        _pendingInviteQr.value = null
    }

    /** Bob: parse scanned URL, but wait for user to name the friend. */
    fun processQrUrl(url: String): Boolean {
        val qr = QrUtils.urlToPayload(url) ?: return false
        _pendingQrForNaming.value = qr
        return true
    }

    fun cancelQrScan() {
        _pendingQrForNaming.value = null
    }

    fun confirmQrScan(qr: QrPayload, friendName: String) {
        _pendingQrForNaming.value = null
        val qrWithName = qr.copy(suggestedName = friendName)
        try {
            val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName)
            _friends.value = e2eeStore.listFriends()
            viewModelScope.launch {
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHexStr()
                    E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                } catch (_: Exception) {
                }
                // Bob posts his initial OPK bundle so Alice can initiate epoch rotation.
                postOpkBundle(bobEntry)
            }
        } catch (_: Exception) {
        }
    }

    fun confirmPendingInit(name: String) {
        val payload = _pendingInitPayload.value ?: return
        _pendingInitPayload.value = null
        try {
            e2eeStore.processKeyExchangeInit(payload, name)
            _friends.value = e2eeStore.listFriends()
        } catch (_: Exception) {
        }
    }

    fun cancelPendingInit() {
        _pendingInitPayload.value = null
    }

    private suspend fun postOpkBundle(friend: FriendEntry) {
        try {
            val bundle = e2eeStore.generateOpkBundle(friend.id) ?: return
            val hexToken = friend.session.routingToken.toHexStr()
            E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, hexToken, bundle)
        } catch (_: Exception) {
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
            
            // Found init payload! Show naming dialog instead of processing immediately.
            _pendingInitPayload.value = initPayload
            _pendingInviteQr.value = null
            e2eeStore.clearInvite()
        } catch (_: Exception) {
        }
    }

    private suspend fun pollAllFriends() {
        for (friend in e2eeStore.listFriends()) {
            try {
                val hexToken = friend.session.routingToken.toHexStr()
                val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, hexToken)
                val friendFp = MessageDigest.getInstance("SHA-256").digest(friend.ikPub + friend.sigIkPub)

                // --- Epoch rotation first: changes session/token for subsequent messages ---
                for (msg in messages.filterIsInstance<EpochRotationPayload>()) {
                    val ack = try {
                        e2eeStore.processEpochRotation(friend.id, msg)
                    } catch (_: IllegalArgumentException) {
                        null // bad signature — discard
                    } ?: continue

                    val newToken = e2eeStore.getFriend(friend.id)?.session?.routingToken?.toHexStr() ?: break
                    try {
                        E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, newToken, ack)
                    } catch (_: Exception) {
                    }
                    // Replenish OPKs on the new token so Alice can rotate again.
                    val bundle = e2eeStore.generateOpkBundle(friend.id)
                    if (bundle != null) {
                        try {
                            E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, newToken, bundle)
                        } catch (_: Exception) {
                        }
                    }
                }

                // --- Cache incoming OPK bundles (Alice stores Bob's prekeys) ---
                for (msg in messages.filterIsInstance<PreKeyBundlePayload>()) {
                    e2eeStore.storeOpkBundle(friend.id, msg)
                }

                // --- Decrypt location updates in seq order ---
                var session = e2eeStore.getFriend(friend.id)?.session ?: continue
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
                if (session !== (e2eeStore.getFriend(friend.id)?.session)) {
                    e2eeStore.updateSession(friend.id, session)
                }

                // --- Validate RatchetAck (informational for Alice) ---
                for (msg in messages.filterIsInstance<RatchetAckPayload>()) {
                    e2eeStore.processRatchetAck(friend.id, msg)
                }

                // --- Bob: proactively replenish OPKs if running low ---
                if (e2eeStore.shouldReplenishOpks(friend.id)) {
                    val current = e2eeStore.getFriend(friend.id) ?: continue
                    postOpkBundle(current)
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
            if (friend.id in _pausedFriendIds.value) continue
            try {
                // Alice: rotate epoch when due, before sending the next message.
                if (e2eeStore.shouldRotateEpoch(friend.id)) {
                    val oldToken = friend.session.routingToken.toHexStr()
                    val rotPayload = e2eeStore.initiateEpochRotation(friend.id)
                    if (rotPayload != null) {
                        // Post rotation announcement to the OLD token so Bob can process it.
                        try {
                            E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, oldToken, rotPayload)
                        } catch (_: Exception) {
                        }
                    }
                }

                // Re-fetch after potential rotation to use the current session/token.
                val current = e2eeStore.getFriend(friend.id) ?: continue
                val friendFp = MessageDigest.getInstance("SHA-256").digest(friend.ikPub + friend.sigIkPub)
                val (newSession, ct) =
                    Session.encryptLocation(
                        state = current.session,
                        location = plaintext,
                        senderFp = myFp,
                        recipientFp = friendFp,
                    )
                e2eeStore.updateSession(friend.id, newSession)
                E2eeMailboxClient.post(
                    baseUrl = BuildConfig.SERVER_HTTP_URL,
                    token = current.session.routingToken.toHexStr(),
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
