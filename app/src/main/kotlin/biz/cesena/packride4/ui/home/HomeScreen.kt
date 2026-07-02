package biz.cesena.packride4.ui.home

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.res.painterResource
import biz.cesena.packride4.ui.common.maneuverIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import biz.cesena.packride4.debug.DebugLog
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

private val BOTTOM_BAR_HEIGHT = 72.dp

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.routeError) {
        val err = uiState.routeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err, duration = SnackbarDuration.Short)
    }

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyleReady by remember { mutableStateOf(false) }
    var initialZoomDone by remember { mutableStateOf(false) }
    var navZoomDone by remember { mutableStateOf(false) }
    if (!uiState.isNavigating) navZoomDone = false

    // Arrow overlay state: screen position + rotation
    var arrowScreenX by remember { mutableStateOf(0f) }
    var arrowScreenY by remember { mutableStateOf(0f) }
    var arrowRotation by remember { mutableStateOf(0f) }
    var arrowVisible by remember { mutableStateOf(false) }
    var cameraBearing by remember { mutableStateOf(0.0) }
    var cameraZoom by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) viewModel.startLocationUpdates()
    }

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(mapInstance, uiState.mapStyleJson) {
        mapInstance?.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
            addMapLayers(style)
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    LaunchedEffect(uiState.hasOfflineMaps) {
        MapLibre.setConnected(if (uiState.hasOfflineMaps) true else null)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mapView = mapViewRef
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START  -> mapView?.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE  -> mapView?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP   -> mapView?.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (mapView != null) { mapView.onStart(); mapView.onResume() }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onPause(); mapView?.onStop()
        }
    }

    // Route line on map
    LaunchedEffect(mapInstance, mapStyleReady, uiState.route) {
        val map = mapInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val routeSource = style.getSourceAs<GeoJsonSource>("route")
        val route = uiState.route
        if (route == null) {
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            val line = LineString.fromLngLats(
                route.points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
            )
            routeSource?.setGeoJson(Feature.fromGeometry(line))
            // Zoom to fit entire route when not navigating.
            // Wait for a valid map size before calling newLatLngBounds — the
            // call silently fails or clips incorrectly when width/height are 0.
            if (!uiState.isNavigating && route.points.size >= 2) {
                val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
                route.points.forEach { (lat, lon) -> boundsBuilder.include(LatLng(lat, lon)) }
                val bounds = boundsBuilder.build()
                if (map.width > 0 && map.height > 0) {
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                } else {
                    // Map not laid out yet — retry after one frame
                    mapViewRef?.post {
                        if (map.width > 0 && map.height > 0) {
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                        }
                    }
                }
            }
        }
    }

    // Fuel stations visible on map (queried from the current viewport, like any other POI —
    // not tied to a calculated route; the route-specific list still feeds the maneuver panel
    // sequence via uiState.fuelStationsAlongRoute)
    LaunchedEffect(mapInstance, uiState.fuelStationsVisible) {
        val map = mapInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val fuelSource = style.getSourceAs<GeoJsonSource>("fuel-stations")
        val stations = uiState.fuelStationsVisible
        if (stations.isEmpty()) {
            fuelSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            val features = stations.map { poi ->
                Feature.fromGeometry(Point.fromLngLat(poi.lon, poi.lat)).apply {
                    addStringProperty("name", poi.name)
                }
            }
            fuelSource?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }

    // Debug POIs on map
    LaunchedEffect(mapInstance, uiState.debugPois) {
        val map = mapInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        var source = style.getSourceAs<GeoJsonSource>("debug-pois")
        if (source == null) {
            style.addSource(GeoJsonSource("debug-pois"))
            source = style.getSourceAs<GeoJsonSource>("debug-pois")
        }
        val pois = uiState.debugPois
        if (pois.isEmpty()) {
            source?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            val features = pois.map { poi ->
                Feature.fromGeometry(Point.fromLngLat(poi.lon, poi.lat)).apply {
                    addStringProperty("name", poi.name)
                    addStringProperty("category", poi.category)
                }
            }
            source?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }

    // Waypoint markers + desired-path line on map
    LaunchedEffect(mapInstance, uiState.waypoints, uiState.selectedWaypointIndex, uiState.isEditingRoute) {
        val map = mapInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        val waypointSource = style.getSourceAs<GeoJsonSource>("waypoint-markers")
        val desiredSource = style.getSourceAs<GeoJsonSource>("desired-path")

        val wps = uiState.waypoints.filter { it.isSet }
        if (wps.isEmpty() || !uiState.isEditingRoute) {
            waypointSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            desiredSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return@LaunchedEffect
        }

        // Waypoint marker features with type property for styling
        val setIndices = uiState.waypoints.mapIndexedNotNull { i, wp -> if (wp.isSet) i else null }
        val features = wps.mapIndexed { i, wp ->
            val f = Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat))
            val type = when {
                i == 0 -> "origin"
                i == wps.size - 1 -> "destination"
                else -> "intermediate"
            }
            f.addStringProperty("wp-type", type)
            f.addNumberProperty("wp-index", setIndices[i].toDouble())
            f.addBooleanProperty("selected", setIndices[i] == uiState.selectedWaypointIndex)
            f
        }
        waypointSource?.setGeoJson(FeatureCollection.fromFeatures(features))

        // Desired-path: straight lines between waypoints
        if (wps.size >= 2) {
            val linePoints = wps.map { Point.fromLngLat(it.lon, it.lat) }
            desiredSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(linePoints)))
        } else {
            desiredSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    // Map click listener (only when route editing or simulated GPS)
    DisposableEffect(mapInstance, uiState.isEditingRoute, uiState.isSimulatedGps) {
        val map = mapInstance ?: return@DisposableEffect onDispose {}
        if (!uiState.isEditingRoute && !uiState.isSimulatedGps) return@DisposableEffect onDispose {}

        val clickListener = org.maplibre.android.maps.MapLibreMap.OnMapClickListener { latLng ->
            viewModel.handleMapTap(latLng.latitude, latLng.longitude, map.cameraPosition.zoom)
        }
        map.addOnMapClickListener(clickListener)
        onDispose { map.removeOnMapClickListener(clickListener) }
    }

    // Long-press listener: always active (route editing + non-navigation favorite saving)
    DisposableEffect(mapInstance) {
        val map = mapInstance ?: return@DisposableEffect onDispose {}
        val longClickListener = org.maplibre.android.maps.MapLibreMap.OnMapLongClickListener { latLng ->
            viewModel.handleMapLongPress(latLng.latitude, latLng.longitude, map.cameraPosition.zoom)
        }
        map.addOnMapLongClickListener(longClickListener)
        onDispose { map.removeOnMapLongClickListener(longClickListener) }
    }

    // GPS dot + follow camera + initial zoom
    LaunchedEffect(uiState.lastKnownPosition, uiState.isFollowing, uiState.isNavigating) {
        val pos = uiState.lastKnownPosition ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        // Use snapped position during navigation, raw GPS otherwise
        val displayLat = if (uiState.isNavigating) pos.snappedLat ?: pos.latitude else pos.latitude
        val displayLon = if (uiState.isNavigating) pos.snappedLon ?: pos.longitude else pos.longitude
        val feature = Feature.fromGeometry(Point.fromLngLat(displayLon, displayLat))
        if (pos.hasBearing) feature.addNumberProperty("bearing", pos.bearing.toDouble())
        val style = map.style
        (style?.getSourceAs<GeoJsonSource>("user-location"))?.setGeoJson(feature)
        // Update arrow overlay position
        val screenPt = map.projection.toScreenLocation(LatLng(displayLat, displayLon))
        arrowScreenX = screenPt.x
        arrowScreenY = screenPt.y
        arrowRotation = if (pos.hasBearing) pos.bearing else 0f
        cameraBearing = map.cameraPosition.bearing
        cameraZoom = map.cameraPosition.zoom
        arrowVisible = true
        if (!initialZoomDone) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.latitude, pos.longitude), 14.0))
            initialZoomDone = true
        } else if (uiState.isFollowing && uiState.isNavigating) {
            val bearing = if (uiState.mapOrientationNorthUp) 0.0
                          else if (pos.hasBearing) pos.bearing.toDouble()
                          else map.cameraPosition.bearing
            val zoom = if (!navZoomDone) 17.0 else map.cameraPosition.zoom
            val tilt = if (!navZoomDone) { if (uiState.mapOrientationNorthUp) 0.0 else 45.0 } else map.cameraPosition.tilt
            // Shift focal point to 75% from top so more road ahead is visible
            val mapHeight = map.height
            val mapWidth = map.width
            val topPad = (mapHeight * 0.50).toInt()
            val leftPad = if (uiState.showManeuverPanel) (mapWidth * 0.20).toInt() else 0
            @Suppress("DEPRECATION") map.setPadding(leftPad, topPad, 0, 0)
            navZoomDone = true
            map.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(displayLat, displayLon))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(bearing)
                    .build()
            ))
        } else if (uiState.isFollowing) {
            @Suppress("DEPRECATION") map.setPadding(0, 0, 0, 0)
            val bearing = if (uiState.mapOrientationNorthUp) 0.0
                          else if (pos.hasBearing) pos.bearing.toDouble()
                          else map.cameraPosition.bearing
            val currentTilt = map.cameraPosition.tilt
            val currentBearing = map.cameraPosition.bearing
            if (currentTilt > 1.0 || Math.abs(currentBearing - bearing) > 1.0) {
                map.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(pos.latitude, pos.longitude))
                        .zoom(map.cameraPosition.zoom)
                        .tilt(0.0)
                        .bearing(bearing)
                        .build()
                ))
            } else {
                map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(pos.latitude, pos.longitude)))
            }
        }
    }

    // ── Usable area padding (accounts for system bars, cutout, sidebar) ──
    val safeTop = maxOf(
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    )
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeEnd = WindowInsets.navigationBars.asPaddingValues().calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    val contentStart = 12.dp
    val contentEnd = 12.dp + safeEnd
    val contentTop = safeTop + 8.dp

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Map (full screen behind everything) ──────────────────────────────
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { mapView ->
                    mapView.addOnDidFailLoadingMapListener { e -> DebugLog.log("map FAILED: $e") }
                    // D-pad / remote control panning doesn't go through the touch gesture
                    // detector, so it needs its own signal to stop auto-recentering.
                    mapView.setOnKeyListener { v, keyCode, event ->
                        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                             keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                             keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                             keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                        ) {
                            viewModel.setFollowing(false)
                            // MapLibre processes the key pan after we return false, so post
                            // the arrow recalculation to run on the next looper iteration
                            // when the camera has already moved — same live update as touch drag.
                            v.post {
                                val pos = uiState.lastKnownPosition ?: return@post
                                val map = mapInstance ?: return@post
                                val lat = if (uiState.isNavigating) pos.snappedLat ?: pos.latitude else pos.latitude
                                val lon = if (uiState.isNavigating) pos.snappedLon ?: pos.longitude else pos.longitude
                                val screenPt = map.projection.toScreenLocation(LatLng(lat, lon))
                                arrowScreenX = screenPt.x
                                arrowScreenY = screenPt.y
                            }
                        }
                        false
                    }
                    mapView.getMapAsync { map ->
                        mapInstance = map
                        map.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isTiltGesturesEnabled = true
                            val statusPx = (ctx.resources.displayMetrics.density * 56).toInt()
                            map.uiSettings.setCompassMargins(0, statusPx, 0, 0)
                            addMapLayers(style)
                            mapStyleReady = true
                        }
                        // Refresh fuel-station POIs for the current viewport, same minzoom
                        // threshold (14) as the other vector-tile POI layers.
                        map.addOnCameraIdleListener {
                            if (map.cameraPosition.zoom < 14.0) {
                                viewModel.clearVisibleFuelStations()
                            } else {
                                val bounds = map.projection.visibleRegion.latLngBounds
                                viewModel.refreshVisibleFuelStations(
                                    bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast
                                )
                            }
                        }
                        // Update arrow screen position on every camera change
                        map.addOnCameraMoveListener {
                            val pos = uiState.lastKnownPosition ?: return@addOnCameraMoveListener
                            val lat = if (uiState.isNavigating) pos.snappedLat ?: pos.latitude else pos.latitude
                            val lon = if (uiState.isNavigating) pos.snappedLon ?: pos.longitude else pos.longitude
                            val screenPt = map.projection.toScreenLocation(LatLng(lat, lon))
                            arrowScreenX = screenPt.x
                            arrowScreenY = screenPt.y
                            arrowRotation = if (pos.hasBearing) pos.bearing else 0f
                            cameraBearing = map.cameraPosition.bearing
                            arrowVisible = true
                        }
                        // A real pan (finger drag or D-pad) should stop auto-recentering;
                        // pinch-zoom and tilt gestures use separate listeners and never reach
                        // here, so follow mode stays on for those. Small accidental touches are
                        // tolerated up to a threshold before we treat it as an intentional pan.
                        val panThresholdPx = ctx.resources.displayMetrics.density * 24
                        var moveStartFocal: android.graphics.PointF? = null
                        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                moveStartFocal = detector.focalPoint
                            }
                            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                val start = moveStartFocal ?: return
                                val current = detector.focalPoint
                                val distance = kotlin.math.hypot(
                                    (current.x - start.x).toDouble(),
                                    (current.y - start.y).toDouble()
                                )
                                if (distance > panThresholdPx) {
                                    viewModel.setFollowing(false)
                                }
                            }
                            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                        })
                        val saved = viewModel.savedPosition
                        if (saved != null) {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(saved.first, saved.second), 14.0))
                        } else {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 5.5))
                        }
                    }
                    mapViewRef = mapView
                }
            },
            onRelease = { it.onPause(); it.onStop(); it.onDestroy() },
            modifier = Modifier.fillMaxSize()
        )

        // ── Navigation arrow overlay (Compose, always on top) ────────────────
        if (arrowVisible && uiState.lastKnownPosition != null) {
            val density = context.resources.displayMetrics.density
            val arrowSizePx = 80 * density
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawIntoCanvas { canvas ->
                    val nCanvas = canvas.nativeCanvas
                    val cx = arrowScreenX
                    val cy = arrowScreenY
                    nCanvas.save()
                    val displayRotation = arrowRotation - cameraBearing.toFloat()
                    nCanvas.rotate(displayRotation, cx, cy)
                    val path = android.graphics.Path().apply {
                        moveTo(cx, cy - arrowSizePx * 0.35f)
                        lineTo(cx + arrowSizePx * 0.21f, cy + arrowSizePx * 0.14f)
                        lineTo(cx, cy)
                        lineTo(cx - arrowSizePx * 0.21f, cy + arrowSizePx * 0.14f)
                        close()
                    }
                    val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(80, 0, 0, 0)
                        style = android.graphics.Paint.Style.FILL
                        maskFilter = android.graphics.BlurMaskFilter(3f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    nCanvas.save()
                    nCanvas.translate(1f * density, 2f * density)
                    nCanvas.drawPath(path, shadowPaint)
                    nCanvas.restore()
                    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.parseColor("#1a73e8")
                        style = android.graphics.Paint.Style.FILL
                    }
                    nCanvas.drawPath(path, fillPaint)
                    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.5f * density
                    }
                    nCanvas.drawPath(path, borderPaint)
                    nCanvas.restore()
                }
            }
        }

        // ── Status bar scrim ────────────────────────────────────────────────
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(safeTop)
                .background(Color.Black.copy(alpha = 0.40f))
                .align(Alignment.TopStart)
        )

        // ── WEB chip (top-right, below safe area) ───────────────────────────
        if (!uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = contentTop, end = contentEnd),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "WEB",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Offline maps warning (top-center, below safe area) ──────────────
        if (!uiState.hasOfflineMaps && uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = contentTop, start = contentStart, end = contentEnd),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Nessuna mappa offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        // ── Delete selected waypoint (top, only when intermediate waypoint selected) ──
        if (uiState.isEditingRoute && uiState.selectedWaypointIndex > 0 &&
            uiState.selectedWaypointIndex < uiState.waypoints.size - 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = contentTop, start = contentStart, end = contentEnd),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        text = "Tocca la mappa per spostare · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.removeSelectedWaypoint() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, "Elimina tappa",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // ── Left column (zoom + speed limit) ────────────────────────────────
        val topBarHeight = if (uiState.isNavigating && uiState.route != null) 120.dp else 0.dp
        val hideLeftButtons = uiState.isNavigating && uiState.showManeuverPanel
        if (!hideLeftButtons) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = contentStart, top = topBarHeight, bottom = BOTTOM_BAR_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { mapInstance?.animateCamera(CameraUpdateFactory.zoomIn()) },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            if (!uiState.isNavigating || uiState.route == null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Text(
                        "${"%.1f".format(cameraZoom)}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            SmallFloatingActionButton(
                onClick = { mapInstance?.animateCamera(CameraUpdateFactory.zoomOut()) },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text("−", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        } // hideLeftButtons

        // ── Right column (GPS follow + orientation + speed limit) ───────────
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = contentEnd, top = topBarHeight, bottom = BOTTOM_BAR_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { viewModel.toggleFollow() },
                containerColor = if (uiState.isFollowing)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    if (uiState.isFollowing) Icons.Default.GpsFixed else Icons.Default.MyLocation,
                    contentDescription = "Segui GPS",
                    tint = if (uiState.isFollowing)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SmallFloatingActionButton(
                onClick = {
                    val newNorthUp = !uiState.mapOrientationNorthUp
                    viewModel.toggleMapOrientation()
                    val pos = uiState.lastKnownPosition
                    val bearing = if (newNorthUp) 0.0
                                  else if (pos?.hasBearing == true) pos.bearing.toDouble()
                                  else mapInstance?.cameraPosition?.bearing ?: 0.0
                    mapInstance?.animateCamera(CameraUpdateFactory.bearingTo(bearing))
                },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    if (uiState.mapOrientationNorthUp) "N" else "↑",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
            if (uiState.currentSpeedLimit > 0) {
                SpeedLimitSign(limit = uiState.currentSpeedLimit, isOfficial = uiState.isSpeedLimitOfficial)
            }
            if (biz.cesena.packride4.BuildConfig.DEBUG) {
                SmallFloatingActionButton(
                    onClick = { viewModel.toggleSimulatedGps() },
                    containerColor = if (uiState.isSimulatedGps)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = if (uiState.isSimulatedGps)
                        MaterialTheme.colorScheme.onError
                    else
                        MaterialTheme.colorScheme.onSurface,
                ) {
                    Text("SIM", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
        }

        // ── Navigation instruction banner + progress (TOP, only when navigating) ──
        if (uiState.isNavigating && uiState.route != null) {
            NavigationInstructionBanner(
                uiState = uiState,
                onStop = { viewModel.stopNavigation() },
                showProgressBar = uiState.showProgressBar,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = contentStart, end = contentEnd, top = contentTop),
            )
        }

        // ── Navigation maneuver side panel (LEFT, toggled by flag button) ──
        if (uiState.isNavigating && uiState.route != null && uiState.showManeuverPanel) {
            NavigationManeuverPanel(
                uiState = uiState,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.20f)
                    .padding(top = topBarHeight, bottom = BOTTOM_BAR_HEIGHT),
            )
        }

        // ── Route ready panel (above bottom bar) ─────────────────────────────
        if (!uiState.isNavigating && uiState.route != null) {
            var showInstructionList by remember { mutableStateOf(false) }
            RouteReadyPanel(
                route = uiState.route!!,
                destinationName = uiState.destinationName,
                onStart = { viewModel.startNavigation() },
                onCancel = { viewModel.clearRoute() },
                onShowInstructions = { showInstructionList = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = contentStart, end = contentEnd, top = contentTop)
            )
            if (showInstructionList) {
                InstructionListPanel(
                    route = uiState.route!!,
                    destinationName = uiState.destinationName,
                    onClose = { showInstructionList = false },
                )
            }
        }

        // ── Bottom bar (always visible) ──────────────────────────────────────
        BottomBar(
            uiState = uiState,
            onNavigateClick = { viewModel.openRoutePlanner() },
            onMenuClick = { viewModel.toggleMenu() },
            onLeftWidgetClick = { viewModel.openWidgetSelector("left") },
            onRightWidgetClick = { viewModel.openWidgetSelector("right") },
            onManeuverPanelClick = { viewModel.toggleManeuverPanel() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // ── Fullscreen info panel ────────────────────────────────────────────
        if (uiState.showInfoFullscreen) {
            FullscreenInfoPanel(
                uiState = uiState,
                selectingSide = uiState.selectingWidgetSide,
                onSelectValue = { viewModel.selectWidgetValue(it) },
                onClose = { viewModel.toggleInfoFullscreen() },
            )
        }

        // ── Fullscreen menu ──────────────────────────────────────────────────
        if (uiState.showMenu) {
            when (uiState.menuSubScreen) {
                "maps" -> biz.cesena.packride4.ui.mapmanager.MapManagerScreen(
                    onBack = { viewModel.closeMenuSubScreen() },
                    onClose = { viewModel.closeMenuAll() },
                    onLoginRequired = {},
                )
                "routes" -> biz.cesena.packride4.ui.savedroutes.SavedRoutesScreen(
                    onGoToMap = { viewModel.closeMenuAll() },
                    onBack = { viewModel.closeMenuSubScreen() },
                    onClose = { viewModel.closeMenuAll() },
                )
                "settings" -> biz.cesena.packride4.ui.settings.SettingsScreen(
                    onBack = { viewModel.closeMenuSubScreen() },
                    onClose = { viewModel.closeMenuAll() },
                )
                "favorites" -> FavoritesScreen(
                    favorites = uiState.favorites,
                    onBack = { viewModel.closeMenuSubScreen() },
                    onSave = { viewModel.saveFavorite(it) },
                    onDelete = { viewModel.deleteFavorite(it) },
                )
                "info" -> DiagnosticsScreen(
                    routingManager = viewModel.routingManager,
                    context = androidx.compose.ui.platform.LocalContext.current,
                    onBack = { viewModel.closeMenuSubScreen() },
                )
                else -> MenuScreen(
                    onClose = { viewModel.closeMenuAll() },
                    onNavigateToMaps = { viewModel.openMenuSubScreen("maps") },
                    onNavigateToRoutes = { viewModel.openMenuSubScreen("routes") },
                    onNavigateToSettings = { viewModel.openMenuSubScreen("settings") },
                    onNavigateToFavorites = { viewModel.openMenuSubScreen("favorites") },
                    onNavigateToInfo = { viewModel.openMenuSubScreen("info") },
                )
            }
        }

        // ── Route planner fullscreen ─────────────────────────────────────────
        if (uiState.showRoutePlanner) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                RoutePlannerSheet(
                    waypoints = uiState.waypoints,
                    editingIndex = uiState.plannerEditingIndex,
                    searchQuery = uiState.plannerSearchQuery,
                    searchResults = uiState.plannerSearchResults,
                    searchLoading = uiState.plannerSearchLoading,
                    onClose = { viewModel.closeRoutePlanner() },
                    onAddWaypoint = { viewModel.addWaypoint() },
                    onRemoveWaypoint = { viewModel.removeWaypoint(it) },
                    onStartEditing = { viewModel.startEditingWaypoint(it) },
                    onSearchChange = { viewModel.onPlannerSearchChange(it) },
                    recentDestinations = uiState.recentDestinations,
                    favorites = uiState.favorites,
                    onSaveFavorite = { viewModel.saveFavorite(it) },
                    onSelectResult = { viewModel.selectPlannerResult(it) },
                    onResetOriginToGps = { viewModel.resetOriginToGps() },
                    onCalculate = { viewModel.computeRouteFromWaypoints() },
                )
            }
        }

        // ── Route calculating spinner ─────────────────────────────────────────
        if (uiState.isRouteCalculating) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }

        // ── Snackbar for errors ──────────────────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = BOTTOM_BAR_HEIGHT + safeBottom + 8.dp)
        )

        // ── GPS permission rationale ─────────────────────────────────────────
        if (!locationPermissions.allPermissionsGranted) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = safeBottom + 16.dp, start = contentStart, end = contentEnd),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Posizione GPS necessaria",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(4.dp))
                    Text("PackRide ha bisogno della posizione per centrare la mappa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                        Text("Concedi permesso")
                    }
                }
            }
        }

        // ── Long-press context sheet ──────────────────────────────────────────
        uiState.pendingLongPress?.let { (lat, lon) ->
            LongPressSheet(
                lat = lat, lon = lon,
                onDismiss = { viewModel.dismissLongPress() },
                onSaveFavorite = { fav ->
                    viewModel.saveFavorite(fav)
                    viewModel.dismissLongPress()
                },
            )
        }
    }
}

