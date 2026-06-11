package biz.cesena.packride4

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre

@HiltAndroidApp
class PackRideApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
