package com.charuco.tracking.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.charuco.tracking.R
import com.charuco.tracking.databinding.ActivityMainBinding
import com.charuco.tracking.service.RemoteMeasurementService
import com.charuco.tracking.utils.CalibrationManager
import com.charuco.tracking.utils.ConfigManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var configManager: ConfigManager
    private var remoteMeasurementService: RemoteMeasurementService? = null
    private var isServiceBound = false

    // Handler for periodic status updates
    private val statusUpdateHandler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L // Update every 1 second

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateMeasurementStatus()
            statusUpdateHandler.postDelayed(this, updateInterval)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RemoteMeasurementService.LocalBinder
            remoteMeasurementService = binder.getService()
            isServiceBound = true
            updateServerUI()
            startStatusUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteMeasurementService = null
            isServiceBound = false
            updateServerUI()
            stopStatusUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        calibrationManager = CalibrationManager(this)
        configManager = ConfigManager(this)

        setupUI()
        checkCameraPermission()
    }

    private fun setupUI() {
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.btnTracking.setOnClickListener {
            if (calibrationManager.hasCalibration()) {
                startActivity(Intent(this, TrackingActivity::class.java))
            } else {
                Toast.makeText(this, "カメラキャリブレーションを先に実行してください", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSpotMeasurement.setOnClickListener {
            if (calibrationManager.hasCalibration()) {
                startActivity(Intent(this, SpotMeasurementActivity::class.java))
            } else {
                Toast.makeText(this, "カメラキャリブレーションを先に実行してください", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnDataViewer.setOnClickListener {
            startActivity(Intent(this, DataViewerActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnToggleServer.setOnClickListener {
            toggleRemoteServer()
        }

        updateCalibrationStatus()
    }

    private fun toggleRemoteServer() {
        if (!calibrationManager.hasCalibration()) {
            Toast.makeText(this, "カメラキャリブレーションを先に実行してください", Toast.LENGTH_LONG).show()
            return
        }

        if (remoteMeasurementService?.isServerRunning() == true) {
            stopRemoteServer()
        } else {
            startRemoteServer()
        }
    }

    private fun startRemoteServer() {
        val intent = Intent(this, RemoteMeasurementService::class.java).apply {
            action = RemoteMeasurementService.ACTION_START_SERVER
            putExtra(RemoteMeasurementService.EXTRA_PORT, RemoteMeasurementService.DEFAULT_PORT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Bind to service
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        Toast.makeText(this, "サーバーを起動しています...", Toast.LENGTH_SHORT).show()
    }

    private fun stopRemoteServer() {
        val intent = Intent(this, RemoteMeasurementService::class.java).apply {
            action = RemoteMeasurementService.ACTION_STOP_SERVER
        }

        stopStatusUpdates()

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        startService(intent)
        remoteMeasurementService = null

        updateServerUI()
        Toast.makeText(this, "サーバーを停止しました", Toast.LENGTH_SHORT).show()
    }

    private fun updateServerUI() {
        if (remoteMeasurementService?.isServerRunning() == true) {
            binding.btnToggleServer.text = "サーバー停止"
            val url = remoteMeasurementService?.getServerUrl()
            binding.tvServerUrl.text = "URL: $url"
            binding.tvServerUrl.visibility = View.VISIBLE
        } else {
            binding.btnToggleServer.text = "サーバー開始"
            binding.tvServerUrl.visibility = View.GONE

            // Hide measurement status when server is stopped
            try {
                binding.layoutMeasurementStatus.visibility = View.GONE
            } catch (e: Exception) {
                // UI element not yet added to layout
            }
        }
    }

    private fun updateMeasurementStatus() {
        try {
            val service = remoteMeasurementService
            if (service == null || !service.isServerRunning()) {
                binding.layoutMeasurementStatus.visibility = View.GONE
                return
            }

            val status = service.getStatus()
            val isMeasuring = status["isMeasuring"] as? Boolean ?: false

            if (isMeasuring) {
                binding.layoutMeasurementStatus.visibility = View.VISIBLE

                val currentFileName = status["currentFileName"] as? String ?: ""
                val numSamples = status["numSamples"] as? Int ?: 0
                val targetSamples = status["targetSamples"] as? Int ?: 30
                val progress = status["progress"] as? Double ?: 0.0

                binding.tvMeasurementStatus.text = "測定中..."
                binding.tvCurrentFile.text = "ファイル: $currentFileName"
                binding.tvCurrentFile.visibility = View.VISIBLE

                binding.progressMeasurement.progress = progress.toInt()
                binding.progressMeasurement.visibility = View.VISIBLE

                binding.tvProgress.text = "$numSamples / $targetSamples サンプル (${progress.toInt()}%)"
                binding.tvProgress.visibility = View.VISIBLE
            } else {
                val lastResult = status["lastResult"] as? Map<*, *>
                if (lastResult != null) {
                    binding.layoutMeasurementStatus.visibility = View.VISIBLE
                    binding.tvMeasurementStatus.text = "測定完了"

                    val fileName = lastResult["fileName"] as? String ?: ""
                    binding.tvCurrentFile.text = "ファイル: $fileName"
                    binding.tvCurrentFile.visibility = View.VISIBLE

                    binding.progressMeasurement.visibility = View.GONE

                    val numSamples = lastResult["numSamples"] as? Int ?: 0
                    binding.tvProgress.text = "$numSamples サンプル完了"
                    binding.tvProgress.visibility = View.VISIBLE
                } else {
                    binding.layoutMeasurementStatus.visibility = View.VISIBLE
                    binding.tvMeasurementStatus.text = "待機中"
                    binding.tvCurrentFile.visibility = View.GONE
                    binding.progressMeasurement.visibility = View.GONE
                    binding.tvProgress.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            // UI elements not yet added to layout, silently ignore
        }
    }

    private fun startStatusUpdates() {
        stopStatusUpdates()
        statusUpdateHandler.post(statusUpdateRunnable)
    }

    private fun stopStatusUpdates() {
        statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
    }

    private fun updateCalibrationStatus() {
        if (calibrationManager.hasCalibration()) {
            val calibration = calibrationManager.loadCalibration()
            if (calibration != null) {
                binding.tvCalibrationStatus.text =
                    "キャリブレーション済み (再投影誤差: %.3f px)".format(calibration.reprojectionError)
                // Release calibration data as we only needed to read the reprojection error
                calibration.release()
            }
        } else {
            binding.tvCalibrationStatus.text = "キャリブレーションが必要です"
        }
    }

    override fun onResume() {
        super.onResume()
        updateCalibrationStatus()
        updateServerUI()
        if (isServiceBound) {
            startStatusUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusUpdates()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        this,
                        getString(R.string.camera_permission_denied),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
