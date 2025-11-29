package com.charuco.tracking.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleService
import com.charuco.tracking.R
import com.charuco.tracking.detector.CharucoDetector
import com.charuco.tracking.tracking.SpotMeasurer
import com.charuco.tracking.utils.*
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Service for remote camera measurement
 */
class RemoteMeasurementService : LifecycleService(), MeasurementController {

    private val binder = LocalBinder()
    private var server: RemoteCameraServer? = null
    private lateinit var cameraExecutor: ExecutorService
    private var detector: CharucoDetector? = null
    private var measurer: SpotMeasurer? = null
    private lateinit var configManager: ConfigManager
    private lateinit var dataExporter: DataExporter

    // Handler for running tasks on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var currentFileName: String? = null
    private var lastResult: Map<String, Any>? = null
    private var countDownTimer: CountDownTimer? = null

    companion object {
        private const val TAG = "RemoteMeasurementSvc"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "remote_camera_channel"
        const val DEFAULT_PORT = 8080

        const val ACTION_START_SERVER = "com.charuco.tracking.START_SERVER"
        const val ACTION_STOP_SERVER = "com.charuco.tracking.STOP_SERVER"
        const val EXTRA_PORT = "port"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RemoteMeasurementService = this@RemoteMeasurementService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        configManager = ConfigManager(this)
        dataExporter = DataExporter()
        cameraExecutor = Executors.newSingleThreadExecutor()

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SERVER -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startServer(port)
            }
            ACTION_STOP_SERVER -> {
                stopServer()
                stopSelf()
            }
        }

        return START_STICKY
    }

    fun startServer(port: Int = DEFAULT_PORT) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        try {
            // Initialize camera and detector
            initializeCamera()

            // Start HTTP server
            server = RemoteCameraServer(port, this)
            server?.start()

            val ipAddress = getIpAddress()
            Log.d(TAG, "Server started on port $port, IP: $ipAddress")

            // Start foreground service
            val notification = createNotification(
                "Remote Camera Server Running",
                "Access at http://$ipAddress:$port"
            )
            startForeground(NOTIFICATION_ID, notification)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopSelf()
        }
    }

    fun stopServer() {
        server?.stop()
        server = null

        stopMeasurement()
        cleanup()

        Log.d(TAG, "Server stopped")
    }

    fun isServerRunning(): Boolean {
        return server != null
    }

    fun getServerUrl(): String? {
        return if (server != null) {
            "http://${getIpAddress()}:${DEFAULT_PORT}"
        } else {
            null
        }
    }

    private fun initializeCamera() {
        val calibrationManager = CalibrationManager(this)
        val calibrationData = calibrationManager.loadCalibration()

        if (calibrationData == null) {
            throw IllegalStateException("Calibration data not found")
        }

        detector = CharucoDetector(configManager, calibrationData)
        calibrationData.release()

        measurer = SpotMeasurer(targetSamples = 30)

        // Start camera
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(configManager.getCameraWidth(), configManager.getCameraHeight()))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, MeasurementAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
                Log.d(TAG, "Camera initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                throw e
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun startMeasurement(fileName: String, delay: Int) {
        Log.d(TAG, "startMeasurement: fileName=$fileName, delay=$delay")

        if (measurer == null) {
            throw IllegalStateException("Measurer not initialized")
        }

        currentFileName = fileName

        if (delay > 0) {
            mainHandler.post {
                countDownTimer?.cancel()
                countDownTimer = object : CountDownTimer(delay * 1000L, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val sec = (millisUntilFinished / 1000) + 1
                        updateNotification("Measurement starting in ${sec}s", "File: $fileName")
                    }
                    override fun onFinish() {
                        doStartMeasurement()
                    }
                }.start()
            }
        } else {
            doStartMeasurement()
        }
    }

    private fun doStartMeasurement() {
        measurer?.start()
        mainHandler.post {
            updateNotification("Measuring...", "File: $currentFileName")
        }
        Log.d(TAG, "Measurement started")
    }

    override fun stopMeasurement() {
        mainHandler.post {
            countDownTimer?.cancel()
        }
        measurer?.stop()
        currentFileName = null
        mainHandler.post {
            updateNotification("Server Running", "Ready for measurements")
        }
        Log.d(TAG, "Measurement stopped")
    }

    override fun getStatus(): Map<String, Any> {
        val status = mutableMapOf<String, Any>()

        status["isMeasuring"] = measurer?.isMeasuring() ?: false
        status["numSamples"] = measurer?.getNumSamples() ?: 0
        status["targetSamples"] = measurer?.getTargetSamples() ?: 30
        status["progress"] = measurer?.getProgress() ?: 0.0
        status["currentFileName"] = currentFileName ?: ""

        if (lastResult != null) {
            status["lastResult"] = lastResult!!
        }

        return status
    }

    private inner class MeasurementAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val mediaImage = imageProxy.image
                if (mediaImage != null && detector != null && measurer != null) {
                    val bitmap = imageProxy.toBitmap()
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)

                    val detectionResult = detector?.detectAndEstimatePose(mat, applyTransform = true)

                    if (detectionResult != null) {
                        if (measurer?.isMeasuring() == true) {
                            val isComplete = measurer?.addSample(detectionResult.poseData) ?: false

                            if (isComplete) {
                                val measurement = measurer?.stop()
                                if (measurement != null && currentFileName != null) {
                                    onMeasurementComplete(measurement, currentFileName!!)
                                }
                            }
                        }

                        detectionResult.release()
                    }

                    mat.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in image analysis", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun onMeasurementComplete(measurement: SpotMeasurement, fileName: String) {
        Log.d(TAG, "Measurement complete: $fileName")

        // Save measurement
        saveMeasurement(measurement, fileName)

        // Store last result
        lastResult = mapOf(
            "fileName" to fileName,
            "position" to mapOf(
                "x" to measurement.pose.translation.x,
                "y" to measurement.pose.translation.y,
                "z" to measurement.pose.translation.z
            ),
            "rotation" to mapOf(
                "roll" to measurement.pose.rotation.roll,
                "pitch" to measurement.pose.rotation.pitch,
                "yaw" to measurement.pose.rotation.yaw
            ),
            "stdDev" to mapOf(
                "x" to measurement.stdDev.xMm,
                "y" to measurement.stdDev.yMm,
                "z" to measurement.stdDev.zMm
            ),
            "numSamples" to measurement.numSamples
        )

        mainHandler.post {
            updateNotification("Measurement Complete", "Saved: $fileName.yaml")
        }
        currentFileName = null
    }

    private fun saveMeasurement(measurement: SpotMeasurement, fileName: String) {
        try {
            val sanitizedFileName = FileUtils.sanitizeFileName(fileName)
            val savePath = configManager.getSavePath()

            if (savePath != null) {
                val uri = Uri.parse(savePath)
                val dir = DocumentFile.fromTreeUri(this, uri)
                val docFile = dir?.createFile("application/x-yaml", "$sanitizedFileName.yaml")
                docFile?.uri?.let { fileUri ->
                    contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                        dataExporter.exportSpotMeasurement(measurement, outputStream)
                    }
                }
                Log.d(TAG, "Measurement saved to: $sanitizedFileName.yaml")
            } else {
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "$sanitizedFileName.yaml")
                dataExporter.exportSpotMeasurement(measurement, file)
                Log.d(TAG, "Measurement saved to: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save measurement", e)
        }
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
        }
        return "Unknown"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Camera Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Remote camera measurement server"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notification = createNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun cleanup() {
        mainHandler.post {
            countDownTimer?.cancel()
        }
        cameraExecutor.shutdown()
        detector?.release()
        detector = null
        measurer = null
        camera = null
        imageAnalysis = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        Log.d(TAG, "Service destroyed")
    }
}
