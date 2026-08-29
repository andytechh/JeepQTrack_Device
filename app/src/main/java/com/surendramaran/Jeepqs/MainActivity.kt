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
import com.surendramaran.Jeepqs.activities.SetupActivity
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadManager: UploadManager
    private lateinit var gpsTracker: GpsTracker
    private lateinit var smsService: SMSService
    private lateinit var supabase: SupabaseService
    private lateinit var geofenceManager: GeofenceManager

    private val passengerManager = PassengerManager()
    private var terminalId: Int = 1

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var isCameraStarted = false
    private val isFrontCamera = false

    private var role: DeviceRole = DeviceRole.PRIMARY
    private var door: String = "REAR"
    private var currentStatus: String = "inactive"

    private var jeepneyIdString: String = "UNKNOWN"
    private var jeepneyPlateNumber: String = "UNKNOWN"
    private var jeepneyName: String = ""
    private var jeepneyDriverName: String = "Driver"
    private var jeepneyCapacity: Int = 24
    private var jeepneyBracket: Int = 1

    private var lastAlertTime: Long = 0L
    private val ALERT_INTERVAL = 30000L

    // ─── STATUS POLLING ──────────────────────────────────────────────
    private val statusCheckHandler = Handler(Looper.getMainLooper())
    private val statusCheckRunnable = object : Runnable {
        override fun run() {
            checkStatusFromSupabase()
            statusCheckHandler.postDelayed(this, 10000) // every 10 seconds
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeApp()
        setupUI()
        setupGeofence()
        loadJeepInfo()
        startStatusPolling() // start polling for status updates
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceConfig()
        updateServicesState()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusPolling()
        cleanupResources()
    }

    private fun initializeApp() {
        DeviceConfig.init(this)
        supabase = SupabaseService(this)
        smsService = SMSService(this, supabase)

        loadDeviceConfig()
        initializeServices()
        initializeCamera()
        setupListeners()
        setupMenuButtons()
        setupDropdownToggle()
        setupStatsToggle()
    }

    private fun initializeServices() {
        uploadManager = UploadManager(supabase)

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
            } catch (_: Exception) { }
        }
    }

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
        // NOTE: startGeofence() is deliberately NOT called here. loadJeepInfo()
        // calls it once the real jeepney row (status/terminal_id/loading_started_at)
        // has been fetched and syncState() has restored it — otherwise a fresh
        // INITIAL_TRIGGER_ENTER fire could race ahead of syncState() and reset an
        // in-progress "loading"/"waiting" jeepney back to "arrived". See
        // loadJeepInfo() below.
    }

    private fun setupUI() {
        updateDeviceInfoUI()
        updateGpsStatus(false)
        binding.txtGpsStatus.text = "GPS: Secondary Device"
        binding.txtJeepStatus.text = "⏸️ Inactive"
    }

    private fun setupListeners() {
        binding.isGpu.setOnCheckedChangeListener { _, isChecked ->
            cameraExecutor.submit {
                detector?.restart(isGpu = isChecked)
            }
        }

        binding.btnStartCamera.setOnClickListener {
            if (!isCameraStarted) {
                startCameraIfPermitted()
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

    private fun setupMenuButtons() {
        binding.btnLogout.setOnClickListener {
            performLogout()
        }

        binding.btnSetTerminalHere.setOnClickListener {
            setCurrentLocationAsTerminal()
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
        }
    }

    private fun setupDropdownToggle() {
        var isExpanded = false
        binding.queueTestHeader.setOnClickListener {
            isExpanded = !isExpanded
            binding.dropdownContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.dropdownArrow.animate()
                .rotation(if (isExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }
        binding.dropdownContent.visibility = View.GONE
    }

    private fun setupStatsToggle() {
        var expanded = false
        binding.btnToggleMore.setOnClickListener {
            expanded = !expanded
            binding.extraControlsCard.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnToggleMore.text = if (expanded) "▲ Less" else "▼ More"
        }
    }

    private fun loadDeviceConfig() {
        val savedRole = DeviceConfig.getRole()
        role = if (savedRole == "SECONDARY") DeviceRole.SECONDARY else DeviceRole.PRIMARY
        door = DeviceConfig.getDoor() ?: "REAR"
        jeepneyIdString = DeviceConfig.getJeepId() ?: "UNKNOWN"
        // 🔥 Read terminal and bracket from config
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
            binding.roleText.setTextColor(ContextCompat.getColor(this, R.color.primary_role))
            binding.roleText.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_role_bg))
        } else {
            binding.roleText.text = "SECONDARY"
            binding.roleText.setTextColor(ContextCompat.getColor(this, R.color.secondary_role))
            binding.roleText.setBackgroundColor(ContextCompat.getColor(this, R.color.secondary_role_bg))
        }

        binding.doorText.text = if (doorStr == "FRONT") "Front Door" else "Rear Door"
    }

    private fun updateServicesState() {
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

    private fun loadJeepInfo() {
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

                    val displayName = if (info.jeepName.isNotEmpty()) {
                        "${info.jeepName} (${info.plateNumber})"
                    } else {
                        info.plateNumber
                    }
                    binding.txtJeepName.text = displayName

                    geofenceManager.updateJeepneyData(
                        plate = info.plateNumber,
                        jeepName = info.jeepName,
                        driver = info.driverName,
                        occupancy = info.occupancy
                    )
                    geofenceManager.updateBracket(info.bracket)
                    // Restore in-memory loading/waiting state from the real row
                    // BEFORE registering geofences, so a fresh
                    // INITIAL_TRIGGER_ENTER fire (if still physically inside a
                    // terminal after a restart) sees the correct state via the
                    // onEnterTerminal guard instead of resetting it to "arrived".
                    geofenceManager.syncState(
                        status = info.status,
                        terminalId = info.terminalId,
                        loadingStartedAtMillis = info.loadingStartedAtMillis
                    )
                } else {
                    binding.txtJeepName.text = "Unknown Jeep"
                }
                // Always start geofencing, even if the fetch failed — falls
                // back to the default "inactive" in-memory state in that case,
                // same as before this fetch existed.
                geofenceManager.startGeofence()
            }
        }
    }

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
                ContextCompat.getColor(
                    this,
                    if (isTracking) R.color.green else R.color.red
                )
            )
        }
    }

    private fun updateLocationData(lat: Double, lng: Double) {
        supabase.updateGps(lat, lng) { }
        supabase.sendGpsTracking(lat, lng, 0.0, 0.0) { }
    }

    private fun setCurrentLocationAsTerminal() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            gpsTracker.getLastKnownLocation { lat, lng ->
                runOnUiThread {
                    if (lat != 0.0 && lng != 0.0) {
                        Toast.makeText(
                            this,
                            "📍 Location: $lat, $lng",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, "❌ Getting GPS...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

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

    private fun startCamera() {
        if (isCameraStarted) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                isCameraStarted = true
                Toast.makeText(this, "📷 Camera started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            isCameraStarted = false
        } catch (_: Exception) { }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera not initialized")
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
            analyzeImage(imageProxy)
        }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageAnalyzer
        )
        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun analyzeImage(imageProxy: androidx.camera.core.ImageProxy) {
        try {
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use {
                bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            }

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
        } catch (_: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
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
        checkOccupancyAlert(data.inside)
        uploadManager.uploadPassengerData(data, role, door, currentStatus)
    }

    private fun checkOccupancyAlert(total: Int) {
        val capacity = jeepneyCapacity
        val percent = if (capacity > 0) (total * 100) / capacity else 0

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

    // ─── STATUS POLLING ──────────────────────────────────────────────

    private fun checkStatusFromSupabase() {
        if (jeepneyIdString == "UNKNOWN") return
        supabase.getCurrentStatus(jeepneyIdString) { status ->
            if (status != null && status != currentStatus) {
                currentStatus = status
                runOnUiThread { updateStatusUI(status) }
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
            val statusText = when (status) {
                "waiting" -> "Waiting"
                "loading" -> "🔄 Loading..."
                "en_route" -> "En Route"
                "arrived" -> "📍 Arrived"
                "dispatched" -> "Dispatched"
                "inactive" -> "⏸️ Inactive"
                else -> "⏸️ Inactive"
            }
            binding.txtJeepStatus.text = statusText
        }
    }

    private fun performLogout() {
        DeviceConfig.clearConfig()
        Toast.makeText(this, "Logged out. Returning to setup...", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun cleanupResources() {
        detector?.close()
        gpsTracker.stopTracking()
        geofenceManager.stopGeofence()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_LOCATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startGpsTracking()
                }
            }
            REQUEST_CODE_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCameraIfPermitted()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            startCameraIfPermitted()
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}