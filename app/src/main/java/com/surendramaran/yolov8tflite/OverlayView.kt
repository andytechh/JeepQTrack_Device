package com.surendramaran.Jeepqs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.surendramaran.Jeepqs.detector.BoundingBox

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var passengerCount = 0

    private val bounds = Rect()

    private val boxPaint = Paint().apply {
        color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    // ✅ Different colors for front/rear door
    private fun getBoxColor(cx: Float): Int {
        return if (cx < 0.4f) {
            Color.parseColor("#00BFFF")  // Blue for front
        } else {
            Color.parseColor("#FF6B6B")  // Red for rear
        }
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val countPaint = Paint().apply {
        color = Color.GREEN
        textSize = 55f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val roiPaint = Paint().apply {
        color = Color.argb(60, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun clear() {
        results = emptyList()
        passengerCount = 0
        invalidate()
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        passengerCount = boundingBoxes.size
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // ✅ Draw ROI (Jeepney area)
        canvas.drawRect(
            width * 0.08f,
            height * 0.12f,
            width * 0.92f,
            height * 0.88f,
            roiPaint
        )

        // ✅ Draw counting line
        val lineY = height * 0.55f  // Was 0.65f
        canvas.drawLine(0f, lineY, width, lineY, linePaint)

        // Draw label for counting line
        textPaint.color = Color.YELLOW
        textPaint.textSize = 30f
        canvas.drawText("Counting Line", 20f, lineY - 20f, textPaint)
        textPaint.color = Color.WHITE

        // Draw all detections
        results.forEach { box ->
            val left = box.x1 * width
            val top = box.y1 * height
            val right = box.x2 * width
            val bottom = box.y2 * height

            // ✅ Color by door
            boxPaint.color = getBoxColor(box.cx)
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Door label
            val doorLabel = if (box.cx < 0.4f) "F" else "R"
            val label = "$doorLabel ${(box.cnf * 100).toInt()}%"

            textPaint.getTextBounds(label, 0, label.length, bounds)

            canvas.drawRect(
                left,
                top - bounds.height() - 10f,
                left + bounds.width() + 20f,
                top,
                textBackgroundPaint
            )

            canvas.drawText(
                label,
                left + 10f,
                top - 10f,
                textPaint
            )
        }

        // ✅ Draw passenger count with door breakdown
        val frontCount = results.count { it.cx < 0.4f }
        val rearCount = results.count { it.cx > 0.6f }

        countPaint.color = Color.WHITE
        countPaint.textSize = 50f
        canvas.drawText(
            "Total: $passengerCount  F:$frontCount R:$rearCount",
            40f,
            60f,
            countPaint
        )

        // ✅ Draw line position info
        countPaint.textSize = 30f
        countPaint.color = Color.GRAY
        canvas.drawText("Line: 55%", width - 150f, 60f, countPaint)
    }
}