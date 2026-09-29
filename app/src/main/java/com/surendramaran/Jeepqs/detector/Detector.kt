package com.surendramaran.Jeepqs.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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

    // Index of the "person" class in the labels file (-1 if missing).
    private var personIdx = -1

    // True if the model input is NCHW ([1,3,H,W]); false if NHWC ([1,H,W,3]).
    private var isNCHW = false

    // ------------------------------------------------------------------
    // Buffers allocated ONCE and reused every frame. The old code allocated
    // ~10 MB of large objects per frame, which caused constant garbage
    // collection and a very low frame rate (~2 fps).
    // ------------------------------------------------------------------
    private var scaledBitmap: Bitmap? = null
    private var scaledCanvas: Canvas? = null
    private val scalePaint = Paint()
    private val srcRect = Rect()
    private val dstRect = Rect()

    private lateinit var pixels: IntArray
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var inputFloats: FloatBuffer
    private lateinit var outputBuffer: ByteBuffer
    private lateinit var outputFloats: FloatBuffer

    // Region of interest, kept wide so edge entries/exits are seen.
    private val ROI_MIN_X = 0.00f
    private val ROI_MAX_X = 1.00f
    private val ROI_MIN_Y = 0.00f
    private val ROI_MAX_Y = 1.00f

    init {
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
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
                isNCHW = true
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            } else {
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

        personIdx = labels.indexOf("person")

        // DEBUG - confirms the model loaded with sane shapes and that
        // "person" was actually found in labels.txt. Filter logcat by
        // tag "DETECT_INIT" right after install/first run. If personIdx
        // prints -1, labels.txt doesn't contain the exact string
        // "person" and NOTHING will ever be detected regardless of any
        // confidence threshold - fix labels.txt first in that case.
        android.util.Log.d(
            "DETECT_INIT",
            "modelPath=$modelPath tensorW=$tensorWidth tensorH=$tensorHeight " +
                    "numChannel=$numChannel numElements=$numElements " +
                    "personIdx=$personIdx isNCHW=$isNCHW labels=$labels"
        )

        if (tensorWidth > 0 && tensorHeight > 0 && numChannel > 0 && numElements > 0) {
            val bmp = Bitmap.createBitmap(tensorWidth, tensorHeight, Bitmap.Config.ARGB_8888)
            scaledBitmap = bmp
            scaledCanvas = Canvas(bmp)
            dstRect.set(0, 0, tensorWidth, tensorHeight)

            pixels = IntArray(tensorWidth * tensorHeight)

            inputBuffer = ByteBuffer.allocateDirect(3 * tensorWidth * tensorHeight * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            inputFloats = inputBuffer.asFloatBuffer()

            outputBuffer = ByteBuffer.allocateDirect(numChannel * numElements * 4)
            outputBuffer.order(ByteOrder.nativeOrder())
            outputFloats = outputBuffer.asFloatBuffer()
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()

        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply {
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
        if (personIdx < 0 || 4 + personIdx >= numChannel) return

        val bmp = scaledBitmap ?: return
        val canvas = scaledCanvas ?: return

        var inferenceTime = SystemClock.uptimeMillis()

        // Scale the camera frame into the reusable model-sized bitmap.
        srcRect.set(0, 0, frame.width, frame.height)
        canvas.drawBitmap(frame, srcRect, dstRect, scalePaint)

        fillInputBuffer(bmp)

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        val bestBoxes = bestBox()
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes == null || bestBoxes.isEmpty()) {
            // MainActivity.onEmptyDetect() forwards this to the tracker.
            detectorListener.onEmptyDetect()
            return
        }

        detectorListener.onDetect(bestBoxes, inferenceTime)
    }

    // Fills the reusable input buffer in the model's layout (NCHW or NHWC).
    private fun fillInputBuffer(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, tensorWidth, 0, 0, tensorWidth, tensorHeight)

        val plane = tensorWidth * tensorHeight

        if (isNCHW) {
            for (i in 0 until plane) {
                val px = pixels[i]
                inputFloats.put(i, ((px shr 16) and 0xFF) / INPUT_STANDARD_DEVIATION)
                inputFloats.put(plane + i, ((px shr 8) and 0xFF) / INPUT_STANDARD_DEVIATION)
                inputFloats.put(2 * plane + i, (px and 0xFF) / INPUT_STANDARD_DEVIATION)
            }
        } else {
            for (i in 0 until plane) {
                val px = pixels[i]
                val base = 3 * i
                inputFloats.put(base, ((px shr 16) and 0xFF) / INPUT_STANDARD_DEVIATION)
                inputFloats.put(base + 1, ((px shr 8) and 0xFF) / INPUT_STANDARD_DEVIATION)
                inputFloats.put(base + 2, (px and 0xFF) / INPUT_STANDARD_DEVIATION)
            }
        }

        inputBuffer.rewind()
    }

    // Only the "person" channel is read (the old code scanned all classes for
    // every candidate, which was slow and unnecessary).
    private fun bestBox(): List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()
        val personBase = numElements * (4 + personIdx)

        for (c in 0 until numElements) {
            val conf = outputFloats.get(personBase + c)
            if (conf <= CONFIDENCE_THRESHOLD) continue

            // RAW (unclipped) centre and size. Used for TRACKING so a large
            // box that spills past the frame keeps a meaningful centre.
            val rawCx = outputFloats.get(c)
            val rawCy = outputFloats.get(c + numElements)
            val rawW = outputFloats.get(c + numElements * 2)
            val rawH = outputFloats.get(c + numElements * 3)

            // CLIPPED corners, used for drawing, size filters and NMS.
            val x1 = (rawCx - rawW / 2F).coerceIn(0F, 1F)
            val y1 = (rawCy - rawH / 2F).coerceIn(0F, 1F)
            val x2 = (rawCx + rawW / 2F).coerceIn(0F, 1F)
            val y2 = (rawCy + rawH / 2F).coerceIn(0F, 1F)

            val boxW = x2 - x1
            val boxH = y2 - y1
            if (boxW <= 0F || boxH <= 0F) continue

            if (rawCx < ROI_MIN_X || rawCx > ROI_MAX_X) continue
            if (rawCy < ROI_MIN_Y || rawCy > ROI_MAX_Y) continue

            if (boxW < MIN_BOX_WIDTH || boxH < MIN_BOX_HEIGHT) continue

            // Aspect guard skipped for boxes touching the frame edge, since
            // clipping distorts their shape.
            val touchesEdge = x1 <= 0F || y1 <= 0F || x2 >= 1F || y2 >= 1F
            if (!touchesEdge) {
                val aspectRatio = boxW / boxH
                if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) continue
            }

            boundingBoxes.add(
                BoundingBox(
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                    cx = rawCx, cy = rawCy, w = rawW, h = rawH,
                    cnf = conf, cls = personIdx, clsName = "person"
                )
            )
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
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

    // IoU from the clipped corners (raw w/h can exceed the frame).
    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        val union = box1Area + box2Area - intersectionArea
        return if (union > 0F) intersectionArea / union else 0F
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_STANDARD_DEVIATION = 255f

        // Was 0.40F. Measured directly against best.tflite on a real test
        // photo: the model's own max "person" confidence anywhere in the
        // frame was ~0.27, so 0.40F guaranteed zero detections ever passed
        // through, no matter how good the tracking/counting logic below it
        // was. 0.20F leaves some margin under that ceiling. If real jeepney
        // footage shows a lot of false-positive boxes (bags, seats, poles)
        // getting through at this level, raise it back up gradually while
        // watching the live overlay - but don't raise it above what you
        // actually see real people scoring in your own footage.
        private const val CONFIDENCE_THRESHOLD = 0.65F
        private const val IOU_THRESHOLD = 0.55F

        // Was 0.04f each (4% of frame) - small enough to let a hand or
        // partial limb through as a "person". Raised so a box has to be
        // closer to plausible full-person size under a top-mounted view.
        // These are reasoned defaults, not measured against your real
        // footage - if real people near frame edges start getting
        // rejected, ease these back down using the live overlay to see
        // real person box sizes at your camera's actual mount height.
        private const val MIN_BOX_WIDTH = 0.08f
        private const val MIN_BOX_HEIGHT = 0.08f

        private const val MIN_ASPECT_RATIO = 0.35f
        private const val MAX_ASPECT_RATIO = 2.8f
    }
}