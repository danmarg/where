package net.af0.where

import android.location.Location
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

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
    val density = LocalDensity.current
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPaddingPx = with(density) { (96.dp + navBarBottom).roundToPx() }

    // Pre-compute marker data outside AndroidView so peerSubtitleText (@Composable) is callable.
    val markerData = users.map { user ->
        val friend = friends.find { it.id == user.userId }
        val name = friend?.name ?: user.userId.take(8)
        val nowSec = System.currentTimeMillis() / 1000L
        val display = friend?.displayState(
            nowSeconds = nowSec,
            lastPingSeconds = friendLastPing[user.userId]?.let { it / 1000L },
        ) ?: PeerDisplay.LastSeen(friendLastPing[user.userId]?.let { it / 1000L })
        val pinStyle = display.pinStyle
        val subtitle = peerSubtitleText(display)
        Triple(user, Triple(friend, name, pinStyle), subtitle)
    }.filter { (_, meta, _) -> meta.third != PeerPinStyle.HIDDEN }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var didInitialCenter by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = MapLifecycleObserver { mapViewRef }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Activate or disable the location component when permission state or map readiness changes.
    // mapRef is only set after setStyle completes, so map.style is guaranteed non-null here.
    LaunchedEffect(mapRef, hasFineLocationPermission) {
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val lc = map.locationComponent
        if (!hasFineLocationPermission) {
            if (lc.isLocationComponentActivated) lc.isLocationComponentEnabled = false
            return@LaunchedEffect
        }
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .build(),
            )
        }
        lc.isLocationComponentEnabled = true
        lc.cameraMode = CameraMode.NONE
        lc.renderMode = RenderMode.COMPASS
    }

    // Push own location to the location component (blue dot).
    LaunchedEffect(mapRef, ownLocation, ownHeading) {
        val map = mapRef ?: return@LaunchedEffect
        val loc = ownLocation ?: return@LaunchedEffect
        val lc = map.locationComponent
        if (!lc.isLocationComponentActivated || !lc.isLocationComponentEnabled) return@LaunchedEffect
        lc.forceLocationUpdate(
            Location("where").apply {
                latitude = loc.lat
                longitude = loc.lng
                accuracy = 10f
                time = System.currentTimeMillis()
                ownHeading?.let { bearing = it.toFloat() }
            },
        )
    }

    // Auto-center on first fix when there is no saved camera position.
    LaunchedEffect(mapRef, ownLocation) {
        val map = mapRef ?: return@LaunchedEffect
        val loc = ownLocation ?: return@LaunchedEffect
        if (!didInitialCenter && UserPrefs.getLastLocation(context) == null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.lat, loc.lng), 14.0))
            didInitialCenter = true
        }
    }

    // Zoom to a specific peer or own location.
    LaunchedEffect(mapRef, zoomToUserId) {
        val id = zoomToUserId ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val target = when (id) {
            "__own__" -> ownLocation?.let { LatLng(it.lat, it.lng) }
            else -> users.find { it.userId == id }?.let { LatLng(it.lat, it.lng) }
        }
        if (target != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
        onZoomToUserIdConsumed()
    }

    // Persist camera position on dispose.
    DisposableEffect(Unit) {
        onDispose {
            val pos = mapRef?.cameraPosition ?: return@onDispose
            val target = pos.target ?: return@onDispose
            UserPrefs.setLastLocation(context, target.latitude, target.longitude, pos.zoom.toFloat())
        }
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                onCreate(null)
                getMapAsync { map ->
                    val last = UserPrefs.getLastLocation(ctx)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(if (last != null) LatLng(last.first, last.second) else LatLng(37.33, -122.03))
                        .zoom(last?.third?.toDouble() ?: 10.0)
                        .build()
                    map.uiSettings.isCompassEnabled = false
                    map.setStyle(STYLE_URL) {
                        // mapRef is set here — after style load — so LaunchedEffects that
                        // read mapRef can safely call map.style without null-checking.
                        map.addOnMapClickListener {
                            onSelectedUserIdChange(null)
                            false
                        }
                        mapRef = map
                    }
                }
            }.also { mapViewRef = it }
        },
        update = { _ ->
            val map = mapRef ?: return@AndroidView
            map.setPadding(0, 0, 0, bottomPaddingPx)

            // Rebuild peer markers. Track Marker.id → userId so the click listener
            // can resolve taps without relying on LatLng equality.
            map.clear()
            val markerIds = mutableMapOf<Long, String>()
            markerData.forEach { (user, meta, subtitle) ->
                val (_, name, _) = meta
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(user.lat, user.lng))
                        .title(name)
                        .snippet(if (selectedUserId == user.userId) subtitle else null),
                )
                if (marker != null) markerIds[marker.id] = user.userId
            }
            map.setOnMarkerClickListener { marker ->
                val userId = markerIds[marker.id] ?: return@setOnMarkerClickListener false
                onSelectedUserIdChange(if (selectedUserId == userId) null else userId)
                true
            }
        },
        modifier = modifier,
    )
}
