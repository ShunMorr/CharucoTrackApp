package com.charuco.tracking.detector

import com.charuco.tracking.utils.CalibrationManager
import com.charuco.tracking.utils.ConfigManager
import com.charuco.tracking.utils.PoseData
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector as OpenCVCharucoDetector
import org.opencv.objdetect.CharucoParameters
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.Objdetect
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.MatOfDouble
import kotlin.math.*

/**
 * ChArUco board detector and pose estimator
 */
class CharucoDetector(
    private val configManager: ConfigManager,
    private val calibrationData: CalibrationManager.CalibrationData
) {
    private val dictionary: Dictionary
    private val board: CharucoBoard
    private val charucoDetector: OpenCVCharucoDetector
    private val cameraMatrix: Mat
    private val distCoeffs: Mat

    init {
        // Setup ArUco dictionary
        val dictName = configManager.getDictionary()
        val dictId = when (dictName) {
            "DICT_4X4_50" -> Objdetect.DICT_4X4_50
            "DICT_5X5_100" -> Objdetect.DICT_5X5_100
            "DICT_6X6_250" -> Objdetect.DICT_6X6_250
            "DICT_7X7_1000" -> Objdetect.DICT_7X7_1000
            else -> Objdetect.DICT_5X5_100
        }
        dictionary = Objdetect.getPredefinedDictionary(dictId)

        // Create ChArUco board
        val squaresX = configManager.getSquaresX()
        val squaresY = configManager.getSquaresY()
        val squareLength = configManager.getSquareLength().toFloat()
        val markerLength = configManager.getMarkerLength().toFloat()

        board = CharucoBoard(
            Size(squaresX.toDouble(), squaresY.toDouble()),
            squareLength,
            markerLength,
            dictionary
        )

        // Setup detector parameters
        val detectorParams = DetectorParameters()
        detectorParams.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)

        // Create CharucoDetector
        charucoDetector = OpenCVCharucoDetector(board, CharucoParameters(), detectorParams)

        // Camera calibration parameters
        cameraMatrix = calibrationData.cameraMatrix
        distCoeffs = calibrationData.distCoeffs
    }

    /**
     * Detect ChArUco board and estimate pose
     */
    fun detectAndEstimatePose(frame: Mat): DetectionResult? {
        if (frame.empty()) return null

        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGBA2GRAY)

        // Detect ChArUco board using new API
        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = mutableListOf<Mat>()
        val markerIds = Mat()

        charucoDetector.detectBoard(gray, charucoCorners, charucoIds, markerCorners, markerIds)

        gray.release()
        markerCorners.forEach { it.release() }
        markerIds.release()

        if (charucoCorners.rows() < 4) {
            charucoCorners.release()
            charucoIds.release()
            return null
        }

        // Estimate pose using solvePnP
        val boardCorners = Mat()
        board.chessboardCorners.copyTo(boardCorners)

        // Create object points (3D)
        val objPointsList = mutableListOf<Point3>()
        val imgPointsList = mutableListOf<Point>()
        for (j in 0 until charucoIds.rows()) {
            val id = charucoIds.get(j, 0)[0].toInt()
            val pt3d = boardCorners.get(id, 0)
            objPointsList.add(Point3(pt3d[0], pt3d[1], pt3d[2]))
            val pt2d = charucoCorners.get(j, 0)
            imgPointsList.add(Point(pt2d[0], pt2d[1]))
        }
        boardCorners.release()

        val objPoints = MatOfPoint3f()
        objPoints.fromList(objPointsList)
        val imgPoints = MatOfPoint2f()
        imgPoints.fromList(imgPointsList)

        val rvec = Mat()
        val tvec = Mat()
        // Convert distCoeffs to MatOfDouble (distCoeffs is 1xN format)
        val distCoeffsMat = MatOfDouble(*DoubleArray(distCoeffs.cols()) { distCoeffs.get(0, it)[0] })

        val success = Calib3d.solvePnP(
            objPoints,
            imgPoints,
            cameraMatrix,
            distCoeffsMat,
            rvec,
            tvec
        )

        objPoints.release()
        imgPoints.release()

        if (!success) {
            charucoCorners.release()
            charucoIds.release()
            rvec.release()
            tvec.release()
            return null
        }

        // Convert to PoseData
        val poseData = convertToPoseData(rvec, tvec, charucoCorners, charucoIds)

        return DetectionResult(
            poseData = poseData,
            rvec = rvec,
            tvec = tvec,
            charucoCorners = charucoCorners,
            charucoIds = charucoIds,
            numCorners = charucoCorners.rows()
        )
    }

    /**
     * Convert rotation and translation vectors to PoseData
     */
    private fun convertToPoseData(
        rvec: Mat,
        tvec: Mat,
        charucoCorners: Mat,
        charucoIds: Mat
    ): PoseData {
        // Translation (convert meters to mm)
        val x = tvec.get(0, 0)[0] * 1000.0
        val y = tvec.get(1, 0)[0] * 1000.0
        val z = tvec.get(2, 0)[0] * 1000.0

        // Convert rotation vector to Euler angles
        val rotMat = Mat()
        Calib3d.Rodrigues(rvec, rotMat)

        val (roll, pitch, yaw) = rotationMatrixToEulerAngles(rotMat)
        rotMat.release()

        // Calculate quality score
        val quality = getPoseQualityScore(charucoCorners, charucoIds)

        return PoseData(
            translation = PoseData.Translation(x, y, z),
            rotation = PoseData.Rotation(
                roll = Math.toDegrees(roll),
                pitch = Math.toDegrees(pitch),
                yaw = Math.toDegrees(yaw)
            ),
            quality = quality,
            numCorners = charucoCorners.rows()
        )
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

    /**
     * Calculate pose quality score based on number of detected corners
     */
    private fun getPoseQualityScore(charucoCorners: Mat, charucoIds: Mat): Double {
        val totalCorners = (configManager.getSquaresX() - 1) * (configManager.getSquaresY() - 1)
        val detectedCorners = charucoCorners.rows()
        return minOf(detectedCorners.toDouble() / totalCorners.toDouble(), 1.0)
    }

    /**
     * Draw detection visualization on frame
     */
    fun drawDetection(
        frame: Mat,
        detectionResult: DetectionResult
    ) {
        // Convert to BGR for drawing
        val bgr = Mat()
        Imgproc.cvtColor(frame, bgr, Imgproc.COLOR_RGBA2BGR)

        // Draw detected corners
        if (!detectionResult.charucoCorners.empty()) {
            Objdetect.drawDetectedCornersCharuco(
                bgr,
                detectionResult.charucoCorners,
                detectionResult.charucoIds,
                Scalar(0.0, 255.0, 0.0)
            )
        }

        // Draw coordinate axes
        val axisLength = (configManager.getSquareLength() * 2).toFloat()
        Calib3d.drawFrameAxes(
            bgr,
            cameraMatrix,
            distCoeffs,
            detectionResult.rvec,
            detectionResult.tvec,
            axisLength
        )

        // Convert back to RGBA
        Imgproc.cvtColor(bgr, frame, Imgproc.COLOR_BGR2RGBA)
        bgr.release()
    }

    /**
     * Result of ChArUco detection
     */
    data class DetectionResult(
        val poseData: PoseData,
        val rvec: Mat,
        val tvec: Mat,
        val charucoCorners: Mat,
        val charucoIds: Mat,
        val numCorners: Int
    ) {
        fun release() {
            rvec.release()
            tvec.release()
            charucoCorners.release()
            charucoIds.release()
        }
    }

    /**
     * Generate ChArUco board image
     */
    fun generateBoardImage(imageSize: Int): Mat {
        val boardImage = Mat()
        board.generateImage(Size(imageSize.toDouble(), imageSize.toDouble()), boardImage, 10, 1)
        return boardImage
    }
}
