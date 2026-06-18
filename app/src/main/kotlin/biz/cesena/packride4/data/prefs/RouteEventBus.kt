package biz.cesena.packride4.data.prefs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteEventBus @Inject constructor() {
    private val _routeDeleted = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val routeDeleted: SharedFlow<Long> = _routeDeleted.asSharedFlow()

    fun notifyRouteDeleted(id: Long) {
        _routeDeleted.tryEmit(id)
    }
}
