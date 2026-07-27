// app/src/main/java/com/surendramaran/Jeepqs/managers/UploadManager.kt
package com.surendramaran.Jeepqs.managers

import android.util.Log
import com.surendramaran.Jeepqs.device.DeviceRole
import com.surendramaran.Jeepqs.services.SupabaseService

class UploadManager(
    private val supabase: SupabaseService
) {

    private var lastUploadTime = 0L
    private val UPLOAD_INTERVAL = 3000L // 3 seconds

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
        if (now - lastUploadTime < UPLOAD_INTERVAL) return false
        lastUploadTime = now

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
        if (now - lastUploadTime < UPLOAD_INTERVAL)
            return false

        lastUploadTime = now

        supabase.updateGps(lat, lng) { success ->
            Log.d("UploadManager", "GPS upload: $success")
        }
        return true
    }
}