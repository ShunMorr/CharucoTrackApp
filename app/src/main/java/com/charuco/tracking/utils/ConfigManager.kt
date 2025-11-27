package com.charuco.tracking.utils

import android.content.Context
import android.content.SharedPreferences
import org.yaml.snakeyaml.Yaml
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream

/**
 * Manages application configuration
 */
class ConfigManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("charuco_config", Context.MODE_PRIVATE)

    companion object {
        // ChArUco Board Configuration
        const val KEY_DICTIONARY = "dictionary"
        const val KEY_SQUARES_X = "squares_x"
        const val KEY_SQUARES_Y = "squares_y"
        const val KEY_SQUARE_LENGTH = "square_length"
        const val KEY_MARKER_LENGTH = "marker_length"

        // Camera Configuration
        const val KEY_CAMERA_WIDTH = "camera_width"
        const val KEY_CAMERA_HEIGHT = "camera_height"
        const val KEY_CAMERA_FPS = "camera_fps"

        // Tracking Configuration
        const val KEY_TRACKING_FPS = "tracking_fps"
        const val KEY_MIN_CALIBRATION_FRAMES = "min_calibration_frames"

        // Save Configuration
        const val KEY_SAVE_PATH = "save_path"

        // Transform Configuration (Board to Target Object)
        const val KEY_TRANSFORM_ENABLED = "transform_enabled"
        const val KEY_TRANSFORM_MATRIX = "transform_matrix"

        // Default values
        const val DEFAULT_DICTIONARY = "DICT_5X5_100"
        const val DEFAULT_SQUARES_X = 7
        const val DEFAULT_SQUARES_Y = 7
        const val DEFAULT_SQUARE_LENGTH = 0.04  // meters
        const val DEFAULT_MARKER_LENGTH = 0.03  // meters
        const val DEFAULT_CAMERA_WIDTH = 1920
        const val DEFAULT_CAMERA_HEIGHT = 1280
        const val DEFAULT_CAMERA_FPS = 30
        const val DEFAULT_TRACKING_FPS = 10
        const val DEFAULT_MIN_CALIBRATION_FRAMES = 30
    }

    // ChArUco Board Configuration
    fun getDictionary(): String = prefs.getString(KEY_DICTIONARY, DEFAULT_DICTIONARY) ?: DEFAULT_DICTIONARY
    fun getSquaresX(): Int = prefs.getInt(KEY_SQUARES_X, DEFAULT_SQUARES_X)
    fun getSquaresY(): Int = prefs.getInt(KEY_SQUARES_Y, DEFAULT_SQUARES_Y)
    fun getSquareLength(): Double = prefs.getFloat(KEY_SQUARE_LENGTH, DEFAULT_SQUARE_LENGTH.toFloat()).toDouble()
    fun getMarkerLength(): Double = prefs.getFloat(KEY_MARKER_LENGTH, DEFAULT_MARKER_LENGTH.toFloat()).toDouble()

    // Camera Configuration
    fun getCameraWidth(): Int = prefs.getInt(KEY_CAMERA_WIDTH, DEFAULT_CAMERA_WIDTH)
    fun getCameraHeight(): Int = prefs.getInt(KEY_CAMERA_HEIGHT, DEFAULT_CAMERA_HEIGHT)
    fun getCameraFps(): Int = prefs.getInt(KEY_CAMERA_FPS, DEFAULT_CAMERA_FPS)

    // Tracking Configuration
    fun getTrackingFps(): Int = prefs.getInt(KEY_TRACKING_FPS, DEFAULT_TRACKING_FPS)
    fun getMinCalibrationFrames(): Int = prefs.getInt(KEY_MIN_CALIBRATION_FRAMES, DEFAULT_MIN_CALIBRATION_FRAMES)

    // Save Configuration
    fun getSavePath(): String? = prefs.getString(KEY_SAVE_PATH, null)

    // Transform Configuration
    fun isTransformEnabled(): Boolean = prefs.getBoolean(KEY_TRANSFORM_ENABLED, false)

    /**
     * Get transformation matrix (Board to Target Object)
     * Returns identity matrix if not set
     */
    fun getTransformMatrix(): Mat {
        val matrixJson = prefs.getString(KEY_TRANSFORM_MATRIX, null)
        return if (matrixJson != null) {
            matrixFromJson(matrixJson)
        } else {
            // Return identity matrix as default (no transformation)
            Mat.eye(4, 4, CvType.CV_64F)
        }
    }

    /**
     * Save transformation matrix
     */
    fun saveTransformMatrix(matrix: Mat) {
        require(matrix.rows() == 4 && matrix.cols() == 4) {
            "Transform matrix must be 4x4"
        }
        val matrixJson = matrixToJson(matrix)
        prefs.edit().putString(KEY_TRANSFORM_MATRIX, matrixJson).apply()
    }

    /**
     * Set transform enabled/disabled
     */
    fun setTransformEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSFORM_ENABLED, enabled).apply()
    }

    /**
     * Convert Mat to JSON string
     */
    private fun matrixToJson(matrix: Mat): String {
        val jsonArray = JSONArray()
        for (i in 0 until matrix.rows()) {
            val row = JSONArray()
            for (j in 0 until matrix.cols()) {
                row.put(matrix.get(i, j)[0])
            }
            jsonArray.put(row)
        }
        return jsonArray.toString()
    }

    /**
     * Convert JSON string to Mat
     */
    private fun matrixFromJson(json: String): Mat {
        val jsonArray = JSONArray(json)
        val rows = jsonArray.length()
        val cols = jsonArray.getJSONArray(0).length()
        val matrix = Mat(rows, cols, CvType.CV_64F)

        for (i in 0 until rows) {
            val row = jsonArray.getJSONArray(i)
            for (j in 0 until cols) {
                matrix.put(i, j, row.getDouble(j))
            }
        }
        return matrix
    }

    /**
     * Save configuration values
     */
    fun saveConfig(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            }
            apply()
        }
    }
}
