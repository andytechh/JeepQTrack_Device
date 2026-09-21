package com.surendramaran.Jeepqs.managers

import android.util.Log
import com.surendramaran.Jeepqs.detector.BoundingBox
import com.surendramaran.Jeepqs.tracking.CentroidTracker
import com.surendramaran.Jeepqs.tracking.PassengerCounter

class PassengerManager {

    private val tracker = CentroidTracker()
    private val passengerCounter = PassengerCounter()

    /**
     * Call on EVERY frame - pass emptyList() when nothing was detected so the
     * tracker can age/prune tracks and the counter can finish crossings.
     */
    fun processDetections(
        boundingBoxes: List<BoundingBox>,
        door: String
    ): PassengerData {
        // 1. Update tracker with new detections
        val tracked = tracker.update(boundingBoxes)

        // 2. Update passenger counter with tracked data
        passengerCounter.update(tracker.getTracks(), door)

        Log.d("DET", "boxes=${boundingBoxes.size} tracks=${tracker.getTracks().size} boarded=${passengerCounter.boarded} exited=${passengerCounter.exited}")

        // 3. Return passenger data
        return PassengerData(
            inside = passengerCounter.inside,
            boarded = passengerCounter.boarded,
            exited = passengerCounter.exited,
            frontBoarded = passengerCounter.frontBoarded,
            rearBoarded = passengerCounter.rearBoarded,
            frontExited = passengerCounter.frontExited,
            rearExited = passengerCounter.rearExited,
            trackedBoxes = tracked.values.toList()
        )
    }

    fun reset() {
        tracker.clear()
        passengerCounter.reset()
    }

    data class PassengerData(
        val inside: Int,
        val boarded: Int,
        val exited: Int,
        val frontBoarded: Int,
        val rearBoarded: Int,
        val frontExited: Int,
        val rearExited: Int,
        val trackedBoxes: List<BoundingBox>
    )
}