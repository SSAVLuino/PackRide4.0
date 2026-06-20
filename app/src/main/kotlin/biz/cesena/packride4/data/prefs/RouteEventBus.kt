package biz.cesena.packride4.data.prefs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LoadRouteEvent(val id: Long, val lat: Double, val lon: Double, val name: String)

@Singleton
class RouteEventBus @Inject constructor() {
    private val _routeDeleted = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val routeDeleted: SharedFlow<Long> = _routeDeleted.asSharedFlow()

    private val _loadRoute = MutableSharedFlow<LoadRouteEvent>(extraBufferCapacity = 1)
    val loadRoute: SharedFlow<LoadRouteEvent> = _loadRoute.asSharedFlow()

    fun notifyRouteDeleted(id: Long) {
        _routeDeleted.tryEmit(id)
    }

    fun notifyLoadRoute(id: Long, lat: Double, lon: Double, name: String) {
        _loadRoute.tryEmit(LoadRouteEvent(id, lat, lon, name))
    }
}
