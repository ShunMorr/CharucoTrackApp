package com.charuco.tracking.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.charuco.tracking.databinding.ActivitySettingsBinding
import com.charuco.tracking.utils.ConfigManager
import org.opencv.core.Mat
import org.opencv.core.CvType

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configManager: ConfigManager

    private val dictionaries = listOf(
        "DICT_4X4_50",
        "DICT_5X5_100",
        "DICT_6X6_250",
        "DICT_7X7_1000"
    )

    // Resolution options (display name to width x height mapping)
    private val resolutions = listOf(
        "640x480 (VGA)" to Pair(640, 480),
        "1280x720 (HD)" to Pair(1280, 720),
        "1920x1080 (Full HD)" to Pair(1920, 1080),
        "1920x1280" to Pair(1920, 1280),
        "3840x2160 (4K)" to Pair(3840, 2160)
    )

    private var selectedSavePath: String? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedSavePath = it.toString()
            updatePathDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)

        setupSpinner()
        loadCurrentSettings()
        setupSaveButton()
        setupFolderPicker()
        setupTransformSettings()
    }

    private fun setupSpinner() {
        // Dictionary spinner
        val dictAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dictionaries)
        dictAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDictionary.adapter = dictAdapter

        // Resolution spinner
        val resolutionNames = resolutions.map { it.first }
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutionNames)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = resAdapter
    }

    private fun loadCurrentSettings() {
        // Dictionary
        val currentDict = configManager.getDictionary()
        val dictIndex = dictionaries.indexOf(currentDict)
        if (dictIndex >= 0) {
            binding.spinnerDictionary.setSelection(dictIndex)
        }

        // Resolution
        val currentWidth = configManager.getCameraWidth()
        val currentHeight = configManager.getCameraHeight()
        val resIndex = resolutions.indexOfFirst { it.second.first == currentWidth && it.second.second == currentHeight }
        if (resIndex >= 0) {
            binding.spinnerResolution.setSelection(resIndex)
        }

        // Other settings
        binding.etSquaresX.setText(configManager.getSquaresX().toString())
        binding.etSquaresY.setText(configManager.getSquaresY().toString())
        binding.etSquareLength.setText(configManager.getSquareLength().toString())
        binding.etMarkerLength.setText(configManager.getMarkerLength().toString())
        binding.etMinFrames.setText(configManager.getMinCalibrationFrames().toString())

        // Save path
        selectedSavePath = configManager.getSavePath()
        updatePathDisplay()

        // Transform settings
        binding.switchTransformEnabled.isChecked = configManager.isTransformEnabled()
        loadTransformMatrix()
    }

    private fun setupFolderPicker() {
        binding.btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }
    }

    private fun updatePathDisplay() {
        val path = selectedSavePath
        if (path != null) {
            val uri = Uri.parse(path)
            val displayPath = uri.lastPathSegment ?: path
            binding.tvCurrentPath.text = "現在のパス: $displayPath"
        } else {
            binding.tvCurrentPath.text = "現在のパス: (デフォルト)"
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        try {
            val dictionary = dictionaries[binding.spinnerDictionary.selectedItemPosition]
            val selectedResolution = resolutions[binding.spinnerResolution.selectedItemPosition].second
            val squaresX = binding.etSquaresX.text.toString().toInt()
            val squaresY = binding.etSquaresY.text.toString().toInt()
            val squareLength = binding.etSquareLength.text.toString().toFloat()
            val markerLength = binding.etMarkerLength.text.toString().toFloat()
            val minFrames = binding.etMinFrames.text.toString().toInt()

            // Validation
            if (squaresX < 3 || squaresY < 3) {
                Toast.makeText(this, "マス数は3以上にしてください", Toast.LENGTH_SHORT).show()
                return
            }
            if (markerLength >= squareLength) {
                Toast.makeText(this, "マーカーサイズはマスサイズより小さくしてください", Toast.LENGTH_SHORT).show()
                return
            }
            if (minFrames < 5) {
                Toast.makeText(this, "最小フレーム数は5以上にしてください", Toast.LENGTH_SHORT).show()
                return
            }

            // Save
            configManager.saveConfig(ConfigManager.KEY_DICTIONARY, dictionary)
            configManager.saveConfig(ConfigManager.KEY_CAMERA_WIDTH, selectedResolution.first)
            configManager.saveConfig(ConfigManager.KEY_CAMERA_HEIGHT, selectedResolution.second)
            configManager.saveConfig(ConfigManager.KEY_SQUARES_X, squaresX)
            configManager.saveConfig(ConfigManager.KEY_SQUARES_Y, squaresY)
            configManager.saveConfig(ConfigManager.KEY_SQUARE_LENGTH, squareLength)
            configManager.saveConfig(ConfigManager.KEY_MARKER_LENGTH, markerLength)
            configManager.saveConfig(ConfigManager.KEY_MIN_CALIBRATION_FRAMES, minFrames)
            selectedSavePath?.let { configManager.saveConfig(ConfigManager.KEY_SAVE_PATH, it) }

            // Save transform settings
            configManager.setTransformEnabled(binding.switchTransformEnabled.isChecked)
            if (binding.switchTransformEnabled.isChecked) {
                saveTransformMatrix()
            }

            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "入力値が不正です", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTransformSettings() {
        // Toggle visibility of transform parameters based on switch
        binding.switchTransformEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutTransformParams.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Set initial visibility
        binding.layoutTransformParams.visibility =
            if (binding.switchTransformEnabled.isChecked) View.VISIBLE else View.GONE
    }

    private fun loadTransformMatrix() {
        try {
            val transformMatrix = configManager.getTransformMatrix()

            // Extract translation from matrix (4th column, first 3 rows)
            val tx = transformMatrix.get(0, 3)[0]
            val ty = transformMatrix.get(1, 3)[0]
            val tz = transformMatrix.get(2, 3)[0]

            // Extract rotation matrix (top-left 3x3)
            val rotMat = Mat(3, 3, CvType.CV_64F)
            for (i in 0..2) {
                for (j in 0..2) {
                    rotMat.put(i, j, transformMatrix.get(i, j)[0])
                }
            }

            // Convert rotation matrix to Euler angles
            val (roll, pitch, yaw) = rotationMatrixToEulerAngles(rotMat)
            rotMat.release()
            transformMatrix.release()

            // Set values in UI
            binding.etTransformX.setText(tx.toString())
            binding.etTransformY.setText(ty.toString())
            binding.etTransformZ.setText(tz.toString())
            binding.etTransformRoll.setText(Math.toDegrees(roll).toString())
            binding.etTransformPitch.setText(Math.toDegrees(pitch).toString())
            binding.etTransformYaw.setText(Math.toDegrees(yaw).toString())
        } catch (e: Exception) {
            // If there's any error, set default values
            binding.etTransformX.setText("0.0")
            binding.etTransformY.setText("0.0")
            binding.etTransformZ.setText("0.0")
            binding.etTransformRoll.setText("0.0")
            binding.etTransformPitch.setText("0.0")
            binding.etTransformYaw.setText("0.0")
        }
    }

    private fun saveTransformMatrix() {
        try {
            val tx = binding.etTransformX.text.toString().toDoubleOrNull() ?: 0.0
            val ty = binding.etTransformY.text.toString().toDoubleOrNull() ?: 0.0
            val tz = binding.etTransformZ.text.toString().toDoubleOrNull() ?: 0.0
            val roll = Math.toRadians(binding.etTransformRoll.text.toString().toDoubleOrNull() ?: 0.0)
            val pitch = Math.toRadians(binding.etTransformPitch.text.toString().toDoubleOrNull() ?: 0.0)
            val yaw = Math.toRadians(binding.etTransformYaw.text.toString().toDoubleOrNull() ?: 0.0)

            // Create rotation matrix from Euler angles
            val rotMat = eulerAnglesToRotationMatrix(roll, pitch, yaw)

            // Create 4x4 transformation matrix
            val transformMatrix = Mat.eye(4, 4, CvType.CV_64F)

            // Copy rotation part (top-left 3x3)
            for (i in 0..2) {
                for (j in 0..2) {
                    transformMatrix.put(i, j, rotMat.get(i, j)[0])
                }
            }

            // Set translation part (4th column, first 3 rows)
            transformMatrix.put(0, 3, tx)
            transformMatrix.put(1, 3, ty)
            transformMatrix.put(2, 3, tz)

            rotMat.release()

            // Save to config
            configManager.saveTransformMatrix(transformMatrix)
            transformMatrix.release()
        } catch (e: Exception) {
            Toast.makeText(this, "変換設定の保存に失敗しました: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun eulerAnglesToRotationMatrix(roll: Double, pitch: Double, yaw: Double): Mat {
        val R = Mat(3, 3, CvType.CV_64F)

        val cosR = kotlin.math.cos(roll)
        val sinR = kotlin.math.sin(roll)
        val cosP = kotlin.math.cos(pitch)
        val sinP = kotlin.math.sin(pitch)
        val cosY = kotlin.math.cos(yaw)
        val sinY = kotlin.math.sin(yaw)

        // ZYX Euler angles rotation matrix
        R.put(0, 0, cosY * cosP)
        R.put(0, 1, cosY * sinP * sinR - sinY * cosR)
        R.put(0, 2, cosY * sinP * cosR + sinY * sinR)

        R.put(1, 0, sinY * cosP)
        R.put(1, 1, sinY * sinP * sinR + cosY * cosR)
        R.put(1, 2, sinY * sinP * cosR - cosY * sinR)

        R.put(2, 0, -sinP)
        R.put(2, 1, cosP * sinR)
        R.put(2, 2, cosP * cosR)

        return R
    }

    private fun rotationMatrixToEulerAngles(R: Mat): Triple<Double, Double, Double> {
        val sy = kotlin.math.sqrt(R.get(0, 0)[0] * R.get(0, 0)[0] + R.get(1, 0)[0] * R.get(1, 0)[0])

        val singular = sy < 1e-6

        val roll: Double
        val pitch: Double
        val yaw: Double

        if (!singular) {
            roll = kotlin.math.atan2(R.get(2, 1)[0], R.get(2, 2)[0])
            pitch = kotlin.math.atan2(-R.get(2, 0)[0], sy)
            yaw = kotlin.math.atan2(R.get(1, 0)[0], R.get(0, 0)[0])
        } else {
            roll = kotlin.math.atan2(-R.get(1, 2)[0], R.get(1, 1)[0])
            pitch = kotlin.math.atan2(-R.get(2, 0)[0], sy)
            yaw = 0.0
        }

        return Triple(roll, pitch, yaw)
    }
}