// ── Bottom panel: route ready (not yet navigating) ────────────────────────────

@Composable
private fun RouteReadyPanel(
    route: biz.cesena.packride4.routing.RouteResult,
    destinationName: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onShowInstructions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "Cancella", tint = MaterialTheme.colorScheme.error)
            }
            Column(modifier = Modifier
                .weight(1f)
                .clickable(onClick = onShowInstructions)
            ) {
                if (destinationName.isNotBlank()) {
                    Text(destinationName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1)
                }
                Text(
                    "${formatDistance(route.distanceMeters)}  ·  ${formatDuration(route.timeMillis)}  ▼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FloatingActionButton(
                onClick = onStart,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Navigation, "Avvia",
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

// ── Navigation instruction banner (bottom, during navigation) ────────────────

@Composable
private fun NavigationInstructionBanner(
    uiState: HomeUiState,
    onStop: () -> Unit,
    showProgressBar: Boolean = false,
    modifier: Modifier = Modifier
) {
    val route = uiState.route ?: return
    val idx = uiState.currentInstructionIndex
    // Show the next maneuver (what to do at end of current segment)
    val displayIdx = if (idx < route.instructions.size - 1) idx + 1 else idx
    val currentInstruction = route.instructions.getOrNull(displayIdx)

    Row(
        modifier = modifier.height(IntrinsicSize.Max),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Large icon box — stretches to match right column height
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 8.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight(),
            ) {
                Icon(
                    painter = painterResource(maneuverIcon(currentInstruction?.sign ?: 0, currentInstruction?.modifier ?: "", currentInstruction?.exitNumber ?: 0, currentInstruction?.turnAngle ?: Double.NaN)),
                    contentDescription = null,
                    modifier = Modifier.size(68.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                val exitNum = currentInstruction?.exitNumber ?: 0
                if (exitNum > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.size(18.dp).align(Alignment.BottomEnd),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(exitNum.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        // Right column: text banner + separate progress bar
        Column(modifier = Modifier.weight(1f)) {
            // Text banner
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (uiState.distanceToNextManeuver > 0) {
                        Text(
                            text = formatDistance(uiState.distanceToNextManeuver),
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val instrText = currentInstruction?.let { instr ->
                        val base = instr.text.takeIf { it.isNotBlank() } ?: "Segui il percorso"
                        if (instr.sign == 0 && instr.distanceMeters > 500) {
                            "$base per ${formatDistance(instr.distanceMeters)}"
                        } else base
                    } ?: "Segui il percorso"
                    Text(
                        text = instrText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Close, "Stop navigazione",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // Progress bar — staccata, sotto al testo
            if (showProgressBar) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shadowElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                ) {
                    RouteProgressBar(
                        route = route,
                        remainingDistance = uiState.remainingDistance,
                        waypoints = uiState.waypoints,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedLimitSign(limit: Int, isOfficial: Boolean = true, modifier: Modifier = Modifier) {
    val borderColor = if (isOfficial) Color.Red else Color.Gray
    val textColor = if (isOfficial) Color.Black else Color.Gray
    Surface(
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 4.dp,
        border = BorderStroke(3.dp, borderColor)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "$limit",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> "${"%.1f".format(meters / 1000)} km"
    else           -> "${meters.roundToInt()} m"
}

private fun formatDuration(millis: Long): String {
    val totalMin = (millis / 60_000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ── Debug log dialog (also used by AppNavigation sidebar) ────────────────────

@Composable
internal fun SidebarDebugLogDialog(onDismiss: () -> Unit) = DebugLogDialog(onDismiss)

@Composable
private fun DebugLogDialog(onDismiss: () -> Unit) {
    val lines by DebugLog.lines.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log di debug") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                items(lines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        dismissButton = { TextButton(onClick = { DebugLog.clear() }) { Text("Pulisci") } }
    )
}

// ── Route progress bar ──────────────────────────────────────────────────────

@Composable
private fun RouteProgressBar(
    route: biz.cesena.packride4.routing.RouteResult,
    remainingDistance: Double,
    waypoints: List<RouteWaypoint>,
    modifier: Modifier = Modifier,
) {
    val totalDist = route.distanceMeters
    val progress = if (totalDist > 0) ((totalDist - remainingDistance) / totalDist).toFloat().coerceIn(0f, 1f) else 0f

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val onSurface = MaterialTheme.colorScheme.onSurface

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
        ) {
            val w = size.width
            val h = size.height
            val cy = h / 2
            val barY = cy
            val barH = 4.dp.toPx()
            val iconSize = 8.dp.toPx()

            // Track background
            drawLine(
                color = trackColor,
                start = androidx.compose.ui.geometry.Offset(iconSize * 2, barY),
                end = androidx.compose.ui.geometry.Offset(w - iconSize * 2, barY),
                strokeWidth = barH,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )

            // Progress fill
            val progressEnd = iconSize * 2 + (w - iconSize * 4) * progress
            drawLine(
                color = primaryColor,
                start = androidx.compose.ui.geometry.Offset(iconSize * 2, barY),
                end = androidx.compose.ui.geometry.Offset(progressEnd, barY),
                strokeWidth = barH,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )

            // Start arrow ▶
            val arrowPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(2.dp.toPx(), cy - iconSize)
                lineTo(2.dp.toPx() + iconSize * 1.5f, cy)
                lineTo(2.dp.toPx(), cy + iconSize)
                close()
            }
            drawPath(arrowPath, color = primaryColor)

            // Finish flag 🏁
            val flagX = w - iconSize * 1.5f
            val flagSize = iconSize * 1.2f
            // Pole
            drawLine(
                color = onSurface,
                start = androidx.compose.ui.geometry.Offset(flagX, cy - flagSize),
                end = androidx.compose.ui.geometry.Offset(flagX, cy + flagSize),
                strokeWidth = 1.5.dp.toPx(),
            )
            // Checkered flag (simple)
            drawRect(
                color = onSurface,
                topLeft = androidx.compose.ui.geometry.Offset(flagX, cy - flagSize),
                size = androidx.compose.ui.geometry.Size(flagSize, flagSize),
            )
            drawRect(
                color = androidx.compose.ui.graphics.Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(flagX + flagSize / 2, cy - flagSize),
                size = androidx.compose.ui.geometry.Size(flagSize / 2, flagSize / 2),
            )
            drawRect(
                color = androidx.compose.ui.graphics.Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(flagX, cy - flagSize / 2),
                size = androidx.compose.ui.geometry.Size(flagSize / 2, flagSize / 2),
            )

            // Waypoint flags (intermediate only)
            if (waypoints.size > 2 && totalDist > 0) {
                val intermediates = waypoints.drop(1).dropLast(1)
                val waypointCount = intermediates.size
                for (i in intermediates.indices) {
                    val frac = (i + 1).toFloat() / (waypoints.size - 1).toFloat()
                    val x = iconSize * 2 + (w - iconSize * 4) * frac
                    // Small flag
                    drawLine(
                        color = onSurface,
                        start = androidx.compose.ui.geometry.Offset(x, cy - iconSize * 0.8f),
                        end = androidx.compose.ui.geometry.Offset(x, cy + iconSize * 0.8f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawRect(
                        color = androidx.compose.ui.graphics.Color(0xFFF9A825),
                        topLeft = androidx.compose.ui.geometry.Offset(x, cy - iconSize * 0.8f),
                        size = androidx.compose.ui.geometry.Size(iconSize * 0.7f, iconSize * 0.6f),
                    )
                }
            }

            // Current position dot
            drawCircle(
                color = primaryColor,
                radius = iconSize * 0.6f,
                center = androidx.compose.ui.geometry.Offset(progressEnd, barY),
            )
            drawCircle(
                color = androidx.compose.ui.graphics.Color.White,
                radius = iconSize * 0.3f,
                center = androidx.compose.ui.geometry.Offset(progressEnd, barY),
            )
        }
    }

// ── Navigation maneuver side panel ──────────────────────────────────────────

sealed class ManeuverListItem {
    data class Instruction(
        val instr: biz.cesena.packride4.routing.RouteInstruction,
        val nextInstr: biz.cesena.packride4.routing.RouteInstruction?,
    ) : ManeuverListItem()
    data class FuelStation(
        val poi: biz.cesena.packride4.routing.OfflineGeocodingService.PoiResult,
        val distFromPrevManeuver: Double,
    ) : ManeuverListItem()
}

@Composable
private fun NavigationManeuverPanel(
    uiState: HomeUiState,
    modifier: Modifier = Modifier,
) {
    val route = uiState.route ?: return
    val startIdx = uiState.currentInstructionIndex + 1
    val instructions = route.instructions
    if (startIdx >= instructions.size) return

    val cumulDist = remember(instructions) {
        var acc = 0.0
        instructions.map { instr -> acc.also { acc += instr.distanceMeters } }
    }
    val totalDist = cumulDist.lastOrNull()?.let { it + instructions.last().distanceMeters } ?: 0.0

    val fuelStations = uiState.fuelStationsAlongRoute

    // Build a flat ordered list of upcoming instructions + fuel stops.
    // No item cap here — LazyColumn clips naturally to whatever height the
    // panel is given, and always shows the first N items that fit on screen.
    // When an instruction is completed (startIdx advances), the list is rebuilt
    // from the new startIdx so the next instruction fills the freed slot.
    val items = remember(startIdx, instructions, fuelStations) {
        val list = mutableListOf<ManeuverListItem>()
        for (i in startIdx until instructions.size) {
            val prevDist = cumulDist[i]
            val nextDist = if (i + 1 < cumulDist.size) cumulDist[i + 1] else totalDist
            for (fuel in fuelStations) {
                if (fuel.distanceAlongRoute >= prevDist && fuel.distanceAlongRoute < nextDist) {
                    list.add(ManeuverListItem.FuelStation(fuel, distFromPrevManeuver = fuel.distanceAlongRoute - prevDist))
                }
            }
            list.add(ManeuverListItem.Instruction(instructions[i], instructions.getOrNull(i + 1)))
        }
        list
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        userScrollEnabled = false,
    ) {
        items(items, key = { item ->
            when (item) {
                is ManeuverListItem.Instruction -> "instr_${item.instr.text}_${item.instr.distanceMeters}"
                is ManeuverListItem.FuelStation -> "fuel_${item.poi.distanceAlongRoute}"
            }
        }) { item ->
            when (item) {
                is ManeuverListItem.Instruction -> ManeuverBanner(item.instr, item.nextInstr)
                is ManeuverListItem.FuelStation -> FuelBanner(item.poi, item.distFromPrevManeuver)
            }
        }
    }
}

@Composable
private fun ManeuverBanner(
    instr: biz.cesena.packride4.routing.RouteInstruction,
    nextInstr: biz.cesena.packride4.routing.RouteInstruction? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconInstr = nextInstr ?: instr
            Icon(
                painter = painterResource(maneuverIcon(iconInstr.sign, iconInstr.modifier, iconInstr.exitNumber, iconInstr.turnAngle)),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column {
                Text(
                    formatDistance(instr.distanceMeters),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    instr.text,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun FuelBanner(
    poi: biz.cesena.packride4.routing.OfflineGeocodingService.PoiResult,
    distFromPrevManeuver: Double,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("⛽", fontSize = 16.sp)
            Column {
                if (poi.distanceFromTrack > 50) {
                    Text(
                        "↔ ${formatDistance(poi.distanceFromTrack)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else {
                    Text(
                        "sulla via",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(
                    "+${formatDistance(distFromPrevManeuver)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── Instruction list (fullscreen, after route calculation) ───────────────────

@Composable
private fun InstructionListPanel(
    route: biz.cesena.packride4.routing.RouteResult,
    destinationName: String,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        destinationName.ifBlank { "Percorso" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        "${formatDistance(route.distanceMeters)}  ·  ${formatDuration(route.timeMillis)}  ·  ${route.instructions.size} manovre",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Chiudi")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(route.instructions) { instr ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(maneuverIcon(instr.sign, instr.modifier, instr.exitNumber, instr.turnAngle)),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                instr.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                        Text(
                            formatDistance(instr.distanceMeters),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

// ── Map layer setup ──────────────────────────────────────────────────────────

private fun addMapLayers(style: Style) {

    // ── Waypoint pin icons ──
    fun createPinBitmap(colorHex: String): android.graphics.Bitmap {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val w = (32 * density).toInt(); val h = (44 * density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(colorHex)
            this.style = android.graphics.Paint.Style.FILL
        }
        val cx = w / 2f; val r = 12f * density; val tipY = h.toFloat()
        canvas.drawCircle(cx, r + 2f * density, r, paint)
        val tri = android.graphics.Path().apply {
            moveTo(cx - 8f * density, r + 8f * density)
            lineTo(cx + 8f * density, r + 8f * density)
            lineTo(cx, tipY)
            close()
        }
        canvas.drawPath(tri, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, r + 2f * density, 5f * density, paint)
        return bmp
    }
    if (style.getImage("pin-origin") == null) style.addImage("pin-origin", createPinBitmap("#1a73e8"))
    if (style.getImage("pin-destination") == null) style.addImage("pin-destination", createPinBitmap("#d93025"))
    if (style.getImage("pin-intermediate") == null) style.addImage("pin-intermediate", createPinBitmap("#f9a825"))

    // ── Sources ──
    if (style.getSource("user-location") == null) style.addSource(GeoJsonSource("user-location"))
    if (style.getSource("route") == null) style.addSource(GeoJsonSource("route"))
    if (style.getSource("desired-path") == null) style.addSource(GeoJsonSource("desired-path"))
    if (style.getSource("waypoint-markers") == null) style.addSource(GeoJsonSource("waypoint-markers"))
    if (style.getSource("fuel-stations") == null) style.addSource(GeoJsonSource("fuel-stations"))
    if (style.getSource("debug-pois") == null) style.addSource(GeoJsonSource("debug-pois"))

    // ── Desired-path layer (thin dashed gray line between waypoints) ──
    if (style.getLayer("desired-path") == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer("desired-path", "desired-path").withProperties(
                PropertyFactory.lineColor("#888888"),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(0.6f),
                PropertyFactory.lineDasharray(arrayOf(4f, 4f))
            )
        )
    }

    // ── Route line layer ──
    if (style.getLayer("route") == null) {
        style.addLayerAbove(
            org.maplibre.android.style.layers.LineLayer("route", "route").withProperties(
                PropertyFactory.lineColor("#FF1493"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.85f)
            ), "desired-path"
        )
    }

    // ── Waypoint marker layer ──
    if (style.getLayer("waypoint-markers") == null) {
        val iconExpr = org.maplibre.android.style.expressions.Expression.match(
            org.maplibre.android.style.expressions.Expression.get("wp-type"),
            org.maplibre.android.style.expressions.Expression.literal("pin-intermediate"),
            org.maplibre.android.style.expressions.Expression.stop("origin", org.maplibre.android.style.expressions.Expression.literal("pin-origin")),
            org.maplibre.android.style.expressions.Expression.stop("destination", org.maplibre.android.style.expressions.Expression.literal("pin-destination")),
        )
        val sizeExpr = org.maplibre.android.style.expressions.Expression.switchCase(
            org.maplibre.android.style.expressions.Expression.get("selected"),
            org.maplibre.android.style.expressions.Expression.literal(1.3f),
            org.maplibre.android.style.expressions.Expression.literal(1.0f)
        )
        style.addLayer(org.maplibre.android.style.layers.SymbolLayer("waypoint-markers", "waypoint-markers").withProperties(
            PropertyFactory.iconImage(iconExpr),
            PropertyFactory.iconSize(sizeExpr),
            PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
    }

    // ── Fuel station icon (small fuel pump) ──
    if (style.getImage("fuel-pin") == null) {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val size = (28 * density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val s = size.toFloat()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#333333")
            this.style = android.graphics.Paint.Style.FILL
        }
        // Pump body
        val body = android.graphics.RectF(s * 0.18f, s * 0.22f, s * 0.58f, s * 0.82f)
        canvas.drawRoundRect(body, 2f * density, 2f * density, paint)
        // Nozzle arm
        val arm = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#333333")
            strokeWidth = 2.5f * density
            strokeCap = android.graphics.Paint.Cap.ROUND
            this.style = android.graphics.Paint.Style.STROKE
        }
        canvas.drawLine(s * 0.58f, s * 0.32f, s * 0.75f, s * 0.32f, arm)
        canvas.drawLine(s * 0.75f, s * 0.32f, s * 0.75f, s * 0.55f, arm)
        canvas.drawLine(s * 0.75f, s * 0.55f, s * 0.65f, s * 0.55f, arm)
        // Fuel gauge window on body
        val window = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRect(s * 0.25f, s * 0.32f, s * 0.51f, s * 0.50f, window)
        // Base
        canvas.drawRect(s * 0.12f, s * 0.82f, s * 0.64f, s * 0.88f, paint)
        style.addImage("fuel-pin", bmp)
    }

    // ── Debug POIs layer (circles with name) ──
    if (style.getLayer("debug-pois") == null) {
        style.addLayer(org.maplibre.android.style.layers.CircleLayer("debug-pois", "debug-pois").withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor("#e03030"),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#ffffff"),
        ))
    }
    if (style.getLayer("debug-pois-labels") == null) {
        style.addLayer(org.maplibre.android.style.layers.SymbolLayer("debug-pois-labels", "debug-pois").withProperties(
            PropertyFactory.textField(
                org.maplibre.android.style.expressions.Expression.concat(
                    org.maplibre.android.style.expressions.Expression.get("name"),
                    org.maplibre.android.style.expressions.Expression.literal(" ("),
                    org.maplibre.android.style.expressions.Expression.get("category"),
                    org.maplibre.android.style.expressions.Expression.literal(")")
                )
            ),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(9f),
            PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
            PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor("#cc0000"),
            PropertyFactory.textHaloColor("#ffffff"),
            PropertyFactory.textHaloWidth(1f),
            PropertyFactory.textAllowOverlap(false),
        ))
    }

    // ── Fuel stations layer (general map POI, same minzoom as the vector-tile POI layers) ──
    if (style.getLayer("fuel-stations") == null) {
        style.addLayer(org.maplibre.android.style.layers.SymbolLayer("fuel-stations", "fuel-stations").withProperties(
            PropertyFactory.iconImage("fuel-pin"),
            PropertyFactory.iconSize(1f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(10f),
            PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
            PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor("#333333"),
            PropertyFactory.textHaloColor("#ffffff"),
            PropertyFactory.textHaloWidth(1f),
            PropertyFactory.textOptional(true),
        ).apply { setMinZoom(14f) })
    }

    // User arrow is now drawn as a Compose overlay (not a MapLibre layer)
}
