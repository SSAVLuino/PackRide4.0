package biz.cesena.packride4.ui.home

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import biz.cesena.packride4.debug.DebugLog
import biz.cesena.packride4.routing.GeocodingResult
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var initialZoomDone by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSearchSheet by remember { mutableStateOf(false) }

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

    LaunchedEffect(uiState.mapStyleJson) {
        mapInstance?.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
            addMapLayers(style)
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
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
    LaunchedEffect(uiState.route) {
        val map = mapInstance ?: return@LaunchedEffect
        val source = map.style?.getSourceAs<GeoJsonSource>("route")
        val route = uiState.route
        if (route == null) {
            source?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyArray()))
        } else {
            val line = org.maplibre.geojson.LineString.fromLngLats(
                route.points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
            )
            source?.setGeoJson(Feature.fromGeometry(line))
        }
    }

    // GPS dot + follow camera + initial zoom
    LaunchedEffect(uiState.lastKnownPosition, uiState.isFollowing) {
        val pos = uiState.lastKnownPosition ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        (map.style?.getSourceAs<GeoJsonSource>("user-location"))?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude))
        )
        if (!initialZoomDone) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.latitude, pos.longitude), 14.0))
            initialZoomDone = true
        } else if (uiState.isFollowing) {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(pos.latitude, pos.longitude)))
        }
    }

    // Height of the bottom overlay so the GPS FAB stays above it
    val bottomPanelDp = when {
        uiState.isNavigating -> 120.dp
        uiState.route != null -> 80.dp
        else -> 72.dp
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
                            val statusPx = (ctx.resources.displayMetrics.density * 56).toInt()
                            map.uiSettings.setCompassMargins(0, statusPx, 0, 0)
                            addMapLayers(style)
                        }
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 5.5))
                    }
                    mapViewRef = mapView
                }
            },
            onRelease = { it.onPause(); it.onStop(); it.onDestroy() },
            modifier = Modifier.fillMaxSize()
        )

        // ── Status bar scrim — makes time/signal readable over the map ───────
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Color.Black.copy(alpha = 0.40f))
                .align(Alignment.TopStart)
        )

        // ── WEB chip (top-right, below status bar) ───────────────────────────
        if (!uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 12.dp, top = 8.dp),
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

        // ── Offline maps warning (top-center, below status bar) ──────────────
        if (!uiState.hasOfflineMaps && uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp, start = 76.dp, end = 12.dp),
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

        // ── GPS follow FAB (bottom-right, above bottom panel) ────────────────
        FloatingActionButton(
            onClick = { viewModel.toggleFollow() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 16.dp, bottom = bottomPanelDp + 12.dp),
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

        // ── Bottom overlay (depends on state) ────────────────────────────────
        when {

            // STATE 1 — Navigating
            uiState.isNavigating && uiState.route != null -> {
                NavigationBottomPanel(
                    uiState = uiState,
                    onStop = { viewModel.stopNavigation() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }

            // STATE 2 — Route computed, not yet navigating
            uiState.route != null -> {
                RouteReadyPanel(
                    route = uiState.route!!,
                    destinationName = uiState.destinationName,
                    routeSaved = uiState.routeSaved,
                    onStart = { viewModel.startNavigation() },
                    onSave = { viewModel.saveRoute() },
                    onCancel = { viewModel.clearRoute() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }

            // STATE 3 — Idle: search bar
            else -> {
                SearchBar(
                    onClick = { showSearchSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 76.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }

        // ── Search bottom sheet ───────────────────────────────────────────────
        if (showSearchSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showSearchSheet = false
                    viewModel.clearSearch()
                },
                sheetState = sheetState,
            ) {
                SearchSheet(
                    query = uiState.searchQuery,
                    results = uiState.searchResults,
                    isLoading = uiState.isSearchLoading,
                    onQueryChange = { viewModel.onSearchQueryChange(it) },
                    onResultClick = { result ->
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showSearchSheet = false
                        }
                        viewModel.selectSearchResult(result)
                    },
                    onClose = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showSearchSheet = false
                            viewModel.clearSearch()
                        }
                    }
                )
            }
        }

        // ── GPS permission rationale ─────────────────────────────────────────
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

    }
}

// ── Bottom panel: idle search bar (pill) ──────────────────────────────────────

@Composable
private fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Dove vuoi andare?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Search bottom sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    query: String,
    results: List<GeocodingResult>,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onResultClick: (GeocodingResult) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Search field
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = { Text("Cerca indirizzo o luogo…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, "Cancella")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge
        )

        Spacer(Modifier.height(8.dp))

        // Loading
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        // Results
        LazyColumn {
            items(results) { result ->
                ListItem(
                    headlineContent = { Text(result.name, maxLines = 1) },
                    supportingContent = {
                        Text(result.address,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Place, null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onResultClick(result) }
                )
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Bottom panel: route ready (not yet navigating) ────────────────────────────

@Composable
private fun RouteReadyPanel(
    route: biz.cesena.packride4.routing.RouteResult,
    destinationName: String,
    routeSaved: Boolean,
    onStart: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showInstructions by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
        )
    ) {
        Column {
            // Summary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                // Toggle instructions list
                if (route.instructions.isNotEmpty()) {
                    IconButton(onClick = { showInstructions = !showInstructions }) {
                        Icon(
                            if (showInstructions) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Istruzioni",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Save button
                IconButton(onClick = onSave, enabled = !routeSaved) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = "Salva percorso",
                        tint = if (routeSaved) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onCancel) { Text("Cancella") }
                Button(onClick = onStart) {
                    Icon(Icons.Default.Navigation, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Avvia")
                }
            }

            // Instructions list (expandable)
            if (showInstructions && route.instructions.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    items(route.instructions) { instr ->
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(signToIcon(instr.sign), null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text(
                                instr.text.ifBlank { "Prosegui" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                formatDistance(instr.distanceMeters),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            } else {
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            }
        }
    }
}

// ── Bottom panel: active navigation (instruction + speed/distance/ETA) ────────

@Composable
private fun NavigationBottomPanel(
    uiState: HomeUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val route = uiState.route ?: return
    val instructions = route.instructions
    val currentInstruction = instructions.getOrNull(uiState.currentInstructionIndex)

    Column(modifier = modifier) {

        // Instruction banner
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = signToIcon(currentInstruction?.sign ?: 0),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentInstruction?.text?.takeIf { it.isNotBlank() }
                            ?: "Segui il percorso",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1
                    )
                    if ((currentInstruction?.distanceMeters ?: 0.0) > 0) {
                        Text(
                            text = "tra ${formatDistance(currentInstruction!!.distanceMeters)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Speed / distance remaining / ETA
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavStat(
                    value = "${uiState.speedKmh.roundToInt()}",
                    unit = "km/h",
                    tint = MaterialTheme.colorScheme.primary
                )
                NavStat(
                    value = formatDistance(route.distanceMeters),
                    unit = "rimasti"
                )
                NavStat(
                    value = formatDuration(route.timeMillis),
                    unit = "arrivo"
                )
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, "Stop navigazione",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NavStat(value: String, unit: String, tint: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint)
        Text(unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun signToIcon(sign: Int): ImageVector = when (sign) {
    -3, -2 -> Icons.Default.TurnLeft
    -1     -> Icons.Default.TurnSlightLeft
    1      -> Icons.Default.TurnSlightRight
    2, 3   -> Icons.Default.TurnRight
    4      -> Icons.Default.Flag
    else   -> Icons.Default.Straight
}

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
    if (style.getSource("user-location") == null) style.addSource(GeoJsonSource("user-location"))
    if (style.getLayer("user-location") == null) {
        style.addLayer(CircleLayer("user-location", "user-location").withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor("#1a73e8"),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#ffffff")
        ))
    }
    if (style.getSource("route") == null) style.addSource(GeoJsonSource("route"))
    if (style.getLayer("route") == null) {
        style.addLayerBelow(
            org.maplibre.android.style.layers.LineLayer("route", "route").withProperties(
                PropertyFactory.lineColor("#1a73e8"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.85f)
            ), "user-location"
        )
    }
}
