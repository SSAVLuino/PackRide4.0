package biz.cesena.packride4.ui.home

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Home screen: full-screen MapLibre map with GPS position and a FAB to start navigation.
 *
 * Offline tiles are served from a local MBTiles file placed in:
 *   app/src/main/assets/maps/<region>.mbtiles
 * The style JSON references the tile source via the `mbtiles://` scheme handled by MapLibre.
 * When no offline tiles are available it falls back to OSM raster tiles.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    onNavigateToRouting: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) {
            viewModel.startLocationUpdates()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // MapLibre map view
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { mapView ->
                    mapView.getMapAsync { map ->
                        // Use OSM raster tiles as default (replace with local MBTiles style when available)
                        val styleJson = """
                            {
                              "version": 8,
                              "sources": {
                                "osm": {
                                  "type": "raster",
                                  "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                                  "tileSize": 256,
                                  "attribution": "© OpenStreetMap contributors"
                                }
                              },
                              "layers": [{
                                "id": "osm-tiles",
                                "type": "raster",
                                "source": "osm"
                              }]
                            }
                        """.trimIndent()

                        map.setStyle(Style.Builder().fromJson(styleJson)) {
                            // Style loaded — enable location component if permission granted
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                        }

                        // Zoom to Italy as default view
                        map.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 5.5)
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // GPS position indicator
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
                        Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "%.5f, %.5f".format(pos.latitude, pos.longitude),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Offline notice when no tiles are downloaded
        if (!uiState.hasOfflineMaps) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp
            ) {
                Text(
                    text = "Nessuna mappa offline — scarica una regione dal menu Mappe",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Permission rationale
        if (!locationPermissions.allPermissionsGranted) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Posizione GPS necessaria",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "PackRide ha bisogno della posizione per la navigazione e la condivisione in tempo reale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                        Text("Concedi permesso")
                    }
                }
            }
        }

        // FAB — start navigation
        FloatingActionButton(
            onClick = onNavigateToRouting,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                Icons.Default.Navigation,
                contentDescription = "Avvia navigazione",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
