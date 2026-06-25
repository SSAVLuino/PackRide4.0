package biz.cesena.packride4.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.download.AVAILABLE_REGIONS
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.SavedRoute
import biz.cesena.packride4.data.local.SavedRouteDao
import biz.cesena.packride4.data.prefs.RouteEventBus
import biz.cesena.packride4.data.prefs.UserPreferences
import biz.cesena.packride4.debug.DebugLog
import biz.cesena.packride4.map.MBTilesServer
import biz.cesena.packride4.map.ShortbreadStyle
import biz.cesena.packride4.navigation.NavigationVoiceService
import biz.cesena.packride4.routing.GeocodingResult
import biz.cesena.packride4.routing.OfflineGeocodingService
import biz.cesena.packride4.routing.GeocodingService
import biz.cesena.packride4.routing.OnlineRoutingService
import biz.cesena.packride4.routing.RouteResult
import biz.cesena.packride4.routing.RoutingManager
import com.google.android.gms.location.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class GpsPosition(val latitude: Double, val longitude: Double, val accuracy: Float, val bearing: Float = 0f, val hasBearing: Boolean = false, val altitude: Double = 0.0, val speed: Float = 0f)

data class RouteWaypoint(
    val label: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val isGps: Boolean = false,
    val isSet: Boolean = false,
)

