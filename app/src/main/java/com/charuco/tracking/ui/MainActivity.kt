package com.charuco.tracking.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RemoteMeasurementService.LocalBinder
            remoteMeasurementService = binder.getService()
            isServiceBound = true
            updateServerUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteMeasurementService = null
            isServiceBound = false
            updateServerUI()
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
        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
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
