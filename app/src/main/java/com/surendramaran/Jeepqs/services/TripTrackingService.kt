package com.surendramaran.Jeepqs.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.surendramaran.Jeepqs.settings.DeviceConfig

/**
 * Replaces GpsTracker for the PRIMARY device. Keeps GPS running while the app
 * is in the background or the screen is off, and sends it through your existing
 * SupabaseService:
 *   updateGps()       -> live position on the jeepneys row (every fix)
 *   sendGpsTracking() -> history row in gps_tracking (every HISTORY_EVERY_MS)
 *
 * Trips are NOT handled here. Your DB triggers create/close them when
 * jeepneys.status changes (geofence / dispatcher / depart_jeepney).
 *
 * ACTION_START starts it (safe to call repeatedly), ACTION_STOP ends it.
 */
class TripTrackingService : Service() {

    companion object {
        const val ACTION_START = "com.surendramaran.Jeepqs.TRACK_START"
        const val ACTION_STOP = "com.surendramaran.Jeepqs.TRACK_STOP"

        private const val TAG = "TripTracking"
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIF_ID = 1001

        private const val LIVE_INTERVAL_MS = 5_000L
        private const val HISTORY_EVERY_MS = 15_000L
        private const val MIN_DISTANCE_M = 5f

        private const val PREFS = "trip_tracking"
        private const val KEY_TRACKING = "tracking"
    }

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastHistoryAt = 0L

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val supabase by lazy { SupabaseService(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        DeviceConfig.init(applicationContext)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Sending GPS")

        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> {
                prefs.edit().putBoolean(KEY_TRACKING, false).apply()
                stopLocationUpdates()
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                // Android restarted us after killing the process: resume if we were tracking.
                if (prefs.getBoolean(KEY_TRACKING, false)) startTracking()
                else {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ tracking

    private fun startTracking() {
        prefs.edit().putBoolean(KEY_TRACKING, true).apply()
        acquireWakeLock()
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (callback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission missing")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LIVE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocation(it) }
            }
        }
        fused.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private fun onLocation(loc: Location) {
        supabase.updateGps(loc.latitude, loc.longitude) { }

        val now = SystemClock.elapsedRealtime()
        if (now - lastHistoryAt >= HISTORY_EVERY_MS) {
            lastHistoryAt = now
            supabase.sendGpsTracking(
                latitude = loc.latitude,
                longitude = loc.longitude,
                speed = loc.speed * 3.6,          // m/s -> km/h
                heading = loc.bearing.toDouble()
            ) { }
        }
    }

    // ---------------------------------------------- notification / wakelock

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jeepqs")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)  // swap for your own icon
            .setOngoing(true)
            .build()

    private fun startAsForeground(text: String) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jeepqs:gps")
            .apply { acquire(12 * 60 * 60 * 1000L) }   // safety timeout: 12 h
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopLocationUpdates()
        releaseWakeLock()
        super.onDestroy()
    }
}