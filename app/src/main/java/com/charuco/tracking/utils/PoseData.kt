package com.charuco.tracking.utils

import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.calib3d.Calib3d
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * Represents a 3D pose with translation and rotation
 */
data class PoseData(
    val translation: Translation,
    val rotation: Rotation,
    val quality: Double,
    val numCorners: Int,
    val timestamp: Double = System.currentTimeMillis() / 1000.0
) {
    data class Translation(
        val x: Double,  // mm
        val y: Double,  // mm
        val z: Double   // mm
    )

    data class Rotation(
        val roll: Double,   // degrees
        val pitch: Double,  // degrees
        val yaw: Double     // degrees
    )

    /**
     * Calculate displacement from another pose
     */
    fun displacementFrom(other: PoseData): Displacement {
        val dx = translation.x - other.translation.x
        val dy = translation.y - other.translation.y
        val dz = translation.z - other.translation.z
        val dyaw = rotation.yaw - other.rotation.yaw

        val distance2D = sqrt(dx * dx + dy * dy)
        val distance3D = sqrt(dx * dx + dy * dy + dz * dz)

        return Displacement(dx, dy, dz, dyaw, distance2D, distance3D)
    }

    /**
     * Apply transformation matrix to this pose
     * @param transformMatrix 4x4 homogeneous transformation matrix (board to target object)
     * @return Transformed PoseData representing the target object's pose
     */
    fun transform(transformMatrix: Mat): PoseData {
        require(transformMatrix.rows() == 4 && transformMatrix.cols() == 4) {
            "Transform matrix must be 4x4"
        }

        // Convert current pose to 4x4 transformation matrix
        val currentTransform = toTransformationMatrix()

        // Apply transformation: T_camera_to_target = T_camera_to_board * T_board_to_target
        val resultTransform = Mat()
        org.opencv.core.Core.gemm(currentTransform, transformMatrix, 1.0, Mat(), 0.0, resultTransform)

        // Extract translation and rotation from result
        val newPose = fromTransformationMatrix(resultTransform, quality, numCorners, timestamp)

        // Clean up
        currentTransform.release()
        resultTransform.release()

        return newPose
    }

    /**
     * Convert this pose to a 4x4 homogeneous transformation matrix
     */
    private fun toTransformationMatrix(): Mat {
        // Convert Euler angles to rotation matrix
        val rotMat = eulerAnglesToRotationMatrix(
            Math.toRadians(rotation.roll),
            Math.toRadians(rotation.pitch),
            Math.toRadians(rotation.yaw)
        )

        // Create 4x4 transformation matrix
        val transform = Mat.eye(4, 4, CvType.CV_64F)

        // Copy rotation part (top-left 3x3)
        for (i in 0..2) {
            for (j in 0..2) {
                transform.put(i, j, rotMat.get(i, j)[0])
            }
        }

        // Set translation part (top-right 3x1, in meters)
        transform.put(0, 3, translation.x / 1000.0)  // Convert mm to meters
        transform.put(1, 3, translation.y / 1000.0)
        transform.put(2, 3, translation.z / 1000.0)

        rotMat.release()
        return transform
    }

    data class Displacement(
        val xMm: Double,
        val yMm: Double,
        val zMm: Double,
        val yawDeg: Double,
        val distance2DMm: Double,
        val distance3DMm: Double
    )

    companion object {
        /**
         * Create PoseData from a 4x4 transformation matrix
         */
        fun fromTransformationMatrix(
            transform: Mat,
            quality: Double,
            numCorners: Int,
            timestamp: Double = System.currentTimeMillis() / 1000.0
        ): PoseData {
            // Extract translation (in mm)
            val x = transform.get(0, 3)[0] * 1000.0
            val y = transform.get(1, 3)[0] * 1000.0
            val z = transform.get(2, 3)[0] * 1000.0

            // Extract rotation matrix (top-left 3x3)
            val rotMat = Mat(3, 3, CvType.CV_64F)
            for (i in 0..2) {
                for (j in 0..2) {
                    rotMat.put(i, j, transform.get(i, j)[0])
                }
            }

            // Convert to Euler angles
            val (roll, pitch, yaw) = rotationMatrixToEulerAngles(rotMat)
            rotMat.release()

            return PoseData(
                translation = Translation(x, y, z),
                rotation = Rotation(
                    roll = Math.toDegrees(roll),
                    pitch = Math.toDegrees(pitch),
                    yaw = Math.toDegrees(yaw)
                ),
                quality = quality,
                numCorners = numCorners,
                timestamp = timestamp
            )
        }

        /**
         * Convert Euler angles to rotation matrix (ZYX convention)
         */
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

        /**
         * Convert rotation matrix to Euler angles (ZYX convention)
         */
        private fun rotationMatrixToEulerAngles(R: Mat): Triple<Double, Double, Double> {
            val sy = sqrt(R.get(0, 0)[0] * R.get(0, 0)[0] + R.get(1, 0)[0] * R.get(1, 0)[0])

            val singular = sy < 1e-6

            val roll: Double
            val pitch: Double
            val yaw: Double

            if (!singular) {
                roll = atan2(R.get(2, 1)[0], R.get(2, 2)[0])
                pitch = atan2(-R.get(2, 0)[0], sy)
                yaw = atan2(R.get(1, 0)[0], R.get(0, 0)[0])
            } else {
                roll = atan2(-R.get(1, 2)[0], R.get(1, 1)[0])
                pitch = atan2(-R.get(2, 0)[0], sy)
                yaw = 0.0
            }

            return Triple(roll, pitch, yaw)
        }
    }
}

/**
 * Statistics for spot measurement
 */
data class SpotMeasurement(
    val pose: PoseData,
    val numSamples: Int,
    val stdDev: StdDev
) {
    data class StdDev(
        val xMm: Double,
        val yMm: Double,
        val zMm: Double
    )
}

/**
 * Trajectory data
 */
data class TrajectoryData(
    val poses: List<PoseData>,
    val startTime: Double,
    val endTime: Double,
    val totalDisplacement: PoseData.Displacement
) {
    val numPoses: Int
        get() = poses.size

    val durationSec: Double
        get() = endTime - startTime
}
