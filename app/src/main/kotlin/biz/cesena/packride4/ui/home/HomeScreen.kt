package biz.cesena.packride4.ui.home

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.WifiOff
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

    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) viewModel.startLocationUpdates()
    }

    // Update MapLibre style whenever source changes
    LaunchedEffect(uiState.mapStyleJson) {
        mapInstance?.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
            addUserLocationLayer(style)
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
                MapView(ctx).also { mapView ->
                    mapView.getMapAsync { map ->
                        mapInstance = map
                        map.setStyle(Style.Builder().fromJson(uiState.mapStyleJson)) { style ->
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                            addUserLocationLayer(style)
                        }
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 5.5))
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // GPS position chip
        uiState.lastKnownPosition?.let { pos ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.MyLocation, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("%.5f, %.5f".format(pos.latitude, pos.longitude),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Banner: nessuna mappa offline disponibile (mostrata solo se è stata richiesta una mappa offline)
        if (!uiState.hasOfflineMaps && uiState.mapStyleJson.contains("localhost")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
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

        // Follow-GPS toggle
        FloatingActionButton(
            onClick = { viewModel.toggleFollow() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
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

        // Permission rationale
        if (!locationPermissions.allPermissionsGranted) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
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
}
