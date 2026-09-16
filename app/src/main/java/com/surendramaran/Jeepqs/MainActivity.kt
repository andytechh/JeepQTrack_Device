package com.surendramaran.Jeepqs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
import com.surendramaran.Jeepqs.settings.DeviceConfig
import com.surendramaran.Jeepqs.tracking.GpsTracker

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadManager: UploadManager
    private lateinit var gpsTracker: GpsTracker
    private lateinit var smsService: SMSService
    private lateinit var supabase: SupabaseService
    private lateinit var geofenceManager: GeofenceManager

    private val passengerManager = PassengerManager()

    private var terminalId: Int = 1

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
        //
        // Camera is intentionally controlled by the START/CLOSE
        // camera button.
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

        smsService = SMSService(
            this,
            supabase
        )

        loadDeviceConfig()

        initializeServices()

        initializeCamera()

        setupListeners()

        setupMenuButtons()

        setupStatsToggle()
    }

    private fun initializeServices() {

        uploadManager = UploadManager(
            supabase
        )

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

        /*
         * Geofence startup is intentionally delayed until
         * loadJeepInfo() restores the actual jeepney state.
         */
    }

    // ============================================================
    // UI
    // ============================================================

    private fun setupUI() {

        updateDeviceInfoUI()

        updateGpsStatus(false)

        binding.txtGpsStatus.text =
            "GPS: Secondary Device"

        binding.txtJeepStatus.text =
            "Inactive"

        binding.capacityLabel.text =
            "/ $jeepneyCapacity"

        binding.capacityText.text =
            "$jeepneyCapacity passengers"

        binding.txtAvailable.text =
            jeepneyCapacity.toString()

        updateCameraUI()

        // Passenger details are collapsed by default.
        binding.extraControlsCard.visibility =
            View.GONE
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private fun setupListeners() {

        // --------------------------------------------------------
        // GPU / CPU
        // --------------------------------------------------------

        binding.isGpu.setOnCheckedChangeListener { _, isChecked ->

            cameraExecutor.submit {

                detector?.restart(
                    isGpu = isChecked
                )
            }
        }

        // --------------------------------------------------------
        // CAMERA TOGGLE
        // --------------------------------------------------------

        binding.btnCameraToggle.setOnClickListener {

            if (isCameraStarted) {

                stopCamera()

            } else {

                startCameraIfPermitted()
            }
        }

        // --------------------------------------------------------
        // ROTATE CAMERA
        // --------------------------------------------------------

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
                if (expanded) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

            binding.btnToggleMore.text =
                if (expanded) {
                    "⌃"
                } else {
                    "⌄"
                }
        }

        binding.extraControlsCard.visibility =
            View.GONE
    }

    // ============================================================
    // DEVICE CONFIG
    // ============================================================

    private fun loadDeviceConfig() {

        val savedRole = DeviceConfig.getRole()

        role =
            if (savedRole == "SECONDARY") {
                DeviceRole.SECONDARY
            } else {
                DeviceRole.PRIMARY
            }

        door =
            DeviceConfig.getDoor()
                ?: "REAR"

        jeepneyIdString =
            DeviceConfig.getJeepId()
                ?: "UNKNOWN"

        terminalId =
            DeviceConfig.getTerminalId()
                ?: 1

        jeepneyBracket =
            DeviceConfig.getBracket()
                ?: 1
    }

    private fun refreshDeviceConfig() {

        loadDeviceConfig()

        updateDeviceInfoUI()
    }

    private fun updateDeviceInfoUI() {

        val roleStr =
            DeviceConfig.getRole()
                ?: "PRIMARY"

        val doorStr =
            DeviceConfig.getDoor()
                ?: "REAR"

        if (roleStr == "PRIMARY") {

            binding.roleText.text =
                "PRIMARY"

            binding.roleText.setTextColor(
                ContextCompat.getColor(
                    this,
                    R.color.ocean_primary_dark
                )
            )

            binding.roleText.setBackgroundResource(
                R.drawable.bg_clay_pill_blue
            )

        } else {

            binding.roleText.text =
                "SECONDARY"

            binding.roleText.setTextColor(
                ContextCompat.getColor(
                    this,
                    R.color.secondary_role
                )
            )

            binding.roleText.setBackgroundResource(
                R.drawable.bg_clay_pill_orange
            )
        }

        binding.doorText.text =
            if (doorStr == "FRONT") {
                "Front Door"
            } else {
                "Rear Door"
            }
    }

    // ============================================================
    // SERVICES
    // ============================================================

    private fun updateServicesState() {

        if (role == DeviceRole.PRIMARY) {

            startGpsTracking()

            geofenceManager.startGeofence()

        } else {

            gpsTracker.stopTracking()

            geofenceManager.stopGeofence()

            updateGpsStatus(false)

            binding.txtGpsStatus.text =
                "GPS: Secondary Device"
        }
    }

    // ============================================================
    // JEEP INFO
    // ============================================================

    private fun loadJeepInfo() {

        val currentId =
            DeviceConfig.getJeepId()
                ?: return

        supabase.getJeepneyWithDriver { info ->

            runOnUiThread {

                if (info != null) {

                    jeepneyIdString =
                        currentId

                    jeepneyPlateNumber =
                        info.plateNumber

                    jeepneyName =
                        info.jeepName

                    jeepneyDriverName =
                        info.driverName

                    jeepneyCapacity =
                        info.capacity

                    jeepneyBracket =
                        info.bracket

                    val displayName =
                        if (info.jeepName.isNotEmpty()) {
                            "${info.jeepName} (${info.plateNumber})"
                        } else {
                            info.plateNumber
                        }

                    binding.txtJeepName.text =
                        displayName

                    binding.capacityLabel.text =
                        "/ $jeepneyCapacity"

                    binding.capacityText.text =
                        "$jeepneyCapacity passengers"

                    binding.txtAvailable.text =
                        jeepneyCapacity.toString()

                    geofenceManager.updateJeepneyData(
                        plate = info.plateNumber,
                        jeepName = info.jeepName,
                        driver = info.driverName,
                        occupancy = info.occupancy
                    )

                    geofenceManager.updateBracket(
                        info.bracket
                    )

                    geofenceManager.syncState(
                        status = info.status,
                        terminalId = info.terminalId,
                        loadingStartedAtMillis =
                            info.loadingStartedAtMillis
                    )

                } else {

                    binding.txtJeepName.text =
                        "Unknown Jeep"
                }

                geofenceManager.startGeofence()
            }
        }
    }

    // ============================================================
    // GPS
    // ============================================================

    private fun startGpsTracking() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )

            return
        }

        gpsTracker.startTracking()

        updateGpsStatus(true)
    }

    private fun updateGpsStatus(
        isTracking: Boolean
    ) {

        runOnUiThread {

            binding.txtGpsStatus.text =
                if (isTracking) {
                    "GPS: Active"
                } else {
                    "GPS: Off"
                }

            binding.txtGpsStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isTracking) {
                        R.color.green
                    } else {
                        R.color.red
                    }
                )
            )
        }
    }

    private fun updateLocationData(
        lat: Double,
        lng: Double
    ) {

        supabase.updateGps(
            lat,
            lng
        ) { }

        supabase.sendGpsTracking(
            lat,
            lng,
            0.0,
            0.0
        ) { }
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

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            try {

                cameraProvider =
                    cameraProviderFuture.get()

                bindCameraUseCases()

                isCameraStarted = true

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

            isCameraStarted = false
            isCameraStarting = false

            binding.overlay.clear()

            updateCameraUI()

        } catch (_: Exception) {

            isCameraStarted = false
            isCameraStarting = false

            updateCameraUI()
        }
    }

    // ============================================================
    // ROTATE CAMERA
    // ============================================================

    private fun rotateCamera() {

        cameraFacing =
            if (
                cameraFacing ==
                CameraSelector.LENS_FACING_BACK
            ) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        updateCameraFacingUI()

        /*
         * If the camera is already running, immediately
         * rebind CameraX using the new lens.
         */
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

            binding.btnCameraToggle.text =
                "■  CLOSE CAMERA"

            binding.btnCameraToggle.setBackgroundResource(
                R.drawable.bg_clay_button_red
            )

            binding.btnCameraToggle.setTextColor(
                ContextCompat.getColor(
                    this,
                    R.color.red_dark
                )
            )

            binding.cameraStatusText.text =
                "CAMERA ACTIVE"

            binding.cameraStatusText.setTextColor(
                ContextCompat.getColor(
                    this,
                    R.color.status_green_dark
                )
            )

            binding.cameraStatusDot.setBackgroundResource(
                R.drawable.bg_dot_green
            )

        } else {

            binding.btnCameraToggle.text =
                "▶  START CAMERA"

            binding.btnCameraToggle.setBackgroundResource(
                R.drawable.bg_clay_button_green
            )

            binding.btnCameraToggle.setTextColor(
                ContextCompat.getColor(
                    this,
                    R.color.status_green_dark
                )
            )

            binding.cameraStatusText.text =
                "CAMERA OFF"

            binding.cameraStatusText.setTextColor(
                ContextCompat.getColor(
                    this,
                    R.color.text_secondary
                )
            )

            binding.cameraStatusDot.setBackgroundResource(
                R.drawable.bg_dot_gray
            )
        }

        updateCameraFacingUI()
    }

    private fun updateCameraFacingUI() {

        binding.cameraFacingText.text =
            if (
                cameraFacing ==
                CameraSelector.LENS_FACING_BACK
            ) {
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
                ?: throw IllegalStateException(
                    "Camera not initialized"
                )

        val rotation =
            binding.viewFinder.display.rotation

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(cameraFacing)
                .build()

        preview =
            Preview.Builder()
                .setTargetAspectRatio(
                    AspectRatio.RATIO_4_3
                )
                .setTargetRotation(rotation)
                .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(
                    AspectRatio.RATIO_4_3
                )
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setTargetRotation(rotation)
                .setOutputImageFormat(
                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                )
                .build()

        imageAnalyzer?.setAnalyzer(
            cameraExecutor
        ) { imageProxy ->

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

        preview?.setSurfaceProvider(
            binding.viewFinder.surfaceProvider
        )
    }

    // ============================================================
    // IMAGE ANALYSIS
    // ============================================================

    private fun analyzeImage(
        imageProxy: androidx.camera.core.ImageProxy
    ) {

        try {

            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )

            imageProxy.use {

                bitmapBuffer.copyPixelsFromBuffer(
                    imageProxy.planes[0].buffer
                )
            }

            val matrix =
                Matrix().apply {

                    postRotate(
                        imageProxy.imageInfo.rotationDegrees.toFloat()
                    )

                    /*
                     * Only mirror the front camera.
                     * Rear camera remains unmirrored.
                     */
                    if (
                        cameraFacing ==
                        CameraSelector.LENS_FACING_FRONT
                    ) {

                        postScale(
                            -1f,
                            1f,
                            imageProxy.width.toFloat(),
                            imageProxy.height.toFloat()
                        )
                    }
                }

            val rotatedBitmap =
                Bitmap.createBitmap(
                    bitmapBuffer,
                    0,
                    0,
                    bitmapBuffer.width,
                    bitmapBuffer.height,
                    matrix,
                    true
                )

            detector?.detect(
                rotatedBitmap
            )

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

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {

            REQUEST_LOCATION_PERMISSION -> {

                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] ==
                    PackageManager.PERMISSION_GRANTED
                ) {

                    startGpsTracking()
                }
            }

            REQUEST_CODE_PERMISSIONS -> {

                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] ==
                    PackageManager.PERMISSION_GRANTED
                ) {

                    startCameraIfPermitted()
                }
            }
        }
    }

    // ============================================================
    // YOLO DETECTOR CALLBACK
    // ============================================================

    override fun onEmptyDetect() {

        runOnUiThread {

            binding.overlay.clear()
        }
    }

    override fun onDetect(
        boundingBoxes: List<BoundingBox>,
        inferenceTime: Long
    ) {

        val data =
            passengerManager.processDetections(
                boundingBoxes,
                door
            )

        runOnUiThread {

            binding.inferenceTime.text =
                "${inferenceTime}ms / frame"

            binding.overlay.setResults(
                data.trackedBoxes
            )

            binding.txtInside.text =
                data.inside.toString()

            binding.txtBoarded.text =
                data.boarded.toString()

            binding.txtExited.text =
                data.exited.toString()

            val available =
                (jeepneyCapacity - data.inside)
                    .coerceAtLeast(0)

            binding.txtAvailable.text =
                available.toString()

            updateOccupancyProgress(
                data.inside
            )
        }

        geofenceManager.updateOccupancy(
            data.inside
        )

        checkOccupancyAlert(
            data.inside
        )

        uploadManager.uploadPassengerData(
            data,
            role,
            door,
            currentStatus
        )
    }

    // ============================================================
    // OCCUPANCY UI
    // ============================================================

    private fun updateOccupancyProgress(
        occupancy: Int
    ) {

        val capacity =
            jeepneyCapacity.coerceAtLeast(1)

        val percentage =
            (
                    occupancy.toFloat() /
                            capacity.toFloat()
                    ).coerceIn(
                    0f,
                    1f
                )

        val parent =
            binding.occupancyProgress.parent

        if (parent is android.view.ViewGroup) {

            val availableWidth =
                parent.width -
                        parent.paddingLeft -
                        parent.paddingRight

            if (availableWidth > 0) {

                val params =
                    binding.occupancyProgress.layoutParams

                params.width =
                    (
                            availableWidth *
                                    percentage
                            ).toInt()

                binding.occupancyProgress.layoutParams =
                    params
            }
        }

        binding.occupancyStateText.text =
            when {

                percentage >= 1f ->
                    "FULL"

                percentage >= 0.8f ->
                    "NEAR FULL"

                else ->
                    "REAL-TIME"
            }
    }

    // ============================================================
    // OCCUPANCY ALERT
    // ============================================================

    private fun checkOccupancyAlert(
        total: Int
    ) {

        val capacity =
            jeepneyCapacity

        val percent =
            if (capacity > 0) {
                (total * 100) / capacity
            } else {
                0
            }

        if (percent >= 80) {

            val now =
                System.currentTimeMillis()

            if (
                now - lastAlertTime >
                ALERT_INTERVAL
            ) {

                lastAlertTime =
                    now

                val dispatcherPhone =
                    "+639123456789"

                smsService.sendOccupancyAlert(

                    jeepneyId =
                        jeepneyIdString,

                    plateNumber =
                        jeepneyPlateNumber,

                    jeepName =
                        jeepneyName,

                    driverName =
                        jeepneyDriverName,

                    occupancy =
                        total,

                    capacity =
                        capacity,

                    phoneNumber =
                        dispatcherPhone
                )
            }
        }
    }

    // ============================================================
    // STATUS POLLING
    // ============================================================

    private fun checkStatusFromSupabase() {

        if (
            jeepneyIdString ==
            "UNKNOWN"
        ) {
            return
        }

        supabase.getCurrentStatus(
            jeepneyIdString
        ) { status ->

            if (
                status != null &&
                status != currentStatus
            ) {

                currentStatus =
                    status

                runOnUiThread {

                    updateStatusUI(
                        status
                    )
                }
            }
        }
    }

    private fun startStatusPolling() {

        statusCheckHandler.post(
            statusCheckRunnable
        )
    }

    private fun stopStatusPolling() {

        statusCheckHandler.removeCallbacks(
            statusCheckRunnable
        )
    }

    private fun updateStatusUI(
        status: String
    ) {

        runOnUiThread {

            val statusText =
                when (status) {

                    "waiting" ->
                        "Waiting"

                    "loading" ->
                        "Loading..."

                    "en_route" ->
                        "En Route"

                    "arrived" ->
                        "Arrived"

                    "dispatched" ->
                        "Dispatched"

                    "inactive" ->
                        "Inactive"

                    else ->
                        "Inactive"
                }

            binding.txtJeepStatus.text =
                statusText
        }
    }

    // ============================================================
    // LOGOUT
    // ============================================================

    private fun performLogout() {

        if (isCameraStarted) {
            stopCamera()
        }

        DeviceConfig.clearConfig()

        startActivity(
            Intent(
                this,
                SetupActivity::class.java
            )
        )

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

        try {
            gpsTracker.stopTracking()
        } catch (_: Exception) {
        }

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

        private const val REQUEST_CODE_PERMISSIONS =
            10

        private const val REQUEST_LOCATION_PERMISSION =
            1001

        private val REQUIRED_PERMISSIONS =
            arrayOf(
                Manifest.permission.CAMERA
            )
    }
}