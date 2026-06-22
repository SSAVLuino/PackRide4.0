package biz.cesena.packride4.ui.home

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

private val SIDEBAR_WIDTH = 64.dp

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onFullscreenOverlayChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.showRoutePlanner) {
        onFullscreenOverlayChanged(uiState.showRoutePlanner)
    }

    LaunchedEffect(uiState.routeError) {
        val err = uiState.routeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err, duration = SnackbarDuration.Short)
    }

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var initialZoomDone by remember { mutableStateOf(false) }

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
    LaunchedEffect(mapInstance, uiState.route) {
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
        }
    }

    // Fuel stations along route
    LaunchedEffect(mapInstance, uiState.fuelStationsAlongRoute) {
        val map = mapInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val fuelSource = style.getSourceAs<GeoJsonSource>("fuel-stations")
        val stations = uiState.fuelStationsAlongRoute
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

    // Map click/long-click listeners for route editing
    DisposableEffect(mapInstance, uiState.isEditingRoute) {
        val map = mapInstance ?: return@DisposableEffect onDispose {}
        if (!uiState.isEditingRoute) return@DisposableEffect onDispose {}

        val clickListener = org.maplibre.android.maps.MapLibreMap.OnMapClickListener { latLng ->
            viewModel.handleMapTap(latLng.latitude, latLng.longitude, map.cameraPosition.zoom)
        }
        val longClickListener = org.maplibre.android.maps.MapLibreMap.OnMapLongClickListener { latLng ->
            viewModel.handleMapLongPress(latLng.latitude, latLng.longitude, map.cameraPosition.zoom)
        }
        map.addOnMapClickListener(clickListener)
        map.addOnMapLongClickListener(longClickListener)

        onDispose {
            map.removeOnMapClickListener(clickListener)
            map.removeOnMapLongClickListener(longClickListener)
        }
    }

    // GPS dot + follow camera + initial zoom
    LaunchedEffect(uiState.lastKnownPosition, uiState.isFollowing, uiState.isNavigating) {
        val pos = uiState.lastKnownPosition ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        val feature = Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude))
        if (pos.hasBearing) feature.addNumberProperty("bearing", pos.bearing.toDouble())
        (map.style?.getSourceAs<GeoJsonSource>("user-location"))?.setGeoJson(feature)
        if (!initialZoomDone) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.latitude, pos.longitude), 14.0))
            initialZoomDone = true
        } else if (uiState.isFollowing && uiState.isNavigating) {
            val bearing = if (pos.hasBearing) pos.bearing.toDouble() else map.cameraPosition.bearing
            map.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(pos.latitude, pos.longitude))
                    .zoom(17.0)
                    .tilt(45.0)
                    .bearing(bearing)
                    .build()
            ))
        } else if (uiState.isFollowing) {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(pos.latitude, pos.longitude)))
        }
    }

    // ── Usable area padding (accounts for system bars, cutout, sidebar) ──
    val safeTop = maxOf(
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    )
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeEnd = WindowInsets.navigationBars.asPaddingValues().calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    val contentStart = SIDEBAR_WIDTH + 12.dp
    val contentEnd = 16.dp + safeEnd
    val contentTop = safeTop + 8.dp

    // Height of the bottom overlay so the GPS FAB stays above it
    val bottomPanelDp = when {
        uiState.isNavigating -> 80.dp
        uiState.route != null -> 80.dp
        else -> 64.dp
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Map (full screen behind everything) ──────────────────────────────
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { mapView ->
                    mapView.addOnDidFailLoadingMapListener { e -> DebugLog.log("map FAILED: $e") }
                    mapView.getMapAsync { map ->
                        mapInstance = map
                        map.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isTiltGesturesEnabled = true
                            val statusPx = (ctx.resources.displayMetrics.density * 56).toInt()
                            map.uiSettings.setCompassMargins(0, statusPx, 0, 0)
                            addMapLayers(style)
                        }
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

        // ── Navigation stats (top, 3 blocks) ───────────────────────────────
        if (uiState.isNavigating && uiState.route != null) {
            NavigationStatsBar(
                uiState = uiState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = contentTop, start = contentStart, end = contentEnd)
            )
        }

        // ── GPS FAB + speed limit (right side, stacked) ─────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = contentEnd, bottom = bottomPanelDp + safeBottom + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.isNavigating && uiState.route != null) {
                val currentInstruction = uiState.route!!.instructions.getOrNull(uiState.currentInstructionIndex)
                val limit = currentInstruction?.speedLimitKmh ?: 0
                if (limit > 0) {
                    SpeedLimitSign(limit = limit)
                }
            }
            if (biz.cesena.packride4.BuildConfig.DEBUG) {
                SmallFloatingActionButton(
                    onClick = { viewModel.debugSearchNearbyPois() },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Icon(Icons.Default.Place, "Debug POI",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp))
                }
            }
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
        }

        // ── Bottom overlay (depends on state) ────────────────────────────────
        when {

            // STATE 1 — Navigating: only instruction banner at bottom
            uiState.isNavigating && uiState.route != null -> {
                NavigationInstructionBanner(
                    uiState = uiState,
                    onStop = { viewModel.stopNavigation() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = contentStart, end = contentEnd, bottom = safeBottom)
                )
            }

            // STATE 2 — Route computed, not yet navigating
            uiState.route != null -> {
                RouteReadyPanel(
                    route = uiState.route!!,
                    destinationName = uiState.destinationName,
                    onStart = { viewModel.startNavigation() },
                    onCancel = { viewModel.clearRoute() },
                    onRecalculate = { engine -> viewModel.recalculateWithEngine(engine) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = contentStart, end = contentEnd, bottom = safeBottom)
                )
            }

            // STATE 3 — Idle: search bar
            else -> {
                SearchBar(
                    onClick = { viewModel.openRoutePlanner() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = contentStart, end = contentEnd, bottom = safeBottom + 12.dp)
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
                    onSelectResult = { viewModel.selectPlannerResult(it) },
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
                .padding(bottom = bottomPanelDp + safeBottom + 8.dp)
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

    }
}

// ── Bottom panel: idle "Dove andiamo?" button ─────────────────────────────────

@Composable
private fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Dove andiamo?",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Bottom panel: route ready (not yet navigating) ────────────────────────────

@Composable
private fun RouteReadyPanel(
    route: biz.cesena.packride4.routing.RouteResult,
    destinationName: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRecalculate: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (destinationName.isNotBlank()) {
                        Text(destinationName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1)
                    }
                    Text(
                        "${formatDistance(route.distanceMeters)}  ·  ${formatDuration(route.timeMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onCancel) { Text("Cancella") }
                Button(onClick = onStart) {
                    Icon(Icons.Default.Navigation, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Avvia")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onRecalculate("local") }, modifier = Modifier.weight(1f)) {
                    Text("Locale", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { onRecalculate("tomtom") }, modifier = Modifier.weight(1f)) {
                    Text("TomTom", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { onRecalculate("osrm") }, modifier = Modifier.weight(1f)) {
                    Text("OSRM", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ── Navigation instruction banner (bottom, during navigation) ────────────────

@Composable
private fun NavigationInstructionBanner(
    uiState: HomeUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val route = uiState.route ?: return
    val currentInstruction = route.instructions.getOrNull(uiState.currentInstructionIndex)

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Icon(
                    painter = painterResource(maneuverIcon(currentInstruction?.sign ?: 0, currentInstruction?.modifier ?: "", currentInstruction?.exitNumber ?: 0, currentInstruction?.turnAngle ?: Double.NaN)),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val exitNum = currentInstruction?.exitNumber ?: 0
                if (exitNum > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.size(16.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(exitNum.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 9.sp)
                        }
                    }
                }
            }
            if (uiState.distanceToNextManeuver > 0) {
                Text(
                    text = formatDistance(uiState.distanceToNextManeuver),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = currentInstruction?.text?.takeIf { it.isNotBlank() } ?: "Segui il percorso",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Close, "Stop navigazione",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Navigation stats overlay (left side, on the map) ─────────────────────────

data class NavStatItem(val value: String, val unit: String, val tint: Color = Color.Unspecified)

@Composable
private fun NavigationStatsBar(
    uiState: HomeUiState,
    modifier: Modifier = Modifier
) {
    val route = uiState.route ?: return
    val currentInstruction = route.instructions.getOrNull(uiState.currentInstructionIndex)
    val overSpeed = currentInstruction?.speedLimitKmh?.let { it > 0 && uiState.speedKmh > it } == true

    val stats = listOf(
        NavStatItem(
            value = "${uiState.speedKmh.roundToInt()}",
            unit = "km/h",
            tint = if (overSpeed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ),
        NavStatItem(value = formatDistance(route.distanceMeters), unit = "rimasti"),
        NavStatItem(value = formatDuration(route.timeMillis), unit = "arrivo"),
    )

    Column(
        modifier = modifier.width(IntrinsicSize.Min),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        stats.forEach { stat ->
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stat.value,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (stat.tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else stat.tint,
                        maxLines = 1)
                    Text(stat.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SpeedLimitSign(limit: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 4.dp,
        border = BorderStroke(3.dp, Color.Red)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "$limit",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
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

// ── Map layer setup ──────────────────────────────────────────────────────────

private fun addMapLayers(style: Style) {
    // ── User bearing arrow icon ──
    if (style.getImage("user-bearing-arrow") == null) {
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1a73e8")
            this.style = android.graphics.Paint.Style.FILL
        }
        val path = android.graphics.Path().apply {
            moveTo(size / 2f, 0f)
            lineTo(size * 0.8f, size * 0.7f)
            lineTo(size / 2f, size * 0.5f)
            lineTo(size * 0.2f, size * 0.7f)
            close()
        }
        canvas.drawPath(path, paint)
        style.addImage("user-bearing-arrow", bmp)
    }

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
                PropertyFactory.lineColor("#1a73e8"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.85f)
            ), "desired-path"
        )
    }

    // ── User location layers ──
    if (style.getLayer("user-bearing") == null) {
        style.addLayer(org.maplibre.android.style.layers.SymbolLayer("user-bearing", "user-location").withProperties(
            PropertyFactory.iconImage("user-bearing-arrow"),
            PropertyFactory.iconSize(1f),
            PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
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

    // ── Fuel station icon (orange circle with white "F") ──
    if (style.getImage("fuel-pin") == null) {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val size = (36 * density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#c07020")
            this.style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, border)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#f5a623")
            this.style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f * density, bg)
        val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 18f * density
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("F", size / 2f, size / 2f + 7f * density, txt)
        style.addImage("fuel-pin", bmp)
    }

    // ── Fuel stations layer ──
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
        ))
    }
}
