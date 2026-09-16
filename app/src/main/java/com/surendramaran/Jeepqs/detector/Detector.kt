package com.surendramaran.Jeepqs.detector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val detectorListener: DetectorListener,
) {

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    // Region of Interest - only count inside jeepney doorway
    private val ROI_MIN_X = 0.08f
    private val ROI_MAX_X = 0.92f
    private val ROI_MIN_Y = 0.12f
    private val ROI_MAX_Y = 0.88f

    // True if the model's input tensor is NCHW (channels-first: [1,3,H,W]),
    // false if NHWC (channels-last: [1,H,W,3]). Detected at load time from
    // the actual model rather than assumed, since different export
    // pipelines/versions can produce either layout - building the input
    // buffer in the wrong one silently feeds the model scrambled pixel
    // data with no error thrown.
    private var isNCHW = false

    init {
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply{
            if(compatList.isDelegateSupportedOnThisDevice){
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
            } else {
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            if (inputShape[1] == 3) {
                // NCHW: [1, 3, H, W]
                isNCHW = true
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            } else {
                // NHWC: [1, H, W, 3]
                isNCHW = false
                tensorWidth = inputShape[1]
                tensorHeight = inputShape[2]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }

        try {
            val inputStream: InputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))

            var line: String? = reader.readLine()
            while (line != null && line != "") {
                labels.add(line)
                line = reader.readLine()
            }

            reader.close()
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()

        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply{
                if(compatList.isDelegateSupportedOnThisDevice){
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply{
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    fun close() {
        interpreter.close()
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0) return
        if (tensorHeight == 0) return
        if (numChannel == 0) return
        if (numElements == 0) return

        var inferenceTime = SystemClock.uptimeMillis()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
        val imageBuffer = bitmapToInputBuffer(resizedBitmap)

        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(imageBuffer, output.buffer)

        val bestBoxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes == null || bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        detectorListener.onDetect(bestBoxes, inferenceTime)
    }

    // Builds the model's input buffer directly from pixel data, in
    // whichever layout (NCHW or NHWC) the model actually declares -
    // rather than relying on a support-library helper that only ever
    // produces one fixed layout (NHWC) regardless of what the model wants.
    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * 3 * tensorWidth * tensorHeight * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(tensorWidth * tensorHeight)
        bitmap.getPixels(pixels, 0, tensorWidth, 0, 0, tensorWidth, tensorHeight)

        if (isNCHW) {
            // Channel-major: every R value, then every G value, then every B value
            for (channel in 0 until 3) {
                for (pixel in pixels) {
                    val value = when (channel) {
                        0 -> (pixel shr 16) and 0xFF
                        1 -> (pixel shr 8) and 0xFF
                        else -> pixel and 0xFF
                    }
                    buffer.putFloat(value / INPUT_STANDARD_DEVIATION)
                }
            }
        } else {
            // Pixel-major (NHWC): R,G,B together for each pixel, in row-major order
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / INPUT_STANDARD_DEVIATION)
                buffer.putFloat(((pixel shr 8) and 0xFF) / INPUT_STANDARD_DEVIATION)
                buffer.putFloat((pixel and 0xFF) / INPUT_STANDARD_DEVIATION)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun bestBox(array: FloatArray) : List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel){
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD && maxIdx >= 0 && maxIdx < labels.size) {
                val clsName = labels[maxIdx]

                // Only count "person" class
                if (clsName != "person") continue

                val cx = array[c]
                val cy = array[c + numElements]
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)

                // Bounds check
                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                // Only count if inside ROI (jeepney doorway area)
                if (cx < ROI_MIN_X || cx > ROI_MAX_X) continue
                if (cy < ROI_MIN_Y || cy > ROI_MAX_Y) continue

                // Reject boxes too small to plausibly be a passenger's
                // head/shoulders from this overhead mount - tune against
                // your actual footage.
                val boxW = x2 - x1
                val boxH = y2 - y1
                if (boxW < MIN_BOX_WIDTH || boxH < MIN_BOX_HEIGHT) continue

                // Wide-tolerance aspect ratio guard. From directly above, a
                // head+shoulders blob is roughly square, but this only
                // rejects extreme slivers (very thin/wide shapes a real
                // person's box would never produce) - a hand, an edge of
                // railing, a shadow strip - without over-constraining the
                // legitimate square-ish range like the old strict "must be
                // taller than wide" filter did.
                val aspectRatio = boxW / boxH
                if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>) : MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while(sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32

        // Confidence threshold. Verified against real photos: this model
        // gives sharp, confident detections (0.87-0.90) on actual people,
        // not borderline scores - so raised significantly from the earlier
        // untested guess of 0.4F to cut out noise. If real passengers start
        // getting missed, lower this gradually (e.g. 0.6) rather than
        // jumping back to 0.4.
        private const val CONFIDENCE_THRESHOLD = 0.7F
        private const val IOU_THRESHOLD = 0.45F

        // Minimum plausible size for a head+shoulders blob seen from
        // directly overhead, in normalized [0,1] coordinates. This depends
        // heavily on your actual mount height and camera FOV - measure
        // against real footage rather than trusting these defaults.
        private const val MIN_BOX_WIDTH = 0.06f
        private const val MIN_BOX_HEIGHT = 0.06f

        // Wide tolerance around square (1.0) - rejects only extreme
        // slivers, not the natural square-ish variation of a real
        // head+shoulders blob seen from overhead.
        private const val MIN_ASPECT_RATIO = 0.35f
        private const val MAX_ASPECT_RATIO = 2.8f
    }
}