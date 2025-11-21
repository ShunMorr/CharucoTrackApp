package com.charuco.tracking

import android.app.Application
import android.util.Log

class CharucoTrackingApp : Application() {
    companion object {
        private const val TAG = "CharucoTrackingApp"

        init {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("opencv_java4")
                Log.d(TAG, "OpenCV library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load OpenCV library: ${e.message}")
            }
        }
    }
}
