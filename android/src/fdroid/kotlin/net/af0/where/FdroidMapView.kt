package net.af0.where

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import android.text.TextPaint
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val PEER_SOURCE_ID = "where-peers"
private const val PEER_LAYER_ID = "where-peers"
private const val PEER_ID_PROPERTY = "userId"
private const val PEER_ICON_PROPERTY = "icon"

/**
 * Renders a marker bitmap with a persistent name label above a pin shape, matching the
 * GMS MarkerComposable visual. subtitle is shown below the name when the peer is selected.
 * The pin's tip is the bitmap's bottom-center; the peer layer anchors icons there
 * (ICON_ANCHOR_BOTTOM), on the friend's coordinate (#482).
 */
@VisibleForTesting
internal fun peerMarkerBitmap(
    context: Context,
    name: String,
    subtitle: String?,
    alpha: Float,
): Bitmap {
    val dm = context.resources.displayMetrics
    val dp = dm.density
    val sp = dm.scaledDensity

    val namePaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * sp
            color = Color.WHITE
            this.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
        }
    val subPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * sp
            color = Color.WHITE
            this.alpha = (255 * 0.7f * alpha).roundToInt().coerceIn(0, 255)
        }

    val padH = 5f * dp
    val padV = 2f * dp
    val gap = 2f * dp

    val nameW = namePaint.measureText(name)
    val subW = if (subtitle != null) subPaint.measureText(subtitle) else 0f
    val boxW = maxOf(nameW, subW) + 2 * padH
    val nameAscent = -namePaint.ascent()
    val nameDescent = namePaint.descent()
    val nameLineH = nameAscent + nameDescent
    val subAscent = -subPaint.ascent()
    val subLineH = subAscent + subPaint.descent()
    val boxH = padV + nameLineH + (if (subtitle != null) gap + subLineH else 0f) + padV

    // Pin: circle (diameter = pinW) + triangle below tapering to a point
    val pinW = 24f * dp
    val pinH = 36f * dp
    val pinR = pinW / 2f

    val bmpW = maxOf(boxW, pinW).roundToInt()
    val bmpH = (boxH + pinH).roundToInt()
    val cx = bmpW / 2f

    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Label background — semi-transparent dark box, matching GMS Color.Black.copy(alpha=0.65)
    val bgPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((0.65f * alpha * 255).roundToInt().coerceIn(0, 255), 0, 0, 0)
        }
    val boxX = (bmpW - boxW) / 2f
    canvas.drawRoundRect(RectF(boxX, 0f, boxX + boxW, boxH), 4f * dp, 4f * dp, bgPaint)

    // Name text, centered in the box
    canvas.drawText(name, cx - nameW / 2f, padV + nameAscent, namePaint)

    // Subtitle text (only when selected)
    if (subtitle != null) {
        canvas.drawText(subtitle, cx - subW / 2f, padV + nameLineH + gap + subAscent, subPaint)
    }

    // Pin: filled circle + triangle pointing down, matching GMS MaterialTheme.colorScheme.error
    val pinPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((255 * alpha).roundToInt().coerceIn(0, 255), 176, 0, 32)
        }
    canvas.drawCircle(cx, boxH + pinR, pinR, pinPaint)
    val path =
        Path().apply {
            // Triangle connects at the lower portion of the circle and tapers to the tip
            moveTo(cx - pinR * 0.7f, boxH + pinR + pinR * 0.7f)
            lineTo(cx + pinR * 0.7f, boxH + pinR + pinR * 0.7f)
            lineTo(cx, boxH + pinH)
            close()
        }
    canvas.drawPath(path, pinPaint)

    return bmp
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
    val bottomPaddingPx = with(density) { (96.dp + navBarBottom).roundToPx() }

    // Pre-compute marker data outside AndroidView so peerSubtitleText (@Composable) is callable.
    val markerData =
        users.map { user ->
            val friend = friends.find { it.id == user.userId }
            val name = friend?.name ?: user.userId.take(8)
            val nowSec = System.currentTimeMillis() / 1000L
            val display =
                friend?.displayState(
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

    // The map click listener is added once, at style load; it reads these current values.
    val currentSelectedUserId by rememberUpdatedState(selectedUserId)
    val currentOnSelectedUserIdChange by rememberUpdatedState(onSelectedUserIdChange)

    // Each peer icon image in the style, by image id, with what it was drawn from: an image is
    // re-uploaded only when that changes. A departed friend's image stays (one small bitmap per
    // friend): removing it would race the asynchronous source update still drawing it.
    val peerImageKeys = remember { mutableMapOf<String, Triple<String, String?, Float>>() }

    // The features last given to the peer source, to skip re-sending an unchanged set. Not Compose
    // state: it's written in AndroidView's update, which would then run again.
    val lastPeers = remember { arrayOfNulls<List<Triple<String, LatLng, String>>>(1) }
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
                    // Under friends' pins, as on gms: a friend beside you stays visible.
                    .locationComponentOptions(LocationComponentOptions.builder(context).layerBelow(PEER_LAYER_ID).build())
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
        val target =
            when (id) {
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
                    map.cameraPosition =
                        CameraPosition.Builder()
                            .target(if (last != null) LatLng(last.first, last.second) else LatLng(37.33, -122.03))
                            .zoom(last?.third?.toDouble() ?: 10.0)
                            .build()
                    map.uiSettings.isCompassEnabled = false
                    map.setStyle(STYLE_URL) { style ->
                        style.addSource(GeoJsonSource(PEER_SOURCE_ID))
                        style.addLayer(
                            SymbolLayer(PEER_LAYER_ID, PEER_SOURCE_ID).withProperties(
                                iconImage(Expression.get(PEER_ICON_PROPERTY)),
                                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                                // Every friend is always shown; overlapping pins stack (by screen y
                                // within a tile).
                                iconAllowOverlap(true),
                                iconIgnorePlacement(true),
                            ),
                        )
                        // The pin drawn under the tap, topmost first. A tap on empty map (even just
                        // beside a pin) clears the selection, and so does tapping the selected
                        // friend again.
                        map.addOnMapClickListener { latLng ->
                            val pt = map.projection.toScreenLocation(latLng)
                            val tapped =
                                map.queryRenderedFeatures(pt, PEER_LAYER_ID)
                                    .firstNotNullOfOrNull { it.getStringProperty(PEER_ID_PROPERTY) }
                            currentOnSelectedUserIdChange(if (tapped == null || tapped == currentSelectedUserId) null else tapped)
                            // Empty map: leave the tap to other listeners.
                            tapped != null
                        }
                        // mapRef is set here — after style load — so LaunchedEffects that
                        // read mapRef can safely call map.style without null-checking.
                        mapRef = map
                    }
                }
            }.also { mapViewRef = it }
        },
        update = { _ ->
            val map = mapRef ?: return@AndroidView
            map.setPadding(0, 0, 0, bottomPaddingPx)

            val style = map.style ?: return@AndroidView
            // Peers: one feature each in the peer source, its icon an image keyed by friend.
            val peers =
                markerData.map { (user, meta, subtitle) ->
                    val (_, name, pinStyle) = meta
                    val pinAlpha = if (pinStyle == PeerPinStyle.DIMMED) 0.45f else 1f
                    val shownSubtitle = if (selectedUserId == user.userId) subtitle else null
                    val imageId = "peer-${user.userId}"
                    val key = Triple(name, shownSubtitle, pinAlpha)
                    if (peerImageKeys[imageId] != key) {
                        style.addImage(imageId, peerMarkerBitmap(context, name, shownSubtitle, pinAlpha))
                        peerImageKeys[imageId] = key
                    }
                    Triple(user.userId, LatLng(user.lat, user.lng), imageId)
                }
            // Recomposition is frequent (own fixes, heading, pings); re-tile only on a change.
            if (peers != lastPeers[0]) {
                val features =
                    peers.map { (userId, position, imageId) ->
                        Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude)).apply {
                            addStringProperty(PEER_ID_PROPERTY, userId)
                            addStringProperty(PEER_ICON_PROPERTY, imageId)
                        }
                    }
                style.getSourceAs<GeoJsonSource>(PEER_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
                lastPeers[0] = peers
            }
        },
        modifier = modifier,
    )
}
