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

    private val LINE_OUTER = 1f - (0.50f - 0.10f)
    private val LINE_INNER = 1f - (0.50f + 0.10f)

    private val boxPaint = Paint().apply {
        color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        strokeWidth = 6f
        style = Paint.Style.STROKE
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

    private val outerLinePaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val innerLinePaint = Paint().apply {
        color = Color.CYAN
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

        canvas.drawRect(
            width * 0.08f,
            height * 0.12f,
            width * 0.92f,
            height * 0.88f,
            roiPaint
        )

        val outerY = height * LINE_OUTER
        val innerY = height * LINE_INNER

        canvas.drawLine(0f, outerY, width, outerY, outerLinePaint)
        canvas.drawLine(0f, innerY, width, innerY, innerLinePaint)

        textPaint.textSize = 30f

        textPaint.color = Color.YELLOW
        canvas.drawText("Outer (${(LINE_OUTER * 100).toInt()}%)", 20f, outerY - 12f, textPaint)

        textPaint.color = Color.CYAN
        canvas.drawText("Inner (${(LINE_INNER * 100).toInt()}%)", 20f, innerY - 12f, textPaint)

        textPaint.color = Color.WHITE

        results.forEach { box ->
            val left = box.x1 * width
            val top = box.y1 * height
            val right = box.x2 * width
            val bottom = box.y2 * height

            boxPaint.color = ContextCompat.getColor(context, R.color.bounding_box_color)
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${(box.cnf * 100).toInt()}%"

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

        countPaint.color = Color.WHITE
        countPaint.textSize = 32f
        canvas.drawText(
            "In frame: $passengerCount",
            40f,
            60f,
            countPaint
        )
    }
}