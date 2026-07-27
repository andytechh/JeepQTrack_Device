package com.surendramaran.Jeepqs.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class GpsTracker(
    private val context: Context,
    private val onLocationUpdate: (Double, Double) -> Unit
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private var lastSpeed = 0.0
    private var lastHeading = 0.0

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startTracking() {
        Log.d("GpsTracker", "🔄 startTracking called")

        if (!hasPermission()) {
            Log.d("GpsTracker", "❌ No permission")
            return
        }

        isTracking = true
        Log.d("GpsTracker", "✅ Tracking started")

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L
        ).apply {
            setMinUpdateIntervalMillis(2000L)
            setMaxUpdateDelayMillis(5000L)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val lat = location.latitude
                    val lng = location.longitude
                    val speed = location.speed * 3.6 // Convert m/s to km/h
                    val heading = location.bearing.toDouble()

                    lastSpeed = speed
                    lastHeading = heading

                    Log.d("GpsTracker", "📍 Location: $lat, $lng, Speed: $speed km/h, Heading: $heading")

                    // Call the callback with speed and heading
                    onLocationUpdate(lat, lng)
                } ?: run {
                    Log.d("GpsTracker", "⚠️ No location in result")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d("GpsTracker", "📍 Availability: ${availability.isLocationAvailable}")
            }
        }

        if (hasPermission()) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d("GpsTracker", "✅ Location updates requested")

            getLastKnownLocation { lat, lng ->
                Log.d("GpsTracker", "📍 Last known: $lat, $lng")
                onLocationUpdate(lat, lng)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getLastKnownLocation(callback: (Double, Double) -> Unit) {
        if (!hasPermission()) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                Log.d("GpsTracker", "📍 Last known: ${it.latitude}, ${it.longitude}")
                callback(it.latitude, it.longitude)
            } ?: run {
                Log.d("GpsTracker", "⚠️ No last known location")
            }
        }.addOnFailureListener { e ->
            Log.d("GpsTracker", "❌ Failed to get last known: ${e.message}")
        }
    }

    private fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isTracking(): Boolean = isTracking

    fun stopTracking() {
        Log.d("GpsTracker", "🛑 Stopping GPS tracking...")

        isTracking = false

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            Log.d("GpsTracker", "✅ Location updates removed")
        }

        locationCallback = null
        Log.d("GpsTracker", "✅ GPS tracking stopped")
    }
}