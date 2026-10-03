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
        val tracks = tracker.getTracks()

        // 2. Update passenger counter with tracked data
        passengerCounter.update(tracks, door)

        Log.d("DET", "boxes=${boundingBoxes.size} tracks=${tracks.size} boarded=${passengerCounter.boarded} exited=${passengerCounter.exited}")

        // Only show CONFIRMED tracks in the overlay. A track that has only
        // been matched for 1-2 frames is exactly the kind of noisy,
        // one-off false detection that looks like a "ghost box" on empty
        // ground - it still exists internally (so a real person is still
        // tracked from their very first frame), but isn't drawn until it's
        // proven to persist.
        val confirmedIds = tracks
            .filter { it.confirmedFrames >= CentroidTracker.CONFIRM_FRAMES }
            .map { it.id }
            .toSet()
        val visibleBoxes = tracked.filterKeys { it in confirmedIds }.values.toList()

        // 3. Return passenger data
        return PassengerData(
            inside = passengerCounter.inside,
            boarded = passengerCounter.boarded,
            exited = passengerCounter.exited,
            frontBoarded = passengerCounter.frontBoarded,
            rearBoarded = passengerCounter.rearBoarded,
            frontExited = passengerCounter.frontExited,
            rearExited = passengerCounter.rearExited,
            trackedBoxes = visibleBoxes
        )
    }

    fun reset() {
        tracker.clear()
        passengerCounter.reset()
    }
    fun restoreOccupancy(count: Int) {
        passengerCounter.restoreOccupancy(count)   // use your actual PassengerCounter field name
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