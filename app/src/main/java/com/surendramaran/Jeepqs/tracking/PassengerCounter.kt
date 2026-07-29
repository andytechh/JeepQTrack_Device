package com.surendramaran.Jeepqs.tracking

import android.util.Log

class PassengerCounter {

    private val countedBoarded = mutableSetOf<Int>()
    private val countedExited = mutableSetOf<Int>()

    var boarded = 0
        private set

    var exited = 0
        private set

    var frontBoarded = 0
        private set
    var rearBoarded = 0
        private set
    var frontExited = 0
        private set
    var rearExited = 0
        private set

    val inside: Int
        get() = boarded - exited

    private val LINE_Y = 0.55f

    fun update(tracks: List<CentroidTracker.Track>, door: String) {
        for (track in tracks) {
            val oldY = track.previousCy
            val newY = track.cy

            Log.d("TRACK", "ID=${track.id} old=$oldY new=$newY")

            // BOARDING: Moving DOWN (entering jeepney)
            if (oldY < LINE_Y && newY >= LINE_Y) {
                Log.d("TRACK", "BOARDING ID=${track.id}")
                if (!countedBoarded.contains(track.id)) {
                    countedBoarded.add(track.id)
                    boarded++
                    if (door == "FRONT") frontBoarded++ else rearBoarded++
                }
            }

            // EXITING: Moving UP (leaving jeepney)
            if (oldY > LINE_Y && newY <= LINE_Y) {
                Log.d("TRACK", "EXITING ID=${track.id}")
                if (!countedExited.contains(track.id)) {
                    countedExited.add(track.id)
                    exited++
                    if (door == "FRONT") frontExited++ else rearExited++
                }
            }
        }
    }

    fun reset() {
        countedBoarded.clear()
        countedExited.clear()
        boarded = 0
        exited = 0
        frontBoarded = 0
        rearBoarded = 0
        frontExited = 0
        rearExited = 0
    }
}