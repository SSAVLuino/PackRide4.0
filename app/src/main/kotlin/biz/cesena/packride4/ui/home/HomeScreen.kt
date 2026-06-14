package biz.cesena.packride4.ui.home

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.WifiOff
import biz.cesena.packride4.debug.DebugLog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.compose.material.icons.filled.GpsFixed
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import org.maplibre.geojson.Feature

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    // Keep a reference to the map to update the style when source changes
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var showDebugLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) viewModel.startLocationUpdates()
    }

    // Keep the screen on while this map screen is visible (useful for navigation)
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Update MapLibre style whenever source changes
    LaunchedEffect(uiState.mapStyleJson) {
        DebugLog.log("style update: hasOfflineMaps=${uiState.hasOfflineMaps} localhost=${uiState.mapStyleJson.contains("localhost")}")
        mapInstance?.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
            DebugLog.log("style update loaded: sources=${style.sources.size} layers=${style.layers.size}")
            addUserLocationLayer(style)
        }
    }

    // MapView needs lifecycle callbacks to start/stop GL rendering — Compose's AndroidView
    // doesn't forward these automatically, which leaves the map blank on some devices.
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mapView = mapViewRef
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> mapView?.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapView?.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (mapView != null) {
            mapView.onStart()
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onPause()
            mapView?.onStop()
        }
    }

    // Draw/clear the computed route line
    LaunchedEffect(uiState.route) {
        val map = mapInstance ?: return@LaunchedEffect
        val route = uiState.route
        val source = map.style?.getSourceAs<GeoJsonSource>("route")
        if (route == null) {
            source?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyArray()))
        } else {
            val line = org.maplibre.geojson.LineString.fromLngLats(
                route.points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
            )
            source?.setGeoJson(Feature.fromGeometry(line))
        }
    }

    // Update the GPS marker, and recenter the camera if "follow" mode is on
    LaunchedEffect(uiState.lastKnownPosition, uiState.isFollowing) {
        val pos = uiState.lastKnownPosition ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        val latLng = LatLng(pos.latitude, pos.longitude)

        (map.style?.getSourceAs<GeoJsonSource>("user-location"))?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude))
        )

        if (uiState.isFollowing) {
            map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                DebugLog.log("MapView factory: creating MapView")
                MapView(ctx).also { mapView ->
                    mapView.addOnDidFailLoadingMapListener { error ->
                        DebugLog.log("map load FAILED: $error")
                    }
                    mapView.getMapAsync { map ->
                        DebugLog.log("getMapAsync: map ready, hasOfflineMaps=${uiState.hasOfflineMaps} localhost=${uiState.mapStyleJson.contains("localhost")}")
                        mapInstance = map
                        map.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
                            DebugLog.log("style loaded OK: sources=${style.sources.size} layers=${style.layers.size}")
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                            val statusBarPx = (ctx.resources.displayMetrics.density * 56).toInt()
                            map.uiSettings.setCompassMargins(0, statusBarPx, 0, 0)
                            addUserLocationLayer(style)
                        }
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 5.5))
                    }
                    mapViewRef = mapView
                }
            },
            onRelease = { mapView ->
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Banner: nessuna mappa offline disponibile (mostrata solo se è stata richiesta una mappa offline)
        if (!uiState.hasOfflineMaps && uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WifiOff, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "Nessuna mappa offline — scarica una regione dal menu Mappe offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // Chip "WEB" visibile solo quando si usano tile online
        if (!uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 12.dp, top = 12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = "WEB",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Debug log viewer (no-adb diagnostics)
        FloatingActionButton(
            onClick = { showDebugLog = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 64.dp + 16.dp, bottom = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                Icons.Default.BugReport,
                contentDescription = "Log di debug",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Follow-GPS toggle
        FloatingActionButton(
            onClick = { viewModel.toggleFollow() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
            containerColor = if (uiState.isFollowing)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                if (uiState.isFollowing) Icons.Default.GpsFixed else Icons.Default.MyLocation,
                contentDescription = "Segui posizione GPS",
                tint = if (uiState.isFollowing)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Test routing: compute a route from GPS position to Lugano (only when graph is ready)
        if (uiState.isRoutingReady && uiState.lastKnownPosition != null) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (uiState.route == null) {
                        viewModel.computeTestRoute(46.0037, 8.9511) // Lugano
                    } else {
                        viewModel.clearRoute()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 64.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(if (uiState.route == null) "Test percorso → Lugano" else "Nascondi percorso")
            }
        }

        // Permission rationale
        if (!locationPermissions.allPermissionsGranted) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
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

        if (showDebugLog) {
            DebugLogDialog(onDismiss = { showDebugLog = false })
        }
    }
}

@Composable
private fun DebugLogDialog(onDismiss: () -> Unit) {
    val lines by DebugLog.lines.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log di debug") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                items(lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        },
        dismissButton = {
            TextButton(onClick = { DebugLog.clear() }) { Text("Pulisci") }
        }
    )
}

/** Adds (or re-adds, after a style change) a source/layer to draw the GPS position as a blue dot. */
private fun addUserLocationLayer(style: Style) {
    if (style.getSource("user-location") == null) {
        style.addSource(GeoJsonSource("user-location"))
    }
    if (style.getLayer("user-location") == null) {
        style.addLayer(
            CircleLayer("user-location", "user-location").withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor("#1a73e8"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#ffffff")
            )
        )
    }
    if (style.getSource("route") == null) {
        style.addSource(GeoJsonSource("route"))
    }
    if (style.getLayer("route") == null) {
        style.addLayerBelow(
            org.maplibre.android.style.layers.LineLayer("route", "route").withProperties(
                PropertyFactory.lineColor("#1a73e8"),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.8f)
            ),
            "user-location"
        )
    }
}
