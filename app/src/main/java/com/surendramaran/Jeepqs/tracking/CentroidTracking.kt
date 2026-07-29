package com.surendramaran.Jeepqs.tracking

import com.surendramaran.Jeepqs.detector.BoundingBox

class CentroidTracker {

    data class Track(
        var id: Int,
        var cx: Float,
        var cy: Float,
        var previousCx: Float,
        var previousCy: Float,
        var missed: Int = 0,
        var box: BoundingBox? = null
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    private val MAX_DISTANCE = 0.08f
    private val MAX_MISSED = 10

    fun update(boxes: List<BoundingBox>): Map<Int, BoundingBox> {
        val result = mutableMapOf<Int, BoundingBox>()
        val matchedTracks = mutableSetOf<Track>()

        for (box in boxes) {
            var bestTrack: Track? = null
            var bestDistance = Float.MAX_VALUE

            for (track in tracks) {
                val dx = box.cx - track.cx
                val dy = box.cy - track.cy
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)

                if (distance < MAX_DISTANCE && distance < bestDistance) {
                    bestDistance = distance
                    bestTrack = track
                }
            }

            if (bestTrack != null) {
                bestTrack.previousCx = bestTrack.cx
                bestTrack.previousCy = bestTrack.cy
                bestTrack.cx = box.cx
                bestTrack.cy = box.cy
                bestTrack.missed = 0
                bestTrack.box = box
                matchedTracks.add(bestTrack)
                result[bestTrack.id] = box
            } else {
                val newTrack = Track(
                    id = nextId++,
                    cx = box.cx,
                    cy = box.cy,
                    previousCx = box.cx,
                    previousCy = box.cy,
                    box = box
                )
                tracks.add(newTrack)
                matchedTracks.add(newTrack)
                result[newTrack.id] = box
            }
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (!matchedTracks.contains(track)) {
                track.missed++
            }
            if (track.missed > MAX_MISSED) {
                iterator.remove()
            }
        }

        return result
    }

    fun getTracks(): List<Track> {
        return tracks.toList()
    }
}