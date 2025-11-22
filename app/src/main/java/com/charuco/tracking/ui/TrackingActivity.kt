package com.charuco.tracking.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.charuco.tracking.R
import com.charuco.tracking.databinding.ActivityTrackingBinding
import com.charuco.tracking.detector.CharucoDetector
import com.charuco.tracking.tracking.TrajectoryTracker
import com.charuco.tracking.utils.CalibrationManager
import com.charuco.tracking.utils.ConfigManager
import com.charuco.tracking.utils.DataExporter
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TrackingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrackingBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: CharucoDetector
    private lateinit var tracker: TrajectoryTracker
    private lateinit var configManager: ConfigManager
    private lateinit var dataExporter: DataExporter

    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var lastProcessTime = 0L
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)
        val calibrationManager = CalibrationManager(this)
        val calibrationData = calibrationManager.loadCalibration()

        if (calibrationData == null) {
            Toast.makeText(this, "キャリブレーションデータが見つかりません", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        detector = CharucoDetector(configManager, calibrationData)
        tracker = TrajectoryTracker()
        dataExporter = DataExporter()

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupUI()
        startCamera()
    }

    private fun setupUI() {
        updateUI()

        binding.btnStartStop.setOnClickListener {
            if (tracker.isTracking()) {
                stopTracking()
            } else {
                showDelayDialog()
            }
        }
    }

    private fun showDelayDialog() {
        val input = EditText(this)
        input.hint = "0"
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        AlertDialog.Builder(this)
            .setTitle("開始までの遅延（秒）")
            .setView(input)
            .setPositiveButton("開始") { _, _ ->
                val delay = input.text.toString().toIntOrNull() ?: 0
                if (delay > 0) {
                    startCountdown(delay)
                } else {
                    startTracking()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startCountdown(seconds: Int) {
        binding.btnStartStop.isEnabled = false
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                binding.tvPoseCount.text = "開始まで: ${sec}秒"
            }
            override fun onFinish() {
                binding.btnStartStop.isEnabled = true
                startTracking()
            }
        }.start()
    }

    private fun startTracking() {
        tracker.start()
        binding.btnStartStop.text = getString(R.string.stop_tracking_button)
        Toast.makeText(this, "トラッキング開始", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        val trajectoryData = tracker.stop()
        binding.btnStartStop.text = getString(R.string.start_tracking_button)

        if (trajectoryData != null) {
            showSaveDialog(trajectoryData)
        } else {
            Toast.makeText(this, "トラッキングデータがありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveDialog(trajectoryData: com.charuco.tracking.utils.TrajectoryData) {
        val input = EditText(this)
        input.hint = "ファイル名 (例: trajectory_test1)"

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_trajectory))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isNotEmpty()) {
                    saveTrajectory(trajectoryData, fileName)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveTrajectory(
        trajectoryData: com.charuco.tracking.utils.TrajectoryData,
        fileName: String
    ) {
        try {
            val savePath = configManager.getSavePath()
            if (savePath != null) {
                // Use SAF URI
                val uri = Uri.parse(savePath)
                val dir = DocumentFile.fromTreeUri(this, uri)
                val docFile = dir?.createFile("application/x-yaml", "$fileName.yaml")
                docFile?.uri?.let { fileUri ->
                    contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                        dataExporter.exportTrajectory(trajectoryData, outputStream)
                    }
                    Toast.makeText(this, "${getString(R.string.trajectory_saved)}: $fileName.yaml", Toast.LENGTH_LONG).show()
                }
            } else {
                // Default path
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "$fileName.yaml")
                dataExporter.exportTrajectory(trajectoryData, file)
                Toast.makeText(this, "${getString(R.string.trajectory_saved)}: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save trajectory", e)
            Toast.makeText(this, getString(R.string.error_saving_file), Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(configManager.getCameraWidth(), configManager.getCameraHeight()))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, TrackingAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private inner class TrackingAnalyzer : ImageAnalysis.Analyzer {
        private var frameCount = 0L
        private var lastFpsTime = System.currentTimeMillis()

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val currentTime = System.currentTimeMillis()
                val trackingInterval = 1000.0 / configManager.getTrackingFps()

                // Check if enough time has passed for tracking
                if (currentTime - lastProcessTime >= trackingInterval || !tracker.isTracking()) {
                    val bitmap = imageProxy.toBitmap()
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)

                    val detectionResult = detector.detectAndEstimatePose(mat)

                    if (detectionResult != null) {
                        // Add pose to tracker if tracking
                        if (tracker.isTracking()) {
                            tracker.addPose(detectionResult.poseData)
                        }

                        runOnUiThread {
                            updateUI(detectionResult.poseData)
                        }

                        detectionResult.release()
                    } else {
                        runOnUiThread {
                            updateUI()
                        }
                    }

                    mat.release()
                    lastProcessTime = currentTime
                }

                // Calculate FPS
                frameCount++
                if (currentTime - lastFpsTime >= 1000) {
                    val fps = frameCount.toFloat() / ((currentTime - lastFpsTime) / 1000f)
                    runOnUiThread {
                        binding.tvFps.text = getString(R.string.fps, fps)
                    }
                    frameCount = 0
                    lastFpsTime = currentTime
                }
            }
            imageProxy.close()
        }
    }

    private fun updateUI(poseData: com.charuco.tracking.utils.PoseData? = null) {
        binding.tvPoseCount.text =
            getString(R.string.poses_recorded, tracker.getNumPoses())

        if (poseData != null) {
            binding.tvPositionX.text = getString(R.string.position_x, poseData.translation.x)
            binding.tvPositionY.text = getString(R.string.position_y, poseData.translation.y)
            binding.tvPositionZ.text = getString(R.string.position_z, poseData.translation.z)
            binding.tvYaw.text = getString(R.string.rotation_yaw, poseData.rotation.yaw)
            binding.tvQuality.text = getString(R.string.quality, poseData.quality)
        } else {
            // Show "Board not detected" message
            binding.tvPositionX.text = getString(R.string.board_not_detected)
            binding.tvPositionY.text = ""
            binding.tvPositionZ.text = ""
            binding.tvYaw.text = ""
            binding.tvQuality.text = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "TrackingActivity"
        init {
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "OpenCV initialization failed")
            }
        }
    }
}
