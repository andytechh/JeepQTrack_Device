package com.surendramaran.Jeepqs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.surendramaran.Jeepqs.Constants.LABELS_PATH
import com.surendramaran.Jeepqs.Constants.MODEL_PATH
import com.surendramaran.Jeepqs.activities.SetupActivity
import com.surendramaran.Jeepqs.databinding.ActivityMainBinding
import com.surendramaran.Jeepqs.detector.BoundingBox
import com.surendramaran.Jeepqs.detector.Detector
import com.surendramaran.Jeepqs.device.DeviceRole
import com.surendramaran.Jeepqs.managers.GeofenceManager
import com.surendramaran.Jeepqs.managers.PassengerManager
import com.surendramaran.Jeepqs.managers.UploadManager
import com.surendramaran.Jeepqs.services.SMSService
import com.surendramaran.Jeepqs.services.SupabaseService
import com.surendramaran.Jeepqs.services.TripTrackingService
import com.surendramaran.Jeepqs.settings.DeviceConfig
import com.surendramaran.Jeepqs.tracking.GpsTracker

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadManager: UploadManager
    private lateinit var gpsTracker: GpsTracker   // no longer started; GPS runs in TripTrackingService
    private lateinit var smsService: SMSService
    private lateinit var supabase: SupabaseService
    private lateinit var geofenceManager: GeofenceManager

    private val passengerManager = PassengerManager()

    private var terminalId: Int = 1

    // Last counts pushed to geofence/SMS/upload, so empty frames only
    // trigger those when a count actually changed.
    private var lastBoarded = -1
    private var lastExited = -1

    // NEW: true once the counter has been seeded with current_occupancy from
    // Supabase. Until then NOTHING is pushed to the database, otherwise the
    // first camera frame would overwrite the stored occupancy with 0.
    private var occupancyRestored = false

    // ============================================================
    // CAMERA
    // ============================================================

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private var isCameraStarted = false
    private var isCameraStarting = false

    // Rear camera is the default.
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    // Reused every frame instead of allocating two new full-resolution
    // Bitmaps per frame (only reallocated if the camera output size changes).
    private var frameBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var rotatedCanvas: Canvas? = null
    private val rotationPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ============================================================
    // DEVICE
    // ============================================================

    private var role: DeviceRole = DeviceRole.PRIMARY
    private var door: String = "REAR"
    private var currentStatus: String = "inactive"

    // ============================================================
    // JEEPNEY
    // ============================================================

    private var jeepneyIdString: String = "UNKNOWN"
    private var jeepneyPlateNumber: String = "UNKNOWN"
    private var jeepneyName: String = ""
    private var jeepneyDriverName: String = "Driver"
    private var jeepneyCapacity: Int = 24
    private var jeepneyBracket: Int = 1

    // ============================================================
    // ALERT
    // ============================================================

    private var lastAlertTime: Long = 0L
    private val ALERT_INTERVAL = 30000L

    // ============================================================
    // STATUS POLLING
    // ============================================================

    private val statusCheckHandler = Handler(Looper.getMainLooper())

    private val statusCheckRunnable = object : Runnable {
        override fun run() {
            checkStatusFromSupabase()
            statusCheckHandler.postDelayed(this, 10000)
        }
    }

    // ============================================================
    // ACTIVITY
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ocean blue only for the top Android status/camera-cutout area.
        // Navigation bar is intentionally left unchanged.
        window.statusBarColor = ContextCompat.getColor(this, R.color.ocean_primary_dark)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeApp()
        setupUI()
        setupGeofence()
        loadJeepInfo()
        startStatusPolling()
    }

    override fun onResume() {
        super.onResume()

        refreshDeviceConfig()
        updateServicesState()

        // IMPORTANT:
        // Do NOT automatically start the camera here.
        // Camera is intentionally controlled by the START/CLOSE camera button.
    }

    override fun onDestroy() {
        super.onDestroy()

        stopStatusPolling()
        cleanupResources()
    }

    // ============================================================
    // INITIALIZATION
    // ============================================================

    private fun initializeApp() {

        DeviceConfig.init(this)

        supabase = SupabaseService(this)

        smsService = SMSService(this, supabase)

        loadDeviceConfig()

        initializeServices()

        initializeCamera()

        setupListeners()

        setupMenuButtons()

        setupStatsToggle()
    }

    private fun initializeServices() {

        uploadManager = UploadManager(supabase)

        // Kept so the rest of the code compiles, but NOT started anymore:
        // TripTrackingService (foreground service) now sends the GPS.
        gpsTracker = GpsTracker(
            context = this,
            onLocationUpdate = { lat, lng ->
                updateLocationData(lat, lng)
            }
        )
    }

    private fun initializeCamera() {

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            try {
                detector = Detector(
                    baseContext,
                    MODEL_PATH,
                    LABELS_PATH,
                    this
                )
            } catch (_: Exception) {
                // Detector initialization failure is handled by
                // the existing detection callbacks.
            }
        }
    }

    // ============================================================
    // GEOFENCE
    // ============================================================

    private fun setupGeofence() {

        geofenceManager = GeofenceManager(
            context = this,
            supabase = supabase,
            jeepneyId = jeepneyIdString,
            bracket = jeepneyBracket,
            terminalId = terminalId,

            onStatusChanged = { newStatus ->
                currentStatus = newStatus
                updateStatusUI(newStatus)
            }
        )

        // Geofence startup is intentionally delayed until
        // loadJeepInfo() restores the actual jeepney state.
    }

    // ============================================================
    // UI
    // ============================================================

    private fun setupUI() {

        updateDeviceInfoUI()

        updateGpsStatus(false)

        binding.txtGpsStatus.text = "GPS: Secondary Device"

        binding.txtJeepStatus.text = "Inactive"

        binding.capacityLabel.text = "/ $jeepneyCapacity"

        binding.capacityText.text = "$jeepneyCapacity passengers"

        binding.txtAvailable.text = jeepneyCapacity.toString()

        updateCameraUI()

        // Passenger details are collapsed by default.
        binding.extraControlsCard.visibility = View.GONE
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private fun setupListeners() {

        // GPU / CPU
        binding.isGpu.setOnCheckedChangeListener { _, isChecked ->
            cameraExecutor.submit {
                detector?.restart(isGpu = isChecked)
            }
        }

        // CAMERA TOGGLE
        binding.btnCameraToggle.setOnClickListener {
            if (isCameraStarted) {
                stopCamera()
            } else {
                startCameraIfPermitted()
            }
        }

        // ROTATE CAMERA
        binding.btnRotateCamera.setOnClickListener {
            rotateCamera()
        }
    }

    // ============================================================
    // MENU
    // ============================================================

    private fun setupMenuButtons() {
        binding.btnLogout.setOnClickListener {
            performLogout()
        }
    }

    // ============================================================
    // PASSENGER DETAILS TOGGLE
    // ============================================================

    private fun setupStatsToggle() {

        var expanded = false

        binding.btnToggleMore.setOnClickListener {

            expanded = !expanded

            binding.extraControlsCard.visibility =
                if (expanded) View.VISIBLE else View.GONE

            binding.btnToggleMore.text =
                if (expanded) "⌃" else "⌄"
        }

        binding.extraControlsCard.visibility = View.GONE
    }

    // ============================================================
    // DEVICE CONFIG
    // ============================================================

    private fun loadDeviceConfig() {

        val savedRole = DeviceConfig.getRole()

        role =
            if (savedRole == "SECONDARY") DeviceRole.SECONDARY
            else DeviceRole.PRIMARY

        door = DeviceConfig.getDoor() ?: "REAR"

        jeepneyIdString = DeviceConfig.getJeepId() ?: "UNKNOWN"

        terminalId = DeviceConfig.getTerminalId() ?: 1

        jeepneyBracket = DeviceConfig.getBracket() ?: 1
    }

    private fun refreshDeviceConfig() {
        loadDeviceConfig()
        updateDeviceInfoUI()
    }

    private fun updateDeviceInfoUI() {

        val roleStr = DeviceConfig.getRole() ?: "PRIMARY"

        val doorStr = DeviceConfig.getDoor() ?: "REAR"

        if (roleStr == "PRIMARY") {

            binding.roleText.text = "PRIMARY"

            binding.roleText.setTextColor(
                ContextCompat.getColor(this, R.color.ocean_primary_dark)
            )

            binding.roleText.setBackgroundResource(R.drawable.bg_clay_pill_blue)

        } else {

            binding.roleText.text = "SECONDARY"

            binding.roleText.setTextColor(
                ContextCompat.getColor(this, R.color.secondary_role)
            )

            binding.roleText.setBackgroundResource(R.drawable.bg_clay_pill_orange)
        }

        binding.doorText.text =
            if (doorStr == "FRONT") "Front Door" else "Rear Door"
    }

    // ============================================================
    // SERVICES
    // ============================================================

    private fun updateServicesState() {

        if (role == DeviceRole.PRIMARY) {

            startGpsTracking()

            geofenceManager.startGeofence()

        } else {

            // CHANGED: stop the foreground GPS service instead of gpsTracker.
            stopService(Intent(this, TripTrackingService::class.java))

            geofenceManager.stopGeofence()

            updateGpsStatus(false)

            binding.txtGpsStatus.text = "GPS: Secondary Device"
        }
    }

    // ============================================================
    // JEEP INFO
    // ============================================================

    // CHANGED: `attempt` added so we can retry when there is no signal.
    private fun loadJeepInfo(attempt: Int = 0) {

        val currentId = DeviceConfig.getJeepId() ?: return

        supabase.getJeepneyWithDriver { info ->

            runOnUiThread {

                if (info != null) {

                    jeepneyIdString = currentId

                    jeepneyPlateNumber = info.plateNumber

                    jeepneyName = info.jeepName

                    jeepneyDriverName = info.driverName

                    jeepneyCapacity = info.capacity

                    jeepneyBracket = info.bracket

                    val displayName =
                        if (info.jeepName.isNotEmpty()) {
                            "${info.jeepName} (${info.plateNumber})"
                        } else {
                            info.plateNumber
                        }

                    binding.txtJeepName.text = displayName

                    binding.capacityLabel.text = "/ $jeepneyCapacity"

                    binding.capacityText.text = "$jeepneyCapacity passengers"

                    binding.txtAvailable.text = jeepneyCapacity.toString()

                    geofenceManager.updateJeepneyData(
                        plate = info.plateNumber,
                        jeepName = info.jeepName,
                        driver = info.driverName,
                        occupancy = info.occupancy
                    )

                    geofenceManager.updateBracket(info.bracket)

                    geofenceManager.syncState(
                        status = info.status,
                        terminalId = info.terminalId,
                        loadingStartedAtMillis = info.loadingStartedAtMillis
                    )

                    // NEW: seed the counter from the database, ONCE only.
                    // Restoring twice would double count, because after uploads
                    // resume the database already contains the local counts.
                    if (!occupancyRestored) {
                        passengerManager.restoreOccupancy(info.occupancy)
                        occupancyRestored = true

                        binding.txtInside.text = info.occupancy.toString()
                        binding.txtAvailable.text =
                            (jeepneyCapacity - info.occupancy).coerceAtLeast(0).toString()
                        updateOccupancyProgress(info.occupancy)
                    }

                } else {

                    binding.txtJeepName.text = "Unknown Jeep"

                    // NEW: no signal / request failed -> retry. The database is
                    // not written to until the restore succeeds.
                    if (!occupancyRestored && attempt < 10) {
                        Handler(Looper.getMainLooper()).postDelayed(
                            { loadJeepInfo(attempt + 1) },
                            3_000
                        )
                    }
                }

                // CHANGED: only on the first call, not on every retry.
                if (attempt == 0) {
                    geofenceManager.startGeofence()
                }
            }
        }
    }

    // ============================================================
    // GPS
    // ============================================================

    // CHANGED: GPS now runs in TripTrackingService (foreground service), so it
    // keeps sending with the screen off or the app in the background.
    private fun startGpsTracking() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

            // Android 13+: needed to show the "Sending GPS" notification.
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            ActivityCompat.requestPermissions(
                this,
                perms.toTypedArray(),
                REQUEST_LOCATION_PERMISSION
            )

            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, TripTrackingService::class.java)
                .setAction(TripTrackingService.ACTION_START)
        )

        updateGpsStatus(true)
    }

    private fun updateGpsStatus(isTracking: Boolean) {

        runOnUiThread {

            binding.txtGpsStatus.text =
                if (isTracking) "GPS: Active" else "GPS: Off"

            binding.txtGpsStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isTracking) R.color.green else R.color.red
                )
            )
        }
    }

    // Only used by the old GpsTracker, which is no longer started.
    private fun updateLocationData(lat: Double, lng: Double) {

        supabase.updateGps(lat, lng) { }

        supabase.sendGpsTracking(lat, lng, 0.0, 0.0) { }
    }

    // ============================================================
    // CAMERA PERMISSION
    // ============================================================

    private fun startCameraIfPermitted() {

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    // ============================================================
    // START CAMERA
    // ============================================================

    private fun startCamera() {

        if (isCameraStarted || isCameraStarting) {
            return
        }

        isCameraStarting = true

        binding.btnCameraToggle.isEnabled = false

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            try {

                cameraProvider = cameraProviderFuture.get()

                bindCameraUseCases()

                isCameraStarted = true

                // NEW: detection stops when the screen turns off, so keep it on
                // while the camera is counting.
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                isCameraStarting = false

                binding.btnCameraToggle.isEnabled = true

                updateCameraUI()

            } catch (e: Exception) {

                isCameraStarting = false

                binding.btnCameraToggle.isEnabled = true

                updateCameraUI()

                Toast.makeText(
                    this,
                    "Unable to start camera",
                    Toast.LENGTH_LONG
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ============================================================
    // CLOSE CAMERA
    // ============================================================

    private fun stopCamera() {

        try {

            cameraProvider?.unbindAll()

            preview = null
            imageAnalyzer = null
            camera = null

            frameBitmap = null
            rotatedBitmap = null
            rotatedCanvas = null

            isCameraStarted = false
            isCameraStarting = false

            // NEW: let the screen sleep again.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            binding.overlay.clear()

            updateCameraUI()

        } catch (_: Exception) {

            isCameraStarted = false
            isCameraStarting = false

            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            updateCameraUI()
        }
    }

    // ============================================================
    // ROTATE CAMERA
    // ============================================================

    private fun rotateCamera() {

        cameraFacing =
            if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        updateCameraFacingUI()

        // If the camera is already running, immediately rebind
        // CameraX using the new lens.
        if (isCameraStarted) {
            try {
                bindCameraUseCases()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Unable to rotate camera",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // CAMERA UI
    // ============================================================

    private fun updateCameraUI() {

        if (isCameraStarted) {

            binding.btnCameraToggle.text = "■  CLOSE CAMERA"

            binding.btnCameraToggle.setBackgroundResource(R.drawable.bg_clay_button_red)

            binding.btnCameraToggle.setTextColor(
                ContextCompat.getColor(this, R.color.red_dark)
            )

            binding.cameraStatusText.text = "CAMERA ACTIVE"

            binding.cameraStatusText.setTextColor(
                ContextCompat.getColor(this, R.color.status_green_dark)
            )

            binding.cameraStatusDot.setBackgroundResource(R.drawable.bg_dot_green)

        } else {

            binding.btnCameraToggle.text = "▶  START CAMERA"

            binding.btnCameraToggle.setBackgroundResource(R.drawable.bg_clay_button_green)

            binding.btnCameraToggle.setTextColor(
                ContextCompat.getColor(this, R.color.status_green_dark)
            )

            binding.cameraStatusText.text = "CAMERA OFF"

            binding.cameraStatusText.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )

            binding.cameraStatusDot.setBackgroundResource(R.drawable.bg_dot_gray)
        }

        updateCameraFacingUI()
    }

    private fun updateCameraFacingUI() {

        binding.cameraFacingText.text =
            if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                "REAR CAMERA"
            } else {
                "FRONT CAMERA"
            }
    }

    // ============================================================
    // CAMERA USE CASES
    // ============================================================

    private fun bindCameraUseCases() {

        val provider =
            cameraProvider
                ?: throw IllegalStateException("Camera not initialized")

        // NOTE: NOT binding.viewFinder.display.rotation. The UI is locked
        // to portrait (see AndroidManifest.xml), so display.rotation
        // always reports that fixed orientation regardless of how the
        // phone is physically mounted.
        //
        // The camera is now mounted top-down (flat), not sideways, so
        // there is no fixed mount correction to layer on top of what
        // CameraX already computes from the sensor - we tell it the
        // target surface is natural/portrait orientation directly.
        //
        // If the phone is ever remounted sideways again: watch the live
        // preview in binding.viewFinder while trying ROTATION_90 / 180 /
        // 270 here until a person walking through the door appears
        // upright and moves the expected direction on screen.
        val rotation = android.view.Surface.ROTATION_0

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(cameraFacing)
                .build()

        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            analyzeImage(imageProxy)
        }

        provider.unbindAll()

        camera =
            provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    // ============================================================
    // IMAGE ANALYSIS
    // ============================================================

    private fun analyzeImage(imageProxy: androidx.camera.core.ImageProxy) {

        try {

            val srcW = imageProxy.width
            val srcH = imageProxy.height
            val rotationDeg = imageProxy.imageInfo.rotationDegrees
            val mirror = cameraFacing == CameraSelector.LENS_FACING_FRONT

            // Rotating 90/180... degrees swaps which dimension is width
            // vs height in the final upright image.
            val swapDims = rotationDeg == 90 || rotationDeg == 270
            val dstW = if (swapDims) srcH else srcW
            val dstH = if (swapDims) srcW else srcH

            if (frameBitmap == null ||
                frameBitmap!!.width != srcW ||
                frameBitmap!!.height != srcH
            ) {
                frameBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            }
            val bitmapBuffer = frameBitmap!!

            // NOTE: do NOT wrap this in imageProxy.use{} - that closes
            // imageProxy immediately, but imageInfo.rotationDegrees and
            // width are still read below. Closing early made every frame
            // throw on that later access, silently swallowed by the
            // catch below, so detector.detect() was never reached and
            // the UI never updated. The finally block already closes
            // imageProxy exactly once, after everything here is done.
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            if (rotatedBitmap == null ||
                rotatedBitmap!!.width != dstW ||
                rotatedBitmap!!.height != dstH
            ) {
                rotatedBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                rotatedCanvas = Canvas(rotatedBitmap!!)
            }
            val canvas = rotatedCanvas!!
            val outBitmap = rotatedBitmap!!

            // Rotate (and mirror, front camera only) about the source
            // centre, then move the result to the destination centre -
            // drawn into a reused Bitmap instead of allocating a new one
            // every frame.
            val matrix = Matrix().apply {
                postTranslate(-srcW / 2f, -srcH / 2f)
                if (mirror) {
                    postScale(-1f, 1f)
                }
                postRotate(rotationDeg.toFloat())
                postTranslate(dstW / 2f, dstH / 2f)
            }

            canvas.drawBitmap(bitmapBuffer, matrix, rotationPaint)

            detector?.detect(outBitmap)

        } catch (_: Exception) {

        } finally {

            imageProxy.close()
        }
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================

    private fun allPermissionsGranted(): Boolean {

        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                baseContext,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {

            REQUEST_LOCATION_PERMISSION -> {

                // Location is requested first, so grantResults[0] is location.
                // If the notification permission is denied the service still
                // runs; only its notification is hidden.
                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startGpsTracking()
                }
            }

            REQUEST_CODE_PERMISSIONS -> {

                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startCameraIfPermitted()
                }
            }
        }
    }

    // ============================================================
    // YOLO DETECTOR CALLBACKS
    // ============================================================

    override fun onEmptyDetect() {

        // Empty frames MUST still go through the tracker (ages/prunes
        // tracks and lets the counter finish a crossing) AND still
        // refresh the UI.
        val data = passengerManager.processDetections(emptyList(), door)

        applyPassengerData(
            data = data,
            inferenceTime = null,
            fromDetection = false
        )
    }

    override fun onDetect(
        boundingBoxes: List<BoundingBox>,
        inferenceTime: Long
    ) {

        val data = passengerManager.processDetections(boundingBoxes, door)

        applyPassengerData(
            data = data,
            inferenceTime = inferenceTime,
            fromDetection = true
        )
    }

    private fun applyPassengerData(
        data: PassengerManager.PassengerData,
        inferenceTime: Long?,
        fromDetection: Boolean
    ) {

        runOnUiThread {

            inferenceTime?.let {
                binding.inferenceTime.text = "${it}ms / frame"
            }

            if (data.trackedBoxes.isEmpty()) {
                binding.overlay.clear()
            } else {
                binding.overlay.setResults(data.trackedBoxes)
            }

            binding.txtInside.text = data.inside.toString()

            binding.txtBoarded.text = data.boarded.toString()

            binding.txtExited.text = data.exited.toString()

            binding.txtAvailable.text =
                (jeepneyCapacity - data.inside)
                    .coerceAtLeast(0)
                    .toString()

            updateOccupancyProgress(data.inside)
        }

        // NEW: do not push anything to geofence / SMS / Supabase until the
        // stored occupancy has been restored, or we would overwrite it with
        // a count that starts at 0. Counting itself continues normally, and
        // the restored offset is added once it arrives.
        if (!occupancyRestored) return

        // Push to geofence / SMS alert / Supabase whenever a count
        // changed, and keep the every-detection behaviour for real
        // detections.
        val changed =
            data.boarded != lastBoarded ||
                    data.exited != lastExited

        lastBoarded = data.boarded
        lastExited = data.exited

        if (changed || fromDetection) {

            geofenceManager.updateOccupancy(data.inside)

            checkOccupancyAlert(data.inside)

            uploadManager.uploadPassengerData(
                data,
                role,
                door,
                currentStatus
            )
        }
    }

    // ============================================================
    // OCCUPANCY UI
    // ============================================================

    private fun updateOccupancyProgress(occupancy: Int) {

        val capacity = jeepneyCapacity.coerceAtLeast(1)

        val percentage =
            (occupancy.toFloat() / capacity.toFloat()).coerceIn(0f, 1f)

        val parent = binding.occupancyProgress.parent

        if (parent is android.view.ViewGroup) {

            val availableWidth =
                parent.width - parent.paddingLeft - parent.paddingRight

            if (availableWidth > 0) {

                val params = binding.occupancyProgress.layoutParams

                params.width = (availableWidth * percentage).toInt()

                binding.occupancyProgress.layoutParams = params
            }
        }

        binding.occupancyStateText.text =
            when {
                percentage >= 1f -> "FULL"
                percentage >= 0.8f -> "NEAR FULL"
                else -> "REAL-TIME"
            }
    }

    // ============================================================
    // OCCUPANCY ALERT
    // ============================================================

    private fun checkOccupancyAlert(total: Int) {

        val capacity = jeepneyCapacity

        val percent =
            if (capacity > 0) (total * 100) / capacity else 0

        if (percent >= 80) {

            val now = System.currentTimeMillis()

            if (now - lastAlertTime > ALERT_INTERVAL) {

                lastAlertTime = now

                val dispatcherPhone = "+639123456789"

                smsService.sendOccupancyAlert(
                    jeepneyId = jeepneyIdString,
                    plateNumber = jeepneyPlateNumber,
                    jeepName = jeepneyName,
                    driverName = jeepneyDriverName,
                    occupancy = total,
                    capacity = capacity,
                    phoneNumber = dispatcherPhone
                )
            }
        }
    }

    // ============================================================
    // STATUS POLLING
    // ============================================================

    private fun checkStatusFromSupabase() {

        if (jeepneyIdString == "UNKNOWN") {
            return
        }

        supabase.getCurrentStatus(jeepneyIdString) { status ->

            if (
                status != null &&
                status != currentStatus
            ) {

                currentStatus = status

                runOnUiThread {
                    updateStatusUI(status)
                }
            }
        }
    }

    private fun startStatusPolling() {
        statusCheckHandler.post(statusCheckRunnable)
    }

    private fun stopStatusPolling() {
        statusCheckHandler.removeCallbacks(statusCheckRunnable)
    }

    private fun updateStatusUI(status: String) {

        runOnUiThread {

            val statusText =
                when (status) {
                    "waiting" -> "Waiting"
                    "loading" -> "Loading..."
                    "en_route" -> "En Route"
                    "arrived" -> "Arrived"
                    "dispatched" -> "Dispatched"
                    "inactive" -> "Inactive"
                    else -> "Inactive"
                }

            binding.txtJeepStatus.text = statusText
        }
    }

    // ============================================================
    // LOGOUT
    // ============================================================

    private fun performLogout() {

        if (isCameraStarted) {
            stopCamera()
        }

        // NEW: stop background GPS when the device is logged out / reset.
        stopService(Intent(this, TripTrackingService::class.java))

        DeviceConfig.clearConfig()

        startActivity(Intent(this, SetupActivity::class.java))

        finish()
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    private fun cleanupResources() {

        try {
            detector?.close()
        } catch (_: Exception) {
        }

        // CHANGED: gpsTracker.stopTracking() removed on purpose. GPS is in
        // TripTrackingService now and must keep running after the activity is
        // destroyed (app swiped away). It stops on logout / SECONDARY role.

        // NOTE: this removes the geofence when the activity is destroyed. If
        // arrival/departure detection should also keep working with the app
        // closed, remove this call and stop the geofence in performLogout()
        // instead.
        try {
            geofenceManager.stopGeofence()
        } catch (_: Exception) {
        }

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // CONSTANTS
    // ============================================================

    companion object {

        private const val REQUEST_CODE_PERMISSIONS = 10

        private const val REQUEST_LOCATION_PERMISSION = 1001

        private val REQUIRED_PERMISSIONS =
            arrayOf(
                Manifest.permission.CAMERA
            )
    }
}