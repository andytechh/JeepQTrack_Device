package com.surendramaran.Jeepqs

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.surendramaran.Jeepqs.databinding.ActivityMainBinding
import com.surendramaran.Jeepqs.detector.BoundingBox
import com.surendramaran.Jeepqs.detector.Detector
import com.surendramaran.Jeepqs.device.DeviceRole
import com.surendramaran.Jeepqs.managers.GeofenceManager
import com.surendramaran.Jeepqs.managers.PassengerManager
import com.surendramaran.Jeepqs.managers.UploadManager
import com.surendramaran.Jeepqs.tracking.GpsTracker
import com.surendramaran.Jeepqs.services.SMSService
import com.surendramaran.Jeepqs.services.SupabaseService
import com.surendramaran.Jeepqs.settings.DeviceConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    // ============================================
    // LATEINIT & VARIABLES
    // ============================================
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadManager: UploadManager
    private lateinit var gpsTracker: GpsTracker
    private lateinit var smsService: SMSService
    private lateinit var supabase: SupabaseService
    private var terminalId: Int = 1

    private val passengerManager = PassengerManager()
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var role: DeviceRole = DeviceRole.PRIMARY
    private var door: String = "REAR"
    private var isCameraStarted = false

    private var currentStatus: String = "inactive"
    private lateinit var geofenceManager: GeofenceManager

    // ============================================
    // JEEPNEY DATA - Store locally
    // ============================================
    private var jeepneyIdString: String = "UNKNOWN"
    private var jeepneyPlateNumber: String = "UNKNOWN"
    private var jeepneyName: String = ""
    private var jeepneyDriverName: String = "Driver"
    private var jeepneyCapacity: Int = 24
    private var jeepneyBracket: Int = 1
    private var lastAlertTime: Long = 0L
    private val ALERT_INTERVAL = 30000L

    // ============================================
    // LIFECYCLE
    // ============================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure DeviceConfig is initialized
        DeviceConfig.init(this)

        supabase = SupabaseService(this)
        smsService = SMSService(this, supabase)
        jeepneyIdString = DeviceConfig.getJeepId() ?: "UNKNOWN"

        initializeServices()
        initializeCamera()
        loadConfig()
        loadJeepInfo()
        setupListeners()

        if (role == DeviceRole.PRIMARY) {
            startGpsTracking()
        } else {
            updateGpsStatus(false)
            binding.txtGpsStatus.text = "GPS: Secondary Device"
        }

        setupButtonListeners()
        setupDropdownToggle()
        setupStatsToggle()

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

        geofenceManager.startGeofence()
    }

    override fun onResume() {
        super.onResume()
        loadDeviceConfig()
        
        // Ensure services match the current role (in case it was changed)
        if (role == DeviceRole.PRIMARY) {
            startGpsTracking()
            geofenceManager.startGeofence()
        } else {
            gpsTracker.stopTracking()
            geofenceManager.stopGeofence()
            updateGpsStatus(false)
            binding.txtGpsStatus.text = "GPS: Secondary Device"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        gpsTracker.stopTracking()
        geofenceManager.stopGeofence()
        cameraExecutor.shutdown()
    }

    // ============================================
    // INITIALIZATION
    // ============================================

    private fun initializeServices() {
        uploadManager = UploadManager(supabase)

        // Fix: Create GpsTracker with both update functions
        gpsTracker = GpsTracker(
            context = this,
            onLocationUpdate = { lat, lng ->
                // 1. Update jeepneys table (for current location)
                supabase.updateGps(lat, lng) { success ->
                    if (success) {
                        Log.d(TAG, " Jeepney GPS updated")
                    } else {
                        Log.d(TAG, "Failed to update jeepney GPS")
                    }
                }

                // 2. Send to gps_tracking table (for history and real-time)
                supabase.sendGpsTracking(lat, lng, 0.0, 0.0) { success ->
                    if (success) {
                        Log.d(TAG, " GPS tracking data sent")
                    } else {
                        Log.d(TAG, " Failed to send GPS tracking")
                    }
                }
            }
        )
    }
    private fun initializeCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            try {
                detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
                Log.d(TAG, "✅ Detector initialized")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Detector init error: ${e.message}")
            }
        }
    }

    private fun loadConfig() {
        loadDeviceConfig()
    }

    private fun loadDeviceConfig() {
        val savedRole = DeviceConfig.getRole()
        Log.d("CONFIG", "📂 Loading Device Role: $savedRole")
        
        role = if (savedRole == "SECONDARY") {
            DeviceRole.SECONDARY
        } else {
            DeviceRole.PRIMARY
        }
        door = DeviceConfig.getDoor()
        Log.d("CONFIG", "📂 Applied Role: $role, Door: $door")
    }

    // ============================================
    // LOAD JEEP INFO - FIXED
    // ============================================
    private fun loadJeepInfo() {
        val currentId = DeviceConfig.jeepId(this) ?: return

        Log.d("MAIN", "📡 Loading jeepney info for: $currentId")

        supabase.getJeepneyWithDriver { info ->
            runOnUiThread {
                if (info != null) {
                    jeepneyIdString = currentId
                    jeepneyPlateNumber = info.plateNumber
                    jeepneyName = info.jeepName
                    jeepneyDriverName = info.driverName
                    jeepneyCapacity = info.capacity
                    jeepneyBracket = info.bracket


                    val displayName = if (info.jeepName.isNotEmpty()) {
                        "${info.jeepName} (${info.plateNumber})"
                    } else {
                        "${info.plateNumber}"
                    }
                    binding.txtJeepName.text = displayName

                    Log.d("MAIN", "✅ Loaded: $displayName, Driver: ${info.driverName}")

                    geofenceManager.updateJeepneyData(
                        plate = info.plateNumber,
                        jeepName = info.jeepName,
                        driver = info.driverName,
                        occupancy =  info.occupancy
                    )
                } else {
                    binding.txtJeepName.text = "Unknown Jeep"
                    Log.e("MAIN", "❌ Failed to load jeepney info")
                }
            }
        }
    }

    // ============================================
    // TEST BUTTONS
    // ============================================
    private fun setupButtonListeners() {
        binding.btnSetTerminalHere.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                gpsTracker.getLastKnownLocation { lat, lng ->
                    runOnUiThread {
                        if (lat != 0.0 && lng != 0.0) {
                            Toast.makeText(
                                this,
                                "📍 Location: $lat, $lng",
                                Toast.LENGTH_LONG
                            ).show()
                            Log.d("TEST", "📍 Current location: $lat, $lng")
                        } else {
                            Toast.makeText(this, "❌ Getting GPS...", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION)
            }
        }

        binding.btnEnterTerminal.setOnClickListener {
            geofenceManager.simulateEnterTerminal()
            Toast.makeText(this, "🚪 Simulated ENTER terminal", Toast.LENGTH_SHORT).show()
        }

        binding.btnExitTerminal.setOnClickListener {
            geofenceManager.simulateExitTerminal()
            Toast.makeText(this, "🚪 Simulated EXIT terminal", Toast.LENGTH_SHORT).show()
        }

        binding.btnWaitingTimeout.setOnClickListener {
            geofenceManager.simulateWaitingTimeout()
            Toast.makeText(this, "⏰ Simulated WAITING TIMEOUT", Toast.LENGTH_SHORT).show()
        }

        binding.btnLoadingComplete.setOnClickListener {
            geofenceManager.simulateLoadingComplete()
            Toast.makeText(this, "⏱️ Simulated LOADING COMPLETE", Toast.LENGTH_SHORT).show()
        }

        binding.btnShowStatus.setOnClickListener {
            val status = geofenceManager.getCurrentStatus()
            Toast.makeText(this, "Status: $status", Toast.LENGTH_SHORT).show()
            Log.d("TEST", "Current status: $status")
        }
    }

    private fun setupDropdownToggle() {
        var isExpanded = false

        binding.queueTestHeader.setOnClickListener {
            isExpanded = !isExpanded
            binding.dropdownContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
            val rotation = if (isExpanded) 180f else 0f
            binding.dropdownArrow.animate()
                .rotation(rotation)
                .setDuration(200)
                .start()
            Log.d("Dropdown", if (isExpanded) "Expanded" else "Collapsed")
        }

        binding.dropdownContent.visibility = View.GONE
    }

    private fun setupStatsToggle() {
        var expanded = false

        binding.btnToggleMore.setOnClickListener {
            expanded = !expanded

            binding.extraControlsCard.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnToggleMore.text = if (expanded) "▲ Less" else "▼ More"

            binding.overlay.post {
                binding.overlay.invalidate()
            }
        }
    }

    // ============================================
    // CAMERA
    // ============================================
    private fun setupListeners() {
        binding.isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
            cameraExecutor.submit {
                detector?.restart(isGpu = isChecked)
            }
            buttonView.setBackgroundColor(
                if (isChecked) ContextCompat.getColor(baseContext, R.color.orange)
                else ContextCompat.getColor(baseContext, R.color.gray)
            )
        }

        binding.btnStartCamera.setOnClickListener {
            if (!isCameraStarted) {
                startCameraIfPermitted()
                Toast.makeText(this, "📷 Starting camera...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "📷 Camera already running", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStopCamera.setOnClickListener {
            if (isCameraStarted) {
                stopCamera()
                Toast.makeText(this, "📷 Camera stopped", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "📷 Camera not running", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            isCameraStarted = false
            Log.d(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop camera error: ${e.message}")
        }
    }

    private fun startCameraIfPermitted() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        if (isCameraStarted) {
            Log.d(TAG, "Camera already running")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                isCameraStarted = true
                Log.d(TAG, "✅ Camera started successfully")
                runOnUiThread {
                    Toast.makeText(this, "✅ Camera started", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Camera start error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "❌ Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                imageProxy.close()

                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    if (isFrontCamera) {
                        postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                    }
                }

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                    matrix, true
                )

                detector?.detect(rotatedBitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Frame analysis error: ${e.message}")
            }
        }

        provider.unbindAll()

        try {
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            throw exc
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // ============================================
    // DETECTOR LISTENER
    // ============================================
    override fun onEmptyDetect() {
        runOnUiThread { binding.overlay.clear() }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val data = passengerManager.processDetections(boundingBoxes, door)

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(data.trackedBoxes)
            binding.txtInside.text = data.inside.toString()
            binding.txtBoarded.text = data.boarded.toString()
            binding.txtExited.text = data.exited.toString()
        }

        geofenceManager.updateOccupancy(data.inside)

        val total = data.inside
        val capacity = jeepneyCapacity
        val percent = if (capacity > 0) (total * 100) / capacity else 0

        if (percent >= 80) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > ALERT_INTERVAL) {
                lastAlertTime = now

                val dispatcherPhone = "+639123456789"

                Log.d("SMS", "📱 Sending occupancy alert")
                Log.d("SMS", "   Plate: $jeepneyPlateNumber")
                Log.d("SMS", "   Total: $total")
                Log.d("SMS", "   Capacity: $capacity")
                Log.d("SMS", "   Percent: $percent%")
                Log.d("SMS", "   Phone: $dispatcherPhone")

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

        uploadManager.uploadPassengerData(data, role, door, currentStatus)
    }

    // ============================================
    // STATUS UI
    // ============================================
    private fun updateStatusUI(status: String) {
        runOnUiThread {
            val statusText = when (status) {
                "waiting" -> "⏳ Waiting"
                "loading" -> "🔄 Loading..."
                "en_route" -> "🚐 En Route"
                "arrived" -> "📍 Arrived"
                "dispatched" -> "📋 Dispatched"
                else -> "⏸️ Inactive"
            }
            binding.txtJeepStatus.text = statusText
        }
    }

    // ============================================
    // GPS
    // ============================================
    private fun startGpsTracking() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }
        gpsTracker.startTracking()
        updateGpsStatus(true)
    }

    private fun updateGpsStatus(isTracking: Boolean) {
        runOnUiThread {
            binding.txtGpsStatus.text = if (isTracking) "GPS: Active" else "GPS: Off"
            binding.txtGpsStatus.setTextColor(
                if (isTracking) ContextCompat.getColor(this, R.color.green)
                else ContextCompat.getColor(this, R.color.red)
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startGpsTracking()
        }
        if (requestCode == REQUEST_CODE_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraIfPermitted()
        }
    }

    // ============================================
    // COMPANION OBJECT
    // ============================================
    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCameraIfPermitted()
        }
    }
}