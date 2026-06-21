package net.af0.where

import android.location.Location
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.Lifecycle
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

// Feeds our known own-location into MyLocationNewOverlay without opening a second
// GPS listener — location is already tracked by LocationService.
private class OwnLocationProvider : IMyLocationProvider {
    private var consumer: IMyLocationConsumer? = null
    private var lastLocation: Location? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer): Boolean {
        consumer = myLocationConsumer
        return true
    }

    override fun stopLocationProvider() { consumer = null }

    override fun getLastKnownLocation(): Location? = lastLocation

    override fun destroy() {}

    fun pushLocation(lat: Double, lng: Double, heading: Double?) {
        val loc = Location("where").apply {
            latitude = lat
            longitude = lng
            accuracy = 10f
            time = System.currentTimeMillis()
            if (heading != null) bearing = heading.toFloat()
        }
        lastLocation = loc
        consumer?.onLocationChanged(loc, this)
    }
}

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
    // Match GmsMapView's contentPadding so map content clears the bottom controls row.
    val bottomPaddingPx = with(density) { (96.dp + navBarBottom).roundToPx() }

    // Pre-compute marker labels (peerSubtitleText is @Composable, can't be called in AndroidView update).
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

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var ownLocationProvider by remember { mutableStateOf<OwnLocationProvider?>(null) }
    var myLocationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var didInitialCenter by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = OsmdroidLifecycleObserver { mapViewRef }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ownLocation, ownHeading) {
        ownLocation?.let { loc ->
            ownLocationProvider?.pushLocation(loc.lat, loc.lng, ownHeading)
        }
        val mv = mapViewRef ?: return@LaunchedEffect
        if (!didInitialCenter && ownLocation != null && UserPrefs.getLastLocation(context) == null) {
            mv.controller.animateTo(GeoPoint(ownLocation.lat, ownLocation.lng), 14.0, 500L)
            didInitialCenter = true
        }
    }

    LaunchedEffect(zoomToUserId) {
        val id = zoomToUserId ?: return@LaunchedEffect
        val mapView = mapViewRef ?: return@LaunchedEffect
        val target = when {
            id == "__own__" -> ownLocation?.let { GeoPoint(it.lat, it.lng) }
            else -> users.find { it.userId == id }?.let { GeoPoint(it.lat, it.lng) }
        }
        if (target != null) {
            mapView.controller.animateTo(target, 15.0, 500L)
        }
        onZoomToUserIdConsumed()
    }

    DisposableEffect(Unit) {
        onDispose {
            myLocationOverlay?.disableMyLocation()
            val mv = mapViewRef ?: return@onDispose
            UserPrefs.setLastLocation(context, mv.mapCenter.latitude, mv.mapCenter.longitude, mv.zoomLevelDouble.toFloat())
        }
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setPadding(0, 0, 0, bottomPaddingPx)
                val last = UserPrefs.getLastLocation(ctx)
                if (last != null) {
                    controller.setZoom(last.third.toDouble())
                    controller.setCenter(GeoPoint(last.first, last.second))
                } else {
                    controller.setZoom(10.0)
                    controller.setCenter(GeoPoint(37.33, -122.03))
                }
                val provider = OwnLocationProvider()
                val overlay = MyLocationNewOverlay(provider, this)
                overlay.enableMyLocation()
                overlays.add(overlay)
                ownLocationProvider = provider
                myLocationOverlay = overlay
            }.also { mapViewRef = it }
        },
        update = { mapView ->
            mapView.setPadding(0, 0, 0, bottomPaddingPx)
            mapView.overlays.removeAll { it is Marker }

            // Peer markers
            markerData.forEach { (user, meta, subtitle) ->
                val (_, name, style) = meta
                val alpha = if (style == PeerPinStyle.DIMMED) 0.45f else 1f
                val marker = Marker(mapView)
                marker.position = GeoPoint(user.lat, user.lng)
                marker.title = name
                marker.snippet = if (selectedUserId == user.userId) subtitle else null
                marker.alpha = alpha
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.setOnMarkerClickListener { _, _ ->
                    onSelectedUserIdChange(if (selectedUserId == user.userId) null else user.userId)
                    true
                }
                mapView.overlays.add(marker)
            }

            mapView.invalidate()
        },
        modifier = modifier,
    )
}
