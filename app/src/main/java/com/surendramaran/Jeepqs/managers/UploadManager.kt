// app/src/main/java/com/surendramaran/Jeepqs/managers/UploadManager.kt
package com.surendramaran.Jeepqs.managers

import android.util.Log
import com.surendramaran.Jeepqs.device.DeviceRole
import com.surendramaran.Jeepqs.services.SupabaseService

class UploadManager(
    private val supabase: SupabaseService
) {
    // Separate throttles — GPS and passenger-count uploads shouldn't
    // compete for the same timer, or one starves the other.
    private var lastPassengerUploadTime = 0L
    private var lastGpsUploadTime = 0L

    private val PASSENGER_UPLOAD_INTERVAL = 3000L  // 3 seconds — occupancy changes are bursty, keep this responsive
    private val GPS_UPLOAD_INTERVAL = 10000L        // 10 seconds — live map only, not geofence-critical

    fun uploadPassengerData(
        data: PassengerManager.PassengerData,
        role: DeviceRole,
        door: String,
        status: String
    ): Boolean {
        val shouldUpload = status in listOf("loading", "en_route", "arrived", "dispatched")

        if (!shouldUpload) {
            Log.d("UploadManager", "⏸️ Status: $status - no upload")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastPassengerUploadTime < PASSENGER_UPLOAD_INTERVAL) return false
        lastPassengerUploadTime = now

        val total = data.inside
        val front = data.frontBoarded
        val rear = data.rearBoarded

        Log.d("UploadManager", "📤 Uploading ($status) - Total: $total")

        if (role == DeviceRole.PRIMARY) {
            uploadPrimary(total, front, rear)
        } else {
            uploadSecondary(door, front, rear)
        }
        return true
    }

    private fun uploadPrimary(total: Int, front: Int, rear: Int) {
        supabase.upsertDoorCounts(front, rear) { success ->
            Log.d("UploadManager", "Door counts: $success")
        }
        supabase.updateOccupancy(total) { success ->
            Log.d("UploadManager", "Occupancy: $success")
        }
    }

    private fun uploadSecondary(door: String, front: Int, rear: Int) {
        if (door == "FRONT") {
            supabase.upsertDoorCounts(front, 0) { success ->
                Log.d("UploadManager", "Front: $success")
            }
        } else {
            supabase.upsertDoorCounts(0, rear) { success ->
                Log.d("UploadManager", "Rear: $success")
            }
        }
    }

    fun uploadGps(lat: Double, lng: Double): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGpsUploadTime < GPS_UPLOAD_INTERVAL)
            return false

        lastGpsUploadTime = now

        supabase.updateGps(lat, lng) { success ->
            Log.d("UploadManager", "GPS upload: $success")
        }
        supabase.sendGpsTracking(lat, lng, 0.0, 0.0) { success ->
            Log.d("UploadManager", "GPS tracking log: $success")
        }
        return true
    }
}