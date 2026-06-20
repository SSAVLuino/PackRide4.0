package biz.cesena.packride4.ui.savedroutes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.local.SavedRoute
import biz.cesena.packride4.data.local.SavedRouteDao
import biz.cesena.packride4.data.prefs.RouteEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedRoutesViewModel @Inject constructor(
    private val dao: SavedRouteDao,
    private val routeEventBus: RouteEventBus,
) : ViewModel() {

    val routes = dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(route: SavedRoute) {
        viewModelScope.launch {
            dao.delete(route)
            routeEventBus.notifyRouteDeleted(route.id.toLong())
        }
    }

    fun loadRoute(route: SavedRoute) {
        routeEventBus.notifyLoadRoute(route.id.toLong(), route.destinationLat, route.destinationLon, route.name)
    }
}