data class HomeUiState(
    val lastKnownPosition: GpsPosition? = null,
    val hasOfflineMaps: Boolean = false,
    val isTracking: Boolean = false,
    val isFollowing: Boolean = false,
    val mapStyleJson: String = ShortbreadStyle.online,
    val isRoutingReady: Boolean = false,
    val route: RouteResult? = null,
    // Navigation state
    val isNavigating: Boolean = false,
    val currentInstructionIndex: Int = 0,
    val distanceToNextManeuver: Double = 0.0,
    val remainingDistance: Double = 0.0,
    val remainingTime: Long = 0L,
    val speedKmh: Float = 0f,
    // Route planner state
    val waypoints: List<RouteWaypoint> = emptyList(),
    val showRoutePlanner: Boolean = false,
    val plannerSearchQuery: String = "",
    val plannerSearchResults: List<GeocodingResult> = emptyList(),
    val plannerSearchLoading: Boolean = false,
    val plannerEditingIndex: Int = -1,
    // Map editing state (Phase 2)
    val isEditingRoute: Boolean = false,
    val selectedWaypointIndex: Int = -1,
    // Destination info (for saved routes)
    val destinationName: String = "",
    // ID of the auto-saved route entry (so delete events can clear the active route)
    val savedRouteId: Long? = null,
    // Routing error feedback
    val routeError: String? = null,
    val fuelStationsAlongRoute: List<OfflineGeocodingService.PoiResult> = emptyList(),
    val debugPois: List<OfflineGeocodingService.PoiResult> = emptyList(),
    val isRouteCalculating: Boolean = false,
    // Layout redesign state
    val altitudeMeters: Double = 0.0,
    val mapOrientationNorthUp: Boolean = true,
    val showInfoFullscreen: Boolean = false,
    val showMenu: Boolean = false,
    val menuSubScreen: String? = null,
    val departureTimeMillis: Long = 0L,
    val distanceTraveled: Double = 0.0,
    val widgetLeftIdle: String = "altitude",
    val widgetRightIdle: String = "time",
    val widgetLeftNav: String = "km_remaining",
    val widgetRightNav: String = "altitude",
    val selectingWidgetSide: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val savedRouteDao: SavedRouteDao,
    private val routingManager: RoutingManager,
    private val onlineRoutingService: OnlineRoutingService,
    private val geocodingService: GeocodingService,
    private val userPreferences: UserPreferences,
    private val routeEventBus: RouteEventBus,
    private val voiceService: NavigationVoiceService,
    private val offlineGeocodingService: OfflineGeocodingService,
) : ViewModel() {

    val savedPosition: Pair<Double, Double>? = userPreferences.getLastPosition()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val mbTilesServer = MBTilesServer(port = 8787)
    private var searchJob: Job? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 3_000L
    ).setMinUpdateDistanceMeters(5f).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _uiState.update { it.copy(
                    lastKnownPosition = GpsPosition(loc.latitude, loc.longitude, loc.accuracy, loc.bearing, loc.hasBearing(), loc.altitude, loc.speed),
                    speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else it.speedKmh,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else it.altitudeMeters,
                )}
                userPreferences.saveLastPosition(loc.latitude, loc.longitude)
                if (_uiState.value.isNavigating) advanceNavigation(loc.latitude, loc.longitude)
            }
        }
    }

    init {
        _uiState.update { it.copy(
            widgetLeftIdle = userPreferences.getWidgetSelection("left", false),
            widgetRightIdle = userPreferences.getWidgetSelection("right", false),
            widgetLeftNav = userPreferences.getWidgetSelection("left", true),
            widgetRightNav = userPreferences.getWidgetSelection("right", true),
        )}
        startMBTilesServer()
        registerMbtFilesInDb()
        observeMapSource()
        viewModelScope.launch {
            routeEventBus.routeDeleted.collect { deletedId ->
                if (_uiState.value.savedRouteId == deletedId) clearRoute()
            }
        }
        viewModelScope.launch {
            routeEventBus.loadRoute.collect { event ->
                DebugLog.log("HomeVM: received loadRoute event id=${event.id} name=${event.name}")
                val saved = savedRouteDao.getById(event.id.toInt())
                DebugLog.log("HomeVM: getById result=${saved != null}")
                if (saved != null) {
                    val points = SavedRoute.deserializePoints(saved.pointsJson)
                    val instructions = SavedRoute.deserializeInstructions(saved.instructionsJson)
                    val result = biz.cesena.packride4.routing.RouteResult(
                        points = points,
                        instructions = instructions,
                        distanceMeters = saved.distanceMeters,
                        timeMillis = saved.durationMillis,
                    )
                    val pos = _uiState.value.lastKnownPosition
                    val origin = if (pos != null) {
                        RouteWaypoint("Posizione GPS", pos.latitude, pos.longitude, isGps = true, isSet = true)
                    } else {
                        RouteWaypoint("Posizione GPS", isGps = true, isSet = false)
                    }
                    _uiState.update { it.copy(
                        route = result,
                        destinationName = saved.name,
                        savedRouteId = saved.id.toLong(),
                        isEditingRoute = true,
                        selectedWaypointIndex = -1,
                        showRoutePlanner = false,
                        waypoints = listOf(
                            origin,
                            RouteWaypoint(saved.name, saved.destinationLat, saved.destinationLon, isSet = true),
                        ),
                    )}
                }
            }
        }
        viewModelScope.launch {
            routingManager.isReady.collect { ready ->
                _uiState.update { it.copy(isRoutingReady = ready) }
            }
        }
        viewModelScope.launch {
            val routingDir = File(context.filesDir, "routing")
            for (entry in AVAILABLE_REGIONS) {
                val graphDir = File(routingDir, "graph-${entry.id}")
                if (graphDir.exists() && graphDir.isDirectory) {
                    routingManager.loadPrebuiltGraph(graphDir, entry.id)
                }
            }
        }
    }

    // ── Route planner ──────────────────────────────────────────────────────

    fun openRoutePlanner() {
        val pos = _uiState.value.lastKnownPosition
        val origin = if (pos != null) {
            RouteWaypoint("Posizione GPS", pos.latitude, pos.longitude, isGps = true, isSet = true)
        } else {
            RouteWaypoint("Posizione GPS", isGps = true, isSet = false)
        }
        _uiState.update { it.copy(
            showRoutePlanner = true,
            waypoints = listOf(origin, RouteWaypoint()),
            plannerEditingIndex = -1,
            plannerSearchQuery = "",
            plannerSearchResults = emptyList(),
        )}
    }

    fun closeRoutePlanner() {
        searchJob?.cancel()
        _uiState.update { it.copy(
            showRoutePlanner = false,
            plannerEditingIndex = -1,
            plannerSearchQuery = "",
            plannerSearchResults = emptyList(),
        )}
    }

    fun addWaypoint() {
        _uiState.update { state ->
            val wps = state.waypoints.toMutableList()
            wps.add(wps.size - 1, RouteWaypoint())
            state.copy(waypoints = wps)
        }
    }

    fun removeWaypoint(index: Int) {
        _uiState.update { state ->
            if (state.waypoints.size <= 2) return@update state
            val wps = state.waypoints.toMutableList()
            wps.removeAt(index)
            state.copy(
                waypoints = wps,
                plannerEditingIndex = if (state.plannerEditingIndex == index) -1 else state.plannerEditingIndex,
            )
        }
    }

    fun startEditingWaypoint(index: Int) {
        searchJob?.cancel()
        _uiState.update { it.copy(
            plannerEditingIndex = index,
            plannerSearchQuery = "",
            plannerSearchResults = emptyList(),
            plannerSearchLoading = false,
        )}
    }

    fun onPlannerSearchChange(query: String) {
        _uiState.update { it.copy(plannerSearchQuery = query) }
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.update { it.copy(plannerSearchResults = emptyList(), plannerSearchLoading = false) }
            return
        }
        searchJob?.cancel()
        _uiState.update { it.copy(plannerSearchLoading = true) }
        searchJob = viewModelScope.launch {
            delay(800)
            try {
                val pos = _uiState.value.lastKnownPosition
                DebugLog.log("search: querying \"$query\" pos=${pos?.latitude},${pos?.longitude}")
                val results = geocodingService.search(query, pos?.latitude ?: 0.0, pos?.longitude ?: 0.0)
                DebugLog.log("search: ${results.size} results for \"$query\"")
                _uiState.update { it.copy(plannerSearchResults = results, plannerSearchLoading = false) }
            } catch (e: Exception) {
                DebugLog.log("search error: ${e::class.simpleName}: ${e.message}")
                _uiState.update { it.copy(plannerSearchResults = emptyList(), plannerSearchLoading = false) }
            }
        }
    }

    fun selectPlannerResult(result: GeocodingResult) {
        val idx = _uiState.value.plannerEditingIndex
        if (idx < 0) return
        _uiState.update { state ->
            val wps = state.waypoints.toMutableList()
            wps[idx] = RouteWaypoint(result.name, result.lat, result.lon, isGps = false, isSet = true)
            state.copy(
                waypoints = wps,
                plannerEditingIndex = -1,
                plannerSearchQuery = "",
                plannerSearchResults = emptyList(),
            )
        }
    }

    fun updateWaypointPosition(index: Int, lat: Double, lon: Double) {
        _uiState.update { state ->
            val wps = state.waypoints.toMutableList()
            val old = wps[index]
            wps[index] = old.copy(lat = lat, lon = lon, isSet = true, isGps = false,
                label = if (old.isGps) "Posizione personalizzata" else old.label)
            state.copy(waypoints = wps)
        }
    }

    fun computeRouteFromWaypoints() {
        val state = _uiState.value
        val setWaypoints = state.waypoints.filter { it.isSet }
        if (setWaypoints.size < 2) {
            _uiState.update { it.copy(routeError = "Inserisci almeno partenza e destinazione") }
            return
        }

        // Update origin from GPS if it's still GPS-tagged
        val wps = state.waypoints.toMutableList()
        val origin = wps[0]
        if (origin.isGps) {
            val pos = state.lastKnownPosition
            if (pos != null) {
                wps[0] = origin.copy(lat = pos.latitude, lon = pos.longitude, isSet = true)
            } else {
                _uiState.update { it.copy(routeError = "Posizione GPS non disponibile") }
                return
            }
        }

        val points = wps.filter { it.isSet }.map { it.lat to it.lon }
        val destName = wps.last().label.ifBlank { "Percorso" }

        _uiState.update { it.copy(
            isRouteCalculating = true,
            routeError = null,
            showRoutePlanner = false,
            destinationName = destName,
            waypoints = wps,
        )}

        viewModelScope.launch {
            val first = points.first()
            val last = points.last()
            val result = if (routingManager.canRouteLocally(first.first, first.second, last.first, last.second, AVAILABLE_REGIONS)) {
                DebugLog.log("routing: trying local GraphHopper with ${points.size} waypoints")
                val local = routingManager.route(points)
                if (local != null) {
                    DebugLog.log("routing: local OK")
                    local
                } else {
                    DebugLog.log("routing: local failed, falling back to online")
                    onlineRoutingService.routeMulti(points)
                }
            } else {
                DebugLog.log("routing: online with ${points.size} waypoints")
                onlineRoutingService.routeMulti(points)
            }
            if (result != null) {
                val savedId = savedRouteDao.insert(SavedRoute(
                    name = destName,
                    destinationLat = last.first,
                    destinationLon = last.second,
                    distanceMeters = result.distanceMeters,
                    durationMillis = result.timeMillis,
                    pointsJson = SavedRoute.serializePoints(result.points),
                    instructionsJson = SavedRoute.serializeInstructions(result.instructions),
                ))
                val fuel = offlineGeocodingService.findFuelAlongRoute(result.points)
                _uiState.update { it.copy(
                    route = result,
                    isRouteCalculating = false,
                    routeError = null,
                    savedRouteId = savedId,
                    isEditingRoute = true,
                    selectedWaypointIndex = -1,
                    fuelStationsAlongRoute = fuel,
                )}
            } else {
                _uiState.update { it.copy(
                    isRouteCalculating = false,
                    routeError = "Impossibile calcolare il percorso",
                )}
            }
        }
    }

    // ── Map route editing (Phase 2) ─────────────────────────────────────────

    fun removeSelectedWaypoint() {
        val state = _uiState.value
        val idx = state.selectedWaypointIndex
        if (idx <= 0 || idx >= state.waypoints.size - 1) return
        val wps = state.waypoints.toMutableList()
        wps.removeAt(idx)
        _uiState.update { it.copy(waypoints = wps, selectedWaypointIndex = -1) }
        recalculateRoute()
    }

    fun selectWaypointOnMap(index: Int) {
        _uiState.update { it.copy(selectedWaypointIndex = if (it.selectedWaypointIndex == index) -1 else index) }
    }

    fun moveSelectedWaypoint(lat: Double, lon: Double) {
        val state = _uiState.value
        val idx = state.selectedWaypointIndex
        if (idx < 0 || idx >= state.waypoints.size) return
        val wps = state.waypoints.toMutableList()
        val old = wps[idx]
        wps[idx] = old.copy(lat = lat, lon = lon, isSet = true, isGps = false,
            label = if (old.label.isBlank() || old.isGps) "Punto sulla mappa" else old.label)
        _uiState.update { it.copy(waypoints = wps, selectedWaypointIndex = -1) }
        recalculateRoute()
    }

    fun addWaypointFromLongPress(lat: Double, lon: Double) {
        val state = _uiState.value
        val route = state.route ?: return
        val wps = state.waypoints.toMutableList()

        // Find which segment of waypoints this point is closest to
        val setWps = wps.filter { it.isSet }
        if (setWps.size < 2) return

        var bestSegment = 0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until setWps.size - 1) {
            val midLat = (setWps[i].lat + setWps[i + 1].lat) / 2
            val midLon = (setWps[i].lon + setWps[i + 1].lon) / 2
            val d = haversineMeters(lat, lon, midLat, midLon)
            if (d < bestDist) {
                bestDist = d
                bestSegment = i
            }
        }

        // Find actual index in the full waypoints list for insertion
        var count = -1
        var insertIdx = wps.size - 1
        for (i in wps.indices) {
            if (wps[i].isSet) count++
            if (count == bestSegment) {
                insertIdx = i + 1
                break
            }
        }

        val newWp = RouteWaypoint("Tappa sulla mappa", lat, lon, isGps = false, isSet = true)
        wps.add(insertIdx, newWp)
        _uiState.update { it.copy(waypoints = wps) }
        recalculateRoute()
    }

    fun handleMapTap(lat: Double, lon: Double, zoom: Double = 14.0): Boolean {
        val state = _uiState.value
        if (!state.isEditingRoute || state.route == null) return false
        val tapRadius = 500.0 / Math.pow(2.0, (zoom - 10.0).coerceAtLeast(0.0))

        // Check if tapped near a waypoint marker
        for ((i, wp) in state.waypoints.withIndex()) {
            if (!wp.isSet) continue
            if (haversineMeters(lat, lon, wp.lat, wp.lon) < tapRadius) {
                selectWaypointOnMap(i)
                return true
            }
        }

        // If a waypoint is selected, move it here
        if (state.selectedWaypointIndex >= 0) {
            moveSelectedWaypoint(lat, lon)
            return true
        }

        return false
    }

    fun handleMapLongPress(lat: Double, lon: Double, zoom: Double = 14.0): Boolean {
        val state = _uiState.value
        if (!state.isEditingRoute || state.route == null) return false

        // Check if long press is near the route polyline
        val route = state.route ?: return false
        val points = route.points
        if (points.size < 2) return false

        var minDist = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val (aLat, aLon) = points[i]
            val (bLat, bLon) = points[i + 1]
            val (dist, _) = pointToSegmentDistance(lat, lon, aLat, aLon, bLat, bLon)
            if (dist < minDist) minDist = dist
        }

        val longPressRadius = 200.0 / Math.pow(2.0, (zoom - 10.0).coerceAtLeast(0.0))
        if (minDist < longPressRadius) {
            addWaypointFromLongPress(lat, lon)
            return true
        }
        return false
    }

    private fun recalculateRoute() {
        val state = _uiState.value
        val setWps = state.waypoints.filter { it.isSet }
        if (setWps.size < 2) return

        val points = setWps.map { it.lat to it.lon }
        val destName = state.destinationName

        _uiState.update { it.copy(isRouteCalculating = true, routeError = null) }
        viewModelScope.launch {
            val first = points.first()
            val last = points.last()
            val result = if (routingManager.canRouteLocally(first.first, first.second, last.first, last.second, AVAILABLE_REGIONS)) {
                val local = routingManager.route(points)
                local ?: onlineRoutingService.routeMulti(points)
            } else {
                onlineRoutingService.routeMulti(points)
            }
            if (result != null) {
                val existingId = _uiState.value.savedRouteId
                val savedId = if (existingId != null) {
                    savedRouteDao.update(SavedRoute(
                        id = existingId.toInt(),
                        name = destName.ifBlank { "Percorso" },
                        destinationLat = last.first,
                        destinationLon = last.second,
                        distanceMeters = result.distanceMeters,
                        durationMillis = result.timeMillis,
                        pointsJson = SavedRoute.serializePoints(result.points),
                        instructionsJson = SavedRoute.serializeInstructions(result.instructions),
                    ))
                    existingId
                } else {
                    savedRouteDao.insert(SavedRoute(
                        name = destName.ifBlank { "Percorso" },
                        destinationLat = last.first,
                        destinationLon = last.second,
                        distanceMeters = result.distanceMeters,
                        durationMillis = result.timeMillis,
                        pointsJson = SavedRoute.serializePoints(result.points),
                        instructionsJson = SavedRoute.serializeInstructions(result.instructions),
                    ))
                }
                val fuel = offlineGeocodingService.findFuelAlongRoute(result.points)
                _uiState.update { it.copy(
                    route = result,
                    isRouteCalculating = false,
                    savedRouteId = savedId,
                    isEditingRoute = true,
                    selectedWaypointIndex = -1,
                    fuelStationsAlongRoute = fuel,
                )}
            } else {
                _uiState.update { it.copy(isRouteCalculating = false, routeError = "Impossibile ricalcolare il percorso") }
            }
        }
    }

    // ── Legacy routing (from saved routes) ──────────────────────────────────

    fun computeRoute(destLat: Double, destLon: Double) {
        val pos = _uiState.value.lastKnownPosition
        if (pos == null) {
            _uiState.update { it.copy(routeError = "Posizione GPS non disponibile") }
            return
        }
        _uiState.update { it.copy(
            waypoints = listOf(
                RouteWaypoint("Posizione GPS", pos.latitude, pos.longitude, isGps = true, isSet = true),
                RouteWaypoint(_uiState.value.destinationName, destLat, destLon, isSet = true),
            )
        )}
        computeRouteFromWaypoints()
    }

    fun recalculateWithEngine(engine: String) {
        val state = _uiState.value
        val setWps = state.waypoints.filter { it.isSet }
        if (setWps.size < 2) return
        val points = setWps.map { it.lat to it.lon }
        _uiState.update { it.copy(isRouteCalculating = true, routeError = null) }
        viewModelScope.launch {
            val result = when (engine) {
                "local" -> routingManager.route(points)
                "tomtom" -> onlineRoutingService.routeViaTomTomPublic(points)
                "osrm" -> onlineRoutingService.routeViaOsrmPublic(points)
                else -> null
            }
            if (result != null) {
                val existingId = _uiState.value.savedRouteId
                val destName = _uiState.value.destinationName.ifBlank { "Percorso" }
                val last = points.last()
                if (existingId != null) {
                    savedRouteDao.update(SavedRoute(
                        id = existingId.toInt(),
                        name = destName,
                        destinationLat = last.first,
                        destinationLon = last.second,
                        distanceMeters = result.distanceMeters,
                        durationMillis = result.timeMillis,
                        pointsJson = SavedRoute.serializePoints(result.points),
                        instructionsJson = SavedRoute.serializeInstructions(result.instructions),
                    ))
                }
                _uiState.update { it.copy(route = result, isRouteCalculating = false, isEditingRoute = true, selectedWaypointIndex = -1) }
            } else {
                _uiState.update { it.copy(isRouteCalculating = false, routeError = "Errore con $engine") }
            }
        }
    }

    fun debugSearchVisiblePois(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val pois = offlineGeocodingService.findPoisInBounds(minLat, minLon, maxLat, maxLon)
            DebugLog.log("debug POIs in view: ${pois.size}")
            _uiState.update { it.copy(debugPois = pois) }
        }
    }

    fun clearRoute() {
        _uiState.update { it.copy(
            route = null,
            isNavigating = false,
            currentInstructionIndex = 0,
            savedRouteId = null,
            isEditingRoute = false,
            selectedWaypointIndex = -1,
            fuelStationsAlongRoute = emptyList(),
        )}
    }

    fun startNavigation() {
        voiceService.init()
        voiceService.reset()
        lastMatchedSegment = 0
        _uiState.update { it.copy(isNavigating = true, isFollowing = true, currentInstructionIndex = 0, isEditingRoute = false, selectedWaypointIndex = -1, departureTimeMillis = System.currentTimeMillis(), distanceTraveled = 0.0) }
        voiceService.checkAnnouncement(0, "Partiamo", 0.0, 0f)
    }

    fun stopNavigation() {
        voiceService.shutdown()
        _uiState.update { it.copy(isNavigating = false, currentInstructionIndex = 0, route = null) }
    }

    // ── Navigation advancement ────────────────────────────────────────────

    private fun advanceNavigation(lat: Double, lon: Double) {
        val state = _uiState.value
        val route = state.route ?: return
        val instructions = route.instructions
        if (instructions.isEmpty()) return

        val idx = state.currentInstructionIndex

        val waypointDistances = mutableListOf<Double>()
        var cumulative = 0.0
        for (instr in instructions) {
            cumulative += instr.distanceMeters
            waypointDistances.add(cumulative)
        }

        val distanceAlongRoute = distanceAlongPolyline(lat, lon, route.points)

        var newIdx = idx
        while (newIdx < instructions.size - 1 && distanceAlongRoute >= waypointDistances[newIdx] + 30.0) {
            newIdx++
        }

        val lastPoint = route.points.lastOrNull()
        val distToEnd = if (lastPoint != null) haversineMeters(lat, lon, lastPoint.first, lastPoint.second) else Double.MAX_VALUE

        if (distToEnd < 30.0) {
            voiceService.checkAnnouncement(instructions.size, "Sei arrivato a destinazione", 0.0, state.speedKmh)
            voiceService.shutdown()
            _uiState.update { it.copy(isNavigating = false, currentInstructionIndex = 0, route = null) }
            return
        }

        // Distance remaining to the next maneuver point
        val distToNextManeuver = if (newIdx < waypointDistances.size)
            (waypointDistances[newIdx] - distanceAlongRoute).coerceAtLeast(0.0) else 0.0

        // Voice announcement for upcoming instruction
        if (newIdx < instructions.size) {
            val nextInstr = instructions[newIdx]
            voiceService.checkAnnouncement(newIdx, nextInstr.text, distToNextManeuver, state.speedKmh)
        }

        // Calculate remaining distance from total (never modify route.distanceMeters)
        val totalDist = waypointDistances.lastOrNull() ?: route.distanceMeters
        val remainingDist = (totalDist - distanceAlongRoute).coerceAtLeast(0.0)
        val remainingRatio = if (totalDist > 0) remainingDist / totalDist else 0.0
        val remainingTime = (route.timeMillis * remainingRatio).toLong()
        _uiState.update { it.copy(
            currentInstructionIndex = newIdx,
            distanceToNextManeuver = distToNextManeuver,
            remainingDistance = remainingDist,
            remainingTime = remainingTime,
        )}
    }

    private var lastMatchedSegment = 0

    private fun distanceAlongPolyline(lat: Double, lon: Double, points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var bestDist = Double.MAX_VALUE
        var bestSegment = 0
        var bestFraction = 0.0

        // Search from a window around the last matched segment to avoid jumping back
        val searchStart = (lastMatchedSegment - 5).coerceAtLeast(0)
        val searchEnd = (lastMatchedSegment + 200).coerceAtMost(points.size - 1)

        for (i in searchStart until searchEnd) {
            val (aLat, aLon) = points[i]
            val (bLat, bLon) = points[i + 1]
            val (dist, frac) = pointToSegmentDistance(lat, lon, aLat, aLon, bLat, bLon)
            if (dist < bestDist) {
                bestDist = dist
                bestSegment = i
                bestFraction = frac
            }
        }

        // Only move forward, never backward (unless very close to an earlier segment)
        if (bestSegment >= lastMatchedSegment || bestDist < 30.0) {
            lastMatchedSegment = bestSegment
        } else {
            bestSegment = lastMatchedSegment
            bestFraction = 0.0
        }

        var along = 0.0
        for (i in 0 until bestSegment) {
            along += haversineMeters(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
        }
        along += haversineMeters(points[bestSegment].first, points[bestSegment].second,
            points[bestSegment + 1].first, points[bestSegment + 1].second) * bestFraction
        return along
    }

    private fun pointToSegmentDistance(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Double, Double> {
        val dx = bLon - aLon
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) return haversineMeters(pLat, pLon, aLat, aLon) to 0.0
        val t = ((pLon - aLon) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        val projLat = aLat + clamped * dy
        val projLon = aLon + clamped * dx
        return haversineMeters(pLat, pLon, projLat, projLon) to clamped
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    // ── Map / location ──────────────────────────────────────────────────────

    private fun startMBTilesServer() {
        runCatching { mbTilesServer.start() }
    }

    private fun registerMbtFilesInDb() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mapsDir = java.io.File(context.filesDir, "maps")
            if (!mapsDir.exists()) return@launch
            mapsDir.listFiles()?.filter { it.name.endsWith(".mbtiles") }?.forEach { mapFile ->
                val regionId = mapFile.nameWithoutExtension
                if (db.mapRegionDao().getById(regionId) == null) {
                    db.mapRegionDao().insert(
                        biz.cesena.packride4.data.local.MapRegion(
                            id = regionId,
                            name = regionId,
                            filePath = mapFile.absolutePath,
                            downloadedAt = mapFile.lastModified(),
                            sizeBytes = mapFile.length(),
                            bboxMinLon = 0.0, bboxMinLat = 0.0,
                            bboxMaxLon = 0.0, bboxMaxLat = 0.0
                        )
                    )
                }
            }
        }
    }

    private fun observeMapSource() {
        viewModelScope.launch {
            combine(
                db.mapRegionDao().getAll(),
                userPreferences.useOfflineMap
            ) { downloadedRegions, useOffline ->
                downloadedRegions to useOffline
            }.collect { (downloadedRegions, useOffline) ->
                val mapFiles = downloadedRegions.map { File(it.filePath) }.filter { it.exists() }
                val hasOffline = mapFiles.isNotEmpty()
                if (useOffline && hasOffline) {
                    mbTilesServer.loadMaps(mapFiles)
                } else {
                    mbTilesServer.loadMaps(emptyList())
                }
                _uiState.update { it.copy(
                    hasOfflineMaps = hasOffline,
                    mapStyleJson = if (useOffline && hasOffline) ShortbreadStyle.offline() else ShortbreadStyle.online
                )}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        _uiState.update { it.copy(isTracking = true) }
    }

    fun toggleFollow() {
        _uiState.update { it.copy(isFollowing = !it.isFollowing) }
    }

    fun toggleMapOrientation() {
        _uiState.update { it.copy(mapOrientationNorthUp = !it.mapOrientationNorthUp) }
    }

    fun toggleMenu() {
        _uiState.update { it.copy(showMenu = !it.showMenu, menuSubScreen = null) }
    }

    fun openMenuSubScreen(screen: String) {
        _uiState.update { it.copy(menuSubScreen = screen) }
    }

    fun closeMenuSubScreen() {
        _uiState.update { it.copy(menuSubScreen = null) }
    }

    fun closeMenuAll() {
        _uiState.update { it.copy(showMenu = false, menuSubScreen = null) }
    }

    fun toggleInfoFullscreen() {
        _uiState.update { it.copy(showInfoFullscreen = !it.showInfoFullscreen, selectingWidgetSide = null) }
    }

    fun openWidgetSelector(side: String) {
        _uiState.update { it.copy(showInfoFullscreen = true, selectingWidgetSide = side) }
    }

    fun selectWidgetValue(dataKey: String) {
        val side = _uiState.value.selectingWidgetSide ?: return
        val navigating = _uiState.value.isNavigating
        userPreferences.setWidgetSelection(side, navigating, dataKey)
        _uiState.update {
            val updated = if (navigating) {
                if (side == "left") it.copy(widgetLeftNav = dataKey) else it.copy(widgetRightNav = dataKey)
            } else {
                if (side == "left") it.copy(widgetLeftIdle = dataKey) else it.copy(widgetRightIdle = dataKey)
            }
            updated.copy(showInfoFullscreen = false, selectingWidgetSide = null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mbTilesServer.stop()
    }
}
