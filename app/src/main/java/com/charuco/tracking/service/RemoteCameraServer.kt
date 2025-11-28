package com.charuco.tracking.service

import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * HTTP Server for remote camera control
 */
class RemoteCameraServer(
    port: Int,
    private val measurementController: MeasurementController
) : NanoHTTPD(port) {

    private val gson = Gson()

    companion object {
        private const val TAG = "RemoteCameraServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        // Enable CORS
        val response = when {
            uri == "/api/start" && method == Method.POST -> handleStart(session)
            uri == "/api/stop" && method == Method.POST -> handleStop()
            uri == "/api/status" && method == Method.GET -> handleStatus()
            uri == "/" || uri == "/index.html" -> handleIndex()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not Found"
            )
        }

        // Add CORS headers
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")

        return response
    }

    private fun handleStart(session: IHTTPSession): Response {
        try {
            // Parse request body
            val bodyMap = mutableMapOf<String, String>()
            session.parseBody(bodyMap)
            val postData = bodyMap["postData"] ?: ""

            val json = JSONObject(postData)
            val fileName = json.optString("fileName", "")
            val delay = json.optInt("delay", 0)

            if (fileName.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"success": false, "error": "fileName is required"}"""
                )
            }

            // Start measurement
            measurementController.startMeasurement(fileName, delay)

            val response = mapOf(
                "success" to true,
                "message" to "Measurement started",
                "fileName" to fileName,
                "delay" to delay
            )

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(response)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStart", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success": false, "error": "${e.message}"}"""
            )
        }
    }

    private fun handleStop(): Response {
        try {
            measurementController.stopMeasurement()

            val response = mapOf(
                "success" to true,
                "message" to "Measurement stopped"
            )

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(response)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStop", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success": false, "error": "${e.message}"}"""
            )
        }
    }

    private fun handleStatus(): Response {
        try {
            val status = measurementController.getStatus()
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(status)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStatus", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success": false, "error": "${e.message}"}"""
            )
        }
    }

    private fun handleIndex(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Remote Camera Control</title>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        max-width: 600px;
                        margin: 50px auto;
                        padding: 20px;
                    }
                    h1 { color: #333; }
                    .form-group {
                        margin: 20px 0;
                    }
                    label {
                        display: block;
                        margin-bottom: 5px;
                        font-weight: bold;
                    }
                    input {
                        width: 100%;
                        padding: 8px;
                        border: 1px solid #ccc;
                        border-radius: 4px;
                        box-sizing: border-box;
                    }
                    button {
                        padding: 10px 20px;
                        margin: 5px;
                        border: none;
                        border-radius: 4px;
                        cursor: pointer;
                        font-size: 16px;
                    }
                    .btn-start {
                        background-color: #4CAF50;
                        color: white;
                    }
                    .btn-stop {
                        background-color: #f44336;
                        color: white;
                    }
                    .btn-status {
                        background-color: #2196F3;
                        color: white;
                    }
                    .status {
                        margin-top: 20px;
                        padding: 15px;
                        background-color: #f5f5f5;
                        border-radius: 4px;
                    }
                    .error {
                        color: #f44336;
                    }
                    .success {
                        color: #4CAF50;
                    }
                </style>
            </head>
            <body>
                <h1>Remote Camera Control</h1>

                <div class="form-group">
                    <label for="fileName">File Name:</label>
                    <input type="text" id="fileName" placeholder="e.g., spot_001" />
                </div>

                <div class="form-group">
                    <label for="delay">Delay (seconds):</label>
                    <input type="number" id="delay" value="0" min="0" />
                </div>

                <div>
                    <button class="btn-start" onclick="startMeasurement()">Start Measurement</button>
                    <button class="btn-stop" onclick="stopMeasurement()">Stop Measurement</button>
                    <button class="btn-status" onclick="getStatus()">Get Status</button>
                </div>

                <div id="status" class="status" style="display: none;"></div>

                <script>
                    async function startMeasurement() {
                        const fileName = document.getElementById('fileName').value;
                        const delay = parseInt(document.getElementById('delay').value) || 0;

                        if (!fileName) {
                            showStatus('error', 'Please enter a file name');
                            return;
                        }

                        try {
                            const response = await fetch('/api/start', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ fileName, delay })
                            });

                            const data = await response.json();
                            if (data.success) {
                                showStatus('success', 'Measurement started: ' + fileName);
                            } else {
                                showStatus('error', 'Error: ' + data.error);
                            }
                        } catch (error) {
                            showStatus('error', 'Request failed: ' + error.message);
                        }
                    }

                    async function stopMeasurement() {
                        try {
                            const response = await fetch('/api/stop', { method: 'POST' });
                            const data = await response.json();

                            if (data.success) {
                                showStatus('success', 'Measurement stopped');
                            } else {
                                showStatus('error', 'Error: ' + data.error);
                            }
                        } catch (error) {
                            showStatus('error', 'Request failed: ' + error.message);
                        }
                    }

                    async function getStatus() {
                        try {
                            const response = await fetch('/api/status');
                            const data = await response.json();

                            let statusHtml = '<strong>Status:</strong><br>';
                            statusHtml += 'Measuring: ' + (data.isMeasuring ? 'Yes' : 'No') + '<br>';

                            if (data.isMeasuring) {
                                statusHtml += 'Samples: ' + data.numSamples + '/' + data.targetSamples + '<br>';
                                statusHtml += 'Progress: ' + data.progress.toFixed(1) + '%<br>';
                                statusHtml += 'File: ' + data.currentFileName;
                            } else if (data.lastResult) {
                                statusHtml += '<br><strong>Last Result:</strong><br>';
                                statusHtml += 'File: ' + data.lastResult.fileName + '<br>';
                                statusHtml += 'Position: X=' + data.lastResult.position.x.toFixed(2) +
                                    'mm, Y=' + data.lastResult.position.y.toFixed(2) +
                                    'mm, Z=' + data.lastResult.position.z.toFixed(2) + 'mm<br>';
                                statusHtml += 'Rotation: Yaw=' + data.lastResult.rotation.yaw.toFixed(2) + '°';
                            }

                            document.getElementById('status').innerHTML = statusHtml;
                            document.getElementById('status').style.display = 'block';
                        } catch (error) {
                            showStatus('error', 'Request failed: ' + error.message);
                        }
                    }

                    function showStatus(type, message) {
                        const statusDiv = document.getElementById('status');
                        statusDiv.className = 'status ' + type;
                        statusDiv.innerHTML = message;
                        statusDiv.style.display = 'block';
                    }

                    // Auto-refresh status every 2 seconds when measuring
                    setInterval(async () => {
                        try {
                            const response = await fetch('/api/status');
                            const data = await response.json();
                            if (data.isMeasuring) {
                                getStatus();
                            }
                        } catch (error) {
                            // Silently ignore errors in auto-refresh
                        }
                    }, 2000);
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
}

/**
 * Interface for controlling spot measurements
 */
interface MeasurementController {
    fun startMeasurement(fileName: String, delay: Int)
    fun stopMeasurement()
    fun getStatus(): Map<String, Any>
}
