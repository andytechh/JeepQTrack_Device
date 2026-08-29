package com.surendramaran.Jeepqs.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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

        if (geofenceList.isNullOrEmpty()) {
            Log.e("GeofenceReceiver", "No geofences triggered")
            return
        }

        Log.d("GeofenceReceiver", "Geofence transition: $transitionType")

        // GeofenceManager only lives while MainActivity's process is alive
        // (activeInstance is set in startGeofence() / cleared in stopGeofence()).
        // If the process was killed, there's nothing to forward to here —
        // LoadingCheckWorker is what re-derives "loading" state from Supabase
        // in that case, so we don't crash or silently retry.
        val manager = GeofenceManager.activeInstance
        if (manager == null) {
            Log.w("GeofenceReceiver", "No active GeofenceManager — process was likely killed; transition dropped")
            return
        }

        geofenceList.forEach { geofence ->
            val requestId = geofence.requestId
            Log.d("GeofenceReceiver", "Forwarding transition $transitionType for $requestId")
            manager.onGeofenceTransition(transitionType, requestId)
        }
    }
}