package com.charuco.tracking.calibration

import com.charuco.tracking.utils.ConfigManager
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.CharucoParameters
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.Objdetect
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Camera calibration using ChArUco board
 */
class CameraCalibrator(private val configManager: ConfigManager) {
    private val dictionary: Dictionary
    private val board: CharucoBoard
    private val arucoDetector: ArucoDetector
    private val charucoDetector: CharucoDetector

    private val allCharucoCorners = mutableListOf<Mat>()
    private val allCharucoIds = mutableListOf<Mat>()
    private val allImagePoints = mutableListOf<Mat>()
    private val allObjectPoints = mutableListOf<Mat>()

    private var imageSize: Size? = null

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

        // Create detectors using new API
        arucoDetector = ArucoDetector(dictionary, detectorParams)
        charucoDetector = CharucoDetector(board, CharucoParameters(), detectorParams)
    }

    /**
     * Capture a calibration frame
     */
    fun captureFrame(frame: Mat): Boolean {
        if (frame.empty()) return false

        if (imageSize == null) {
            imageSize = frame.size()
        }

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
            return false
        }

        // Store the corners
        allCharucoCorners.add(charucoCorners.clone())
        allCharucoIds.add(charucoIds.clone())

        charucoCorners.release()
        charucoIds.release()

        return true
    }

    /**
     * Get number of captured frames
     */
    fun getNumFrames(): Int = allCharucoCorners.size

    /**
     * Perform camera calibration
     */
    fun calibrate(): CalibrationResult? {
        val minFrames = configManager.getMinCalibrationFrames()
        if (allCharucoCorners.size < minFrames) {
            return null
        }

        val imgSize = imageSize ?: return null

        // Get all charuco corner 3D positions from board
        val boardCorners = Mat()
        board.chessboardCorners.copyTo(boardCorners)

        // Prepare object and image points for calibration
        for (i in allCharucoCorners.indices) {
            val corners = allCharucoCorners[i]
            val ids = allCharucoIds[i]

            val objPoints = Mat(corners.rows(), 1, CvType.CV_32FC3)
            val imgPoints = Mat()
            corners.copyTo(imgPoints)

            // Map each detected corner ID to its 3D position
            for (j in 0 until ids.rows()) {
                val id = ids.get(j, 0)[0].toInt()
                val pt3d = boardCorners.get(id, 0)
                objPoints.put(j, 0, pt3d[0], pt3d[1], pt3d[2])
            }

            allObjectPoints.add(objPoints)
            allImagePoints.add(imgPoints)
        }

        boardCorners.release()

        // Initialize camera matrix
        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        val distCoeffs = Mat.zeros(5, 1, CvType.CV_64F)

        val rvecs = mutableListOf<Mat>()
        val tvecs = mutableListOf<Mat>()

        // Perform calibration
        val reprojectionError = Calib3d.calibrateCamera(
            allObjectPoints,
            allImagePoints,
            imgSize,
            cameraMatrix,
            distCoeffs,
            rvecs,
            tvecs,
            Calib3d.CALIB_RATIONAL_MODEL
        )

        // Clean up
        rvecs.forEach { it.release() }
        tvecs.forEach { it.release() }

        return CalibrationResult(
            cameraMatrix = cameraMatrix,
            distCoeffs = distCoeffs,
            reprojectionError = reprojectionError,
            numFrames = allCharucoCorners.size
        )
    }

    /**
     * Clear all captured frames
     */
    fun clear() {
        allCharucoCorners.forEach { it.release() }
        allCharucoIds.forEach { it.release() }
        allObjectPoints.forEach { it.release() }
        allImagePoints.forEach { it.release() }

        allCharucoCorners.clear()
        allCharucoIds.clear()
        allObjectPoints.clear()
        allImagePoints.clear()
    }

    /**
     * Draw detection on frame for visualization
     */
    fun drawDetection(frame: Mat): Boolean {
        if (frame.empty()) return false

        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGBA2GRAY)

        // Convert to BGR for drawing (OpenCV expects 1 or 3 channels)
        val bgr = Mat()
        Imgproc.cvtColor(frame, bgr, Imgproc.COLOR_RGBA2BGR)

        // Detect ChArUco board using new API
        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = mutableListOf<Mat>()
        val markerIds = Mat()

        charucoDetector.detectBoard(gray, charucoCorners, charucoIds, markerCorners, markerIds)


        var detected = false

        if (!markerIds.empty() && markerCorners.isNotEmpty()) {
            // Draw detected markers on BGR image
            Objdetect.drawDetectedMarkers(bgr, markerCorners, markerIds)
        }

        if (charucoCorners.rows() >= 4) {
            // Draw ChArUco corners
            Objdetect.drawDetectedCornersCharuco(
                bgr,
                charucoCorners,
                charucoIds,
                Scalar(0.0, 255.0, 0.0)
            )
            detected = true
        }

        // Convert back to RGBA
        Imgproc.cvtColor(bgr, frame, Imgproc.COLOR_BGR2RGBA)

        gray.release()
        bgr.release()
        charucoCorners.release()
        charucoIds.release()
        markerCorners.forEach { it.release() }
        markerIds.release()

        return detected
    }

    data class CalibrationResult(
        val cameraMatrix: Mat,
        val distCoeffs: Mat,
        val reprojectionError: Double,
        val numFrames: Int
    )
}
