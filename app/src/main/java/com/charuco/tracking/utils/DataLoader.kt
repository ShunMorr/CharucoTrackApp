package com.charuco.tracking.utils

import android.content.Context
import android.net.Uri
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * Load measurement data from YAML files
 */
class DataLoader(private val context: Context) {
    private val yaml = Yaml()

    /**
     * Load trajectory data from URI
     */
    fun loadTrajectory(uri: Uri): TrajectoryData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                parseTrajectory(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Load spot measurement from URI
     */
    fun loadSpotMeasurement(uri: Uri): SpotMeasurement? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                parseSpotMeasurement(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Parse trajectory from input stream
     */
    private fun parseTrajectory(inputStream: InputStream): TrajectoryData? {
        val data = yaml.load<Map<String, Any>>(inputStream)

        // Parse metadata
        val metadata = data["metadata"] as? Map<*, *>
        val numPoses = (metadata?.get("num_poses") as? Number)?.toInt() ?: 0
        val durationSec = (metadata?.get("duration_sec") as? Number)?.toDouble() ?: 0.0

        // Parse trajectory poses
        val trajectoryList = data["trajectory"] as? List<*> ?: return null
        val poses = trajectoryList.mapNotNull { item ->
            val poseMap = item as? Map<*, *> ?: return@mapNotNull null
            parsePose(poseMap)
        }

        if (poses.isEmpty()) return null

        // Parse total displacement
        val dispMap = data["total_displacement"] as? Map<*, *>
        val displacement = if (dispMap != null) {
            parseDisplacement(dispMap)
        } else {
            // Calculate if not present
            poses.last().displacementFrom(poses.first())
        }

        val startTime = poses.firstOrNull()?.timestamp ?: 0.0
        val endTime = poses.lastOrNull()?.timestamp ?: 0.0

        return TrajectoryData(
            poses = poses,
            startTime = startTime,
            endTime = endTime,
            totalDisplacement = displacement
        )
    }

    /**
     * Parse spot measurement from input stream
     */
    private fun parseSpotMeasurement(inputStream: InputStream): SpotMeasurement? {
        val data = yaml.load<Map<String, Any>>(inputStream)

        val poseMap = data["pose"] as? Map<*, *> ?: return null
        val pose = parsePoseFromSpot(poseMap) ?: return null

        val numSamples = (poseMap["num_samples"] as? Number)?.toInt() ?: 0

        val stdDevMap = poseMap["std_dev"] as? Map<*, *>
        val stdDev = if (stdDevMap != null) {
            SpotMeasurement.StdDev(
                xMm = (stdDevMap["x_mm"] as? Number)?.toDouble() ?: 0.0,
                yMm = (stdDevMap["y_mm"] as? Number)?.toDouble() ?: 0.0,
                zMm = (stdDevMap["z_mm"] as? Number)?.toDouble() ?: 0.0
            )
        } else {
            SpotMeasurement.StdDev(0.0, 0.0, 0.0)
        }

        return SpotMeasurement(
            pose = pose,
            numSamples = numSamples,
            stdDev = stdDev
        )
    }

    /**
     * Parse pose data from map (trajectory format)
     */
    private fun parsePose(map: Map<*, *>): PoseData? {
        val translationMap = map["translation"] as? Map<*, *> ?: return null
        val rotationMap = map["rotation"] as? Map<*, *> ?: return null

        val translation = PoseData.Translation(
            x = (translationMap["x"] as? Number)?.toDouble() ?: 0.0,
            y = (translationMap["y"] as? Number)?.toDouble() ?: 0.0,
            z = (translationMap["z"] as? Number)?.toDouble() ?: 0.0
        )

        val rotation = PoseData.Rotation(
            roll = (rotationMap["roll"] as? Number)?.toDouble() ?: 0.0,
            pitch = (rotationMap["pitch"] as? Number)?.toDouble() ?: 0.0,
            yaw = (rotationMap["yaw"] as? Number)?.toDouble() ?: 0.0
        )

        val quality = (map["quality"] as? Number)?.toDouble() ?: 0.0
        val numCorners = (map["num_corners"] as? Number)?.toInt() ?: 0
        val timestamp = (map["timestamp"] as? Number)?.toDouble() ?: 0.0

        return PoseData(
            translation = translation,
            rotation = rotation,
            quality = quality,
            numCorners = numCorners,
            timestamp = timestamp
        )
    }

    /**
     * Parse pose data from spot measurement format
     */
    private fun parsePoseFromSpot(map: Map<*, *>): PoseData? {
        val translationMap = map["translation"] as? Map<*, *> ?: return null
        val rotationMap = map["rotation"] as? Map<*, *> ?: return null

        val translation = PoseData.Translation(
            x = (translationMap["x"] as? Number)?.toDouble() ?: 0.0,
            y = (translationMap["y"] as? Number)?.toDouble() ?: 0.0,
            z = (translationMap["z"] as? Number)?.toDouble() ?: 0.0
        )

        val rotation = PoseData.Rotation(
            roll = (rotationMap["roll"] as? Number)?.toDouble() ?: 0.0,
            pitch = (rotationMap["pitch"] as? Number)?.toDouble() ?: 0.0,
            yaw = (rotationMap["yaw"] as? Number)?.toDouble() ?: 0.0
        )

        val quality = (map["quality"] as? Number)?.toDouble() ?: 0.0

        return PoseData(
            translation = translation,
            rotation = rotation,
            quality = quality,
            numCorners = 0,
            timestamp = System.currentTimeMillis() / 1000.0
        )
    }

    /**
     * Parse displacement from map
     */
    private fun parseDisplacement(map: Map<*, *>): PoseData.Displacement {
        val dispMap = map["displacement"] as? Map<*, *>

        val xMm = (dispMap?.get("x_mm") as? Number)?.toDouble() ?: 0.0
        val yMm = (dispMap?.get("y_mm") as? Number)?.toDouble() ?: 0.0
        val zMm = (dispMap?.get("z_mm") as? Number)?.toDouble() ?: 0.0
        val yawDeg = (dispMap?.get("yaw_deg") as? Number)?.toDouble() ?: 0.0

        val distance2D = (map["distance_2d_mm"] as? Number)?.toDouble() ?: 0.0
        val distance3D = (map["distance_3d_mm"] as? Number)?.toDouble() ?: 0.0

        return PoseData.Displacement(
            xMm = xMm,
            yMm = yMm,
            zMm = zMm,
            yawDeg = yawDeg,
            distance2DMm = distance2D,
            distance3DMm = distance3D
        )
    }
}
