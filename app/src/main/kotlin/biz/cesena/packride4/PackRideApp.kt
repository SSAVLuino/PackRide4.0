package biz.cesena.packride4

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PackRideApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Global app init (logging, crash reporting, etc.) goes here
    }
}
