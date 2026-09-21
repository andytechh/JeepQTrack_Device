package com.surendramaran.Jeepqs.tracking

import com.surendramaran.Jeepqs.detector.BoundingBox
import kotlin.math.hypot
import kotlin.math.min

/**
 * Centroid tracker with:
 *  - one-to-one (greedy, globally sorted) assignment, so two boxes can't steal the same track
 *  - velocity prediction, so fast walkers don't break into a new ID every frame
 *  - a wider gate that grows while a track is briefly lost
 *
 * IMPORTANT: call update() on EVERY frame, including frames with zero detections
 * (pass emptyList()). That is what ages and prunes lost tracks.
 */
class CentroidTracker {

    data class Track(
        val id: Int,
        var cx: Float,
        var cy: Float,
        var previousCx: Float,
        var previousCy: Float,
        var vx: Float = 0f,          // smoothed velocity, normalized units per frame
        var vy: Float = 0f,
        var missed: Int = 0,         // consecutive frames without a match
        var box: BoundingBox? = null,
        var confirmedFrames: Int = 1 // total frames this track has been matched
    )

    private class Candidate(val trackIdx: Int, val boxIdx: Int, val dist: Float)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    companion object {
        // Frames a track must be matched before the counter trusts it.
        const val CONFIRM_FRAMES = 3

        private const val BASE_GATE = 0.15f     // was 0.08 - far too tight for walking speed
        private const val GATE_GROWTH = 0.02f   // extra gate per missed frame
        private const val MAX_GATE = 0.30f
        private const val MAX_MISSED = 10
        private const val MAX_PREDICT_STEPS = 3
        private const val VEL_SMOOTH = 0.6f
    }

    fun update(boxes: List<BoundingBox>): Map<Int, BoundingBox> {
        val result = mutableMapOf<Int, BoundingBox>()

        // 1. Build every plausible (track, box) pair using predicted positions.
        val candidates = ArrayList<Candidate>()
        for ((ti, t) in tracks.withIndex()) {
            val steps = min(t.missed + 1, MAX_PREDICT_STEPS)
            val px = t.cx + t.vx * steps
            val py = t.cy + t.vy * steps
            val gate = min(BASE_GATE + t.missed * GATE_GROWTH, MAX_GATE)
            for ((bi, b) in boxes.withIndex()) {
                val d = hypot(b.cx - px, b.cy - py)
                if (d <= gate) candidates.add(Candidate(ti, bi, d))
            }
        }

        // 2. Closest pairs win; each track and each box used at most once.
        candidates.sortBy { it.dist }
        val usedTracks = BooleanArray(tracks.size)
        val usedBoxes = BooleanArray(boxes.size)

        for (c in candidates) {
            if (usedTracks[c.trackIdx] || usedBoxes[c.boxIdx]) continue
            usedTracks[c.trackIdx] = true
            usedBoxes[c.boxIdx] = true

            val t = tracks[c.trackIdx]
            val b = boxes[c.boxIdx]
            val frames = (t.missed + 1).toFloat()

            t.vx = VEL_SMOOTH * t.vx + (1f - VEL_SMOOTH) * ((b.cx - t.cx) / frames)
            t.vy = VEL_SMOOTH * t.vy + (1f - VEL_SMOOTH) * ((b.cy - t.cy) / frames)
            t.previousCx = t.cx
            t.previousCy = t.cy
            t.cx = b.cx
            t.cy = b.cy
            t.box = b
            t.missed = 0
            t.confirmedFrames++
            result[t.id] = b
        }

        // 3. Unmatched boxes start new tracks.
        for ((bi, b) in boxes.withIndex()) {
            if (usedBoxes[bi]) continue
            val t = Track(
                id = nextId++,
                cx = b.cx, cy = b.cy,
                previousCx = b.cx, previousCy = b.cy,
                box = b
            )
            tracks.add(t)
            result[t.id] = b
        }

        // 4. Age unmatched (pre-existing) tracks and prune the dead ones.
        for ((ti, t) in tracks.withIndex()) {
            if (ti < usedTracks.size && !usedTracks[ti]) t.missed++
        }
        tracks.removeAll { it.missed > MAX_MISSED }

        return result
    }

    fun getTracks(): List<Track> = tracks.toList()

    fun clear() {
        tracks.clear()
    }
}