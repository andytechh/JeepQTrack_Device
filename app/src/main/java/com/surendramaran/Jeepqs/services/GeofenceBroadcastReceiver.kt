package com.surendramaran.Jeepqs.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.surendramaran.Jeepqs.managers.GeofenceManager

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent == null) {
            Log.e("GeofenceReceiver", "GeofencingEvent is null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Error: ${geofencingEvent.errorCode}")
            return
        }

        val transitionType = geofencingEvent.geofenceTransition
        val geofenceList = geofencingEvent.triggeringGeofences

        if (geofenceList == null || geofenceList.isEmpty()) {
            Log.e("GeofenceReceiver", "No geofences triggered")
            return
        }

        Log.d("GeofenceReceiver", "Geofence transition: $transitionType")

        // Pass to GeofenceManager (you need a reference or use an interface)
        // For simplicity, we'll just log it
        when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                Log.d("GeofenceReceiver", "🚪 Jeepney ENTERED terminal")
                // Call your queue management API here
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.d("GeofenceReceiver", "🚪 Jeepney EXITED terminal")
                // Call your queue management API here
            }
        }
    }
}