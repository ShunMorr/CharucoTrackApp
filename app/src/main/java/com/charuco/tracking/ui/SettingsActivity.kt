package com.charuco.tracking.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.charuco.tracking.databinding.ActivitySettingsBinding
import com.charuco.tracking.utils.ConfigManager

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

            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "入力値が不正です", Toast.LENGTH_SHORT).show()
        }
    }
}
