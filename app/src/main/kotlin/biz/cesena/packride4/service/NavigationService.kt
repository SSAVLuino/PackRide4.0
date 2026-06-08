package biz.cesena.packride4.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import biz.cesena.packride4.R
import com.google.android.gms.location.*

/**
 * Foreground service that keeps GPS tracking alive while the app is in the background.
 * Started/stopped by [RoutingViewModel].
 *
 * GPS fixes are broadcast locally via [NavigationBroadcasts]; other components
 * (e.g., RoutingViewModel, GPX recorder) listen on the same LocalBroadcastManager.
 */
class NavigationService : Service() {

    companion object {
        const val ACTION_START = "biz.cesena.packride4.NAV_START"
        const val ACTION_STOP  = "biz.cesena.packride4.NAV_STOP"
        const val ACTION_LOCATION = "biz.cesena.packride4.LOCATION_UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_SPEED = "speed"   // m/s
        const val EXTRA_BEARING = "bearing"

        private const val CHANNEL_ID = "navigation_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { broadcastLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                requestLocationUpdates()
            }
            ACTION_STOP -> {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun broadcastLocation(loc: Location) {
        val intent = Intent(ACTION_LOCATION).apply {
            putExtra(EXTRA_LAT, loc.latitude)
            putExtra(EXTRA_LON, loc.longitude)
            putExtra(EXTRA_SPEED, loc.speed)
            putExtra(EXTRA_BEARING, loc.bearing)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.nav_service_notification_title))
            .setContentText(getString(R.string.nav_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nav_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Canale per la navigazione GPS attiva in background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
