package net.af0.where

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.launch
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize

private val mapStyleJson = """
[
  {
    "featureType": "poi",
    "elementType": "labels",
    "stylers": [
      { "visibility": "off" }
    ]
  }
]
""".trimIndent()

@Composable
fun MapComposable(
    ownLocation: UserLocation?,
    ownHeading: Double?,
    users: List<UserLocation>,
    friends: List<FriendEntry>,
    friendLastPing: Map<String, Long>,
    selectedUserId: String?,
    hasFineLocationPermission: Boolean,
    zoomToUserId: String?,
    onZoomToUserIdConsumed: () -> Unit,
    onSelectedUserIdChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val initialPosition = remember {
        val last = UserPrefs.getLastLocation(context)
        if (last != null) {
            CameraPosition.fromLatLngZoom(LatLng(last.first, last.second), last.third)
        } else {
            CameraPosition.fromLatLngZoom(LatLng(37.33, -122.03), 10f)
        }
    }

    val cameraPositionState = rememberCameraPositionState { position = initialPosition }

    val mapLocationSource = remember {
        object : com.google.android.gms.maps.LocationSource {
            private var listener: com.google.android.gms.maps.LocationSource.OnLocationChangedListener? = null

            override fun activate(l: com.google.android.gms.maps.LocationSource.OnLocationChangedListener) {
                listener = l
            }

            override fun deactivate() {
                listener = null
            }

            fun update(loc: UserLocation, heading: Double?) {
                val androidLoc = android.location.Location("where").apply {
                    latitude = loc.lat
                    longitude = loc.lng
                    time = loc.timestamp * 1000
                    if (heading != null) bearing = heading.toFloat()
                    accuracy = 10f
                }
                listener?.onLocationChanged(androidLoc)
            }
        }
    }

    LaunchedEffect(ownLocation, ownHeading) {
        ownLocation?.let { mapLocationSource.update(it, ownHeading) }
    }

    DisposableEffect(Unit) {
        onDispose {
            val pos = cameraPositionState.position
            UserPrefs.setLastLocation(context, pos.target.latitude, pos.target.longitude, pos.zoom)
        }
    }

    LaunchedEffect(ownLocation) {
        if (ownLocation != null && UserPrefs.getLastLocation(context) == null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(ownLocation.lat, ownLocation.lng), 14f)
        }
    }

    LaunchedEffect(zoomToUserId) {
        val id = zoomToUserId ?: return@LaunchedEffect
        val target = if (id == "__own__") {
            ownLocation?.let { LatLng(it.lat, it.lng) }
        } else {
            users.find { it.userId == id }?.let { LatLng(it.lat, it.lng) }
        }
        if (target != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, 15f))
        }
        onZoomToUserIdConsumed()
    }

    // Pre-compute marker data (including @Composable subtitle strings) in composable scope.
    // Filter HIDDEN peers here so MarkerComposable is never instantiated for them.
    val markerData = users.map { user ->
        val friend = friends.find { it.id == user.userId }
        val name = friend?.name ?: user.userId.take(8)
        val nowSec = System.currentTimeMillis() / 1000L
        val display = friend?.displayState(
            nowSeconds = nowSec,
            lastPingSeconds = friendLastPing[user.userId]?.let { it / 1000L },
        ) ?: PeerDisplay.LastSeen(friendLastPing[user.userId]?.let { it / 1000L })
        val style = display.pinStyle
        val subtitle = peerSubtitleText(display)
        Triple(user, Triple(friend, name, style), subtitle)
    }.filter { (_, meta, _) -> meta.third != PeerPinStyle.HIDDEN }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        contentPadding = PaddingValues(bottom = 96.dp + navBarBottom),
        onMapClick = { onSelectedUserIdChange(null) },
        properties = MapProperties(
            isMyLocationEnabled = hasFineLocationPermission,
            mapStyleOptions = MapStyleOptions(mapStyleJson),
        ),
        uiSettings = MapUiSettings(
            myLocationButtonEnabled = false,
            zoomControlsEnabled = false,
            compassEnabled = false,
        ),
        locationSource = mapLocationSource,
    ) {
        markerData.forEach { (user, meta, subtitle) ->
            val (_, name, style) = meta
            val isSelected = selectedUserId == user.userId
            val pinAlpha = if (style == PeerPinStyle.DIMMED) 0.45f else 1f
            key(user.userId) {
                val markerState = rememberUpdatedMarkerState(position = LatLng(user.lat, user.lng))
                MarkerComposable(
                    keys = arrayOf<Any>(name, isSelected, subtitle, style),
                    state = markerState,
                    anchor = Offset(0.5f, 1f),
                    onClick = {
                        scope.launch {
                            onSelectedUserIdChange(if (selectedUserId == user.userId) null else user.userId)
                        }
                        true
                    },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Color.Black.copy(alpha = 0.65f * pinAlpha),
                            contentColor = Color.White.copy(alpha = pinAlpha),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                if (isSelected) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color.White.copy(alpha = 0.7f * pinAlpha),
                                    )
                                }
                            }
                        }
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = pinAlpha),
                        )
                    }
                }
            }
        }
    }
}
