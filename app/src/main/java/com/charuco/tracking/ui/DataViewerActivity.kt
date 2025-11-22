package com.charuco.tracking.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.charuco.tracking.databinding.ActivityDataViewerBinding
import com.charuco.tracking.utils.ConfigManager
import com.charuco.tracking.utils.DataLoader
import com.charuco.tracking.utils.FileUtils
import com.charuco.tracking.utils.SpotMeasurement
import com.charuco.tracking.utils.TrajectoryData
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.IScatterDataSet
import com.google.android.material.tabs.TabLayout

class DataViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDataViewerBinding
    private lateinit var dataLoader: DataLoader
    private lateinit var configManager: ConfigManager

    // Trajectory data
    private var trajectoryData: TrajectoryData? = null
    private var currentAxis = "X"
    private var qualityThreshold = 0.0

    // Spot measurement data
    private var referenceSpot: SpotMeasurement? = null
    private val comparisonSpots = mutableListOf<Pair<String, SpotMeasurement>>()
    private val axisNames = listOf("X", "Y", "Z", "ROLL", "PITCH", "YAW")

    private val trajectoryFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadTrajectoryFile(it) }
    }

    private val referenceFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadReferenceFile(it) }
    }

    private val comparisonFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadComparisonFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataLoader = DataLoader(this)
        configManager = ConfigManager(this)

        setupUI()
    }

    private fun setupUI() {
        // Data type selection
        binding.rgDataType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.rbTrajectory.id -> showTrajectorySection()
                binding.rbSpot.id -> showSpotSection()
            }
        }

        // Trajectory section
        setupTrajectoryTab()
        setupQualityFilter()
        binding.btnLoadTrajectory.setOnClickListener {
            trajectoryFileLauncher.launch(arrayOf("*/*"))
        }
        binding.btnSaveTrajectoryChart.setOnClickListener {
            showSaveChartDialog("trajectory_chart") { fileName ->
                saveTrajectoryChartAsPng(fileName)
            }
        }

        // Spot section
        setupAxisSpinners()
        binding.btnLoadReference.setOnClickListener {
            referenceFileLauncher.launch(arrayOf("*/*"))
        }
        binding.btnLoadComparison.setOnClickListener {
            comparisonFileLauncher.launch(arrayOf("*/*"))
        }
        binding.btnPlotSpot.setOnClickListener {
            plotSpotData()
        }
        binding.btnSaveSpotChart.setOnClickListener {
            showSaveChartDialog("spot_chart") { fileName ->
                saveSpotChartAsPng(fileName)
            }
        }

        // Show trajectory by default
        showTrajectorySection()
    }

    private fun showTrajectorySection() {
        binding.trajectorySection.visibility = View.VISIBLE
        binding.spotSection.visibility = View.GONE
    }

    private fun showSpotSection() {
        binding.trajectorySection.visibility = View.GONE
        binding.spotSection.visibility = View.VISIBLE
    }

    private fun setupTrajectoryTab() {
        val tabs = listOf("X", "Y", "Z", "Roll", "Pitch", "Yaw", "Quality")
        tabs.forEach { binding.tabLayout.addTab(binding.tabLayout.newTab().setText(it)) }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentAxis = tab?.text.toString()
                plotTrajectoryData()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupQualityFilter() {
        binding.seekBarQuality.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                qualityThreshold = progress / 100.0
                binding.tvQualityValue.text = "%.2f".format(qualityThreshold)
                if (fromUser) {
                    plotTrajectoryData()
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun setupAxisSpinners() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, axisNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerXAxis.adapter = adapter
        binding.spinnerYAxis.adapter = adapter
        binding.spinnerYAxis.setSelection(1) // Default to Y
    }

    // ===== Trajectory Data Loading =====

    private fun loadTrajectoryFile(uri: Uri) {
        val data = dataLoader.loadTrajectory(uri)
        if (data != null) {
            trajectoryData = data
            Toast.makeText(this, "軌跡データを読み込みました (${data.numPoses} poses)", Toast.LENGTH_SHORT).show()
            plotTrajectoryData()
        } else {
            Toast.makeText(this, "軌跡データの読み込みに失敗しました", Toast.LENGTH_LONG).show()
        }
    }

    private fun plotTrajectoryData() {
        val data = trajectoryData ?: return

        // Filter poses by quality threshold
        val filteredPoses = data.poses.filter { it.quality > qualityThreshold }

        if (filteredPoses.isEmpty()) {
            Toast.makeText(this, "フィルタ後のデータがありません。閾値を下げてください。", Toast.LENGTH_SHORT).show()
            return
        }

        // Calculate relative time from first timestamp
        val startTime = filteredPoses.firstOrNull()?.timestamp ?: 0.0

        val entries = when (currentAxis) {
            "X" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.translation.x.toFloat()) }
            "Y" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.translation.y.toFloat()) }
            "Z" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.translation.z.toFloat()) }
            "Roll" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.rotation.roll.toFloat()) }
            "Pitch" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.rotation.pitch.toFloat()) }
            "Yaw" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.rotation.yaw.toFloat()) }
            "Quality" -> filteredPoses.map { pose -> Entry((pose.timestamp - startTime).toFloat(), pose.quality.toFloat()) }
            else -> return
        }

        val dataSet = LineDataSet(entries, currentAxis).apply {
            color = Color.BLUE
            setCircleColor(Color.BLUE)
            circleRadius = 2f
            lineWidth = 2f
            setDrawValues(false)
        }

        val yAxisLabel = when (currentAxis) {
            "X", "Y", "Z" -> "$currentAxis (mm)"
            "Roll", "Pitch", "Yaw" -> "$currentAxis (deg)"
            "Quality" -> "Quality (0-1)"
            else -> currentAxis
        }

        // Update axis labels with filtered data count
        val totalCount = data.numPoses
        val filteredCount = filteredPoses.size
        binding.tvTrajectoryYAxisLabel.text = "Y軸: $yAxisLabel (データ数: $filteredCount / $totalCount)"
        binding.tvTrajectoryXAxisLabel.text = "X軸: 時間 (秒)"

        binding.trajectoryChart.apply {
            this.data = LineData(dataSet)
            description.isEnabled = false

            // Calculate axis ranges with 1.1x margin
            if (entries.isNotEmpty()) {
                val xValues = entries.map { it.x }
                val yValues = entries.map { it.y }

                val xMin = xValues.minOrNull() ?: 0f
                val xMax = xValues.maxOrNull() ?: 0f
                val xRange = xMax - xMin
                val xMargin = xRange * 0.05f // 5% margin on each side = 10% total

                val yMin = yValues.minOrNull() ?: 0f
                val yMax = yValues.maxOrNull() ?: 0f
                val yRange = yMax - yMin
                val yMargin = yRange * 0.05f

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    granularity = 0.1f
                    axisMinimum = xMin - xMargin
                    axisMaximum = xMax + xMargin
                }

                axisLeft.apply {
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    axisMinimum = yMin - yMargin
                    axisMaximum = yMax + yMargin
                }
            } else {
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    granularity = 0.1f
                }

                axisLeft.apply {
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                }
            }

            axisRight.isEnabled = false

            legend.isEnabled = false

            // Keep aspect ratio close to 1:1 for better visualization
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = true

            setExtraOffsets(10f, 10f, 10f, 20f)
            invalidate()
        }

        // Calculate and display statistics
        if (entries.size >= 2) {
            val firstValue = entries.first().y
            val lastValue = entries.last().y
            val diff = lastValue - firstValue
            val unit = when (currentAxis) {
                "X", "Y", "Z" -> "mm"
                "Roll", "Pitch", "Yaw" -> "deg"
                "Quality" -> ""
                else -> ""
            }
            binding.tvTrajectoryStats.text = "開始と最終の差分: %.4f %s".format(diff, unit)
        } else {
            binding.tvTrajectoryStats.text = ""
        }
    }

    // ===== Spot Measurement Data Loading =====

    private fun loadReferenceFile(uri: Uri) {
        val spot = dataLoader.loadSpotMeasurement(uri)
        if (spot != null) {
            referenceSpot = spot
            val fileName = getFileName(uri)
            binding.tvReferenceFile.text = "基準: $fileName"
            Toast.makeText(this, "基準ファイルを読み込みました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "基準ファイルの読み込みに失敗しました", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadComparisonFile(uri: Uri) {
        val spot = dataLoader.loadSpotMeasurement(uri)
        if (spot != null) {
            val fileName = getFileName(uri)
            comparisonSpots.add(Pair(fileName, spot))
            updateComparisonFilesList()
            Toast.makeText(this, "比較ファイルを追加しました: $fileName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "比較ファイルの読み込みに失敗しました", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateComparisonFilesList() {
        val fileNames = comparisonSpots.joinToString("\n") { it.first }
        binding.tvComparisonFiles.text = if (fileNames.isNotEmpty()) fileNames else "未選択"
    }

    private fun getFileName(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return it.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment ?: "Unknown"
    }

    private fun plotSpotData() {
        val reference = referenceSpot
        if (reference == null) {
            Toast.makeText(this, "基準ファイルを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (comparisonSpots.isEmpty()) {
            Toast.makeText(this, "比較ファイルを追加してください", Toast.LENGTH_SHORT).show()
            return
        }

        val xAxisName = axisNames[binding.spinnerXAxis.selectedItemPosition]
        val yAxisName = axisNames[binding.spinnerYAxis.selectedItemPosition]

        val entries = mutableListOf<ScatterDataSet>()

        // Add reference point (origin)
        val refEntry = listOf(Entry(0f, 0f))
        val refDataSet = ScatterDataSet(refEntry, "基準: ${getAxisLabel(xAxisName)}=${getValue(reference, xAxisName).toFloat()}, ${getAxisLabel(yAxisName)}=${getValue(reference, yAxisName).toFloat()}").apply {
            setScatterShape(ScatterChart.ScatterShape.CIRCLE)
            scatterShapeSize = 15f
            color = Color.RED
            setDrawValues(false)
        }
        entries.add(refDataSet)

        // Add comparison points
        val colors = listOf(Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.YELLOW)
        comparisonSpots.forEachIndexed { index, (name, spot) ->
            val xDiff = (getValue(spot, xAxisName) - getValue(reference, xAxisName)).toFloat()
            val yDiff = (getValue(spot, yAxisName) - getValue(reference, yAxisName)).toFloat()

            val entry = listOf(Entry(xDiff, yDiff))
            val dataSet = ScatterDataSet(entry, name).apply {
                setScatterShape(ScatterChart.ScatterShape.SQUARE)
                scatterShapeSize = 12f
                color = colors[index % colors.size]
                setDrawValues(false)
            }
            entries.add(dataSet)
        }

        // Update axis labels
        binding.tvSpotXAxisLabel.text = "X軸: 差分 ${getAxisLabel(xAxisName)}"
        binding.tvSpotYAxisLabel.text = "Y軸: 差分 ${getAxisLabel(yAxisName)}"

        binding.spotChart.apply {
            data = ScatterData(entries as List<IScatterDataSet>)
            description.isEnabled = false

            // Calculate axis ranges with 1.1x margin
            val allXValues = mutableListOf<Float>()
            val allYValues = mutableListOf<Float>()
            entries.forEach { dataset ->
                dataset.values.forEach { entry ->
                    allXValues.add(entry.x)
                    allYValues.add(entry.y)
                }
            }

            if (allXValues.isNotEmpty() && allYValues.isNotEmpty()) {
                val xMin = allXValues.minOrNull() ?: 0f
                val xMax = allXValues.maxOrNull() ?: 0f
                val xRange = xMax - xMin
                val xMargin = if (xRange > 0) xRange * 0.05f else 1f

                val yMin = allYValues.minOrNull() ?: 0f
                val yMax = allYValues.maxOrNull() ?: 0f
                val yRange = yMax - yMin
                val yMargin = if (yRange > 0) yRange * 0.05f else 1f

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "%.2f".format(value)
                        }
                    }
                    axisMinimum = xMin - xMargin
                    axisMaximum = xMax + xMargin
                }

                axisLeft.apply {
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "%.2f".format(value)
                        }
                    }
                    axisMinimum = yMin - yMargin
                    axisMaximum = yMax + yMargin
                }
            } else {
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "%.2f".format(value)
                        }
                    }
                }

                axisLeft.apply {
                    setDrawGridLines(true)
                    setDrawLabels(true)
                    textSize = 10f
                    textColor = Color.YELLOW
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "%.2f".format(value)
                        }
                    }
                }
            }

            axisRight.isEnabled = false

            legend.isEnabled = false

            // Keep aspect ratio close to 1:1 for better visualization
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = true

            // Try to keep the same scale on both axes
            setScaleMinima(1f, 1f)

            setExtraOffsets(10f, 10f, 10f, 20f)
            invalidate()
        }

        // Calculate and display statistics for differences
        if (comparisonSpots.isNotEmpty()) {
            val xDiffs = mutableListOf<Double>()
            val yDiffs = mutableListOf<Double>()

            comparisonSpots.forEach { (_, spot) ->
                xDiffs.add(getValue(spot, xAxisName) - getValue(reference, xAxisName))
                yDiffs.add(getValue(spot, yAxisName) - getValue(reference, yAxisName))
            }

            // Calculate statistics for both axes
            val xMean = xDiffs.average()
            val xMin = xDiffs.minOrNull() ?: 0.0
            val xMax = xDiffs.maxOrNull() ?: 0.0
            val xVariance = xDiffs.map { (it - xMean) * (it - xMean) }.average()

            val yMean = yDiffs.average()
            val yMin = yDiffs.minOrNull() ?: 0.0
            val yMax = yDiffs.maxOrNull() ?: 0.0
            val yVariance = yDiffs.map { (it - yMean) * (it - yMean) }.average()

            val xUnit = getAxisUnit(xAxisName)
            val yUnit = getAxisUnit(yAxisName)

            binding.tvSpotStats.text = """
                X軸(${xAxisName}): 平均=%.4f%s, 分散=%.4f, 最小=%.4f%s, 最大=%.4f%s
                Y軸(${yAxisName}): 平均=%.4f%s, 分散=%.4f, 最小=%.4f%s, 最大=%.4f%s
            """.trimIndent().format(xMean, xUnit, xVariance, xMin, xUnit, xMax, xUnit,
                                     yMean, yUnit, yVariance, yMin, yUnit, yMax, yUnit)
        } else {
            binding.tvSpotStats.text = ""
        }
    }

    private fun getValue(spot: SpotMeasurement, axis: String): Double {
        return when (axis) {
            "X" -> spot.pose.translation.x
            "Y" -> spot.pose.translation.y
            "Z" -> spot.pose.translation.z
            "ROLL" -> spot.pose.rotation.roll
            "PITCH" -> spot.pose.rotation.pitch
            "YAW" -> spot.pose.rotation.yaw
            else -> 0.0
        }
    }

    private fun getAxisLabel(axis: String): String {
        return when (axis) {
            "X", "Y", "Z" -> "$axis (mm)"
            "ROLL", "PITCH", "YAW" -> "$axis (deg)"
            else -> axis
        }
    }

    private fun getAxisUnit(axis: String): String {
        return when (axis) {
            "X", "Y", "Z" -> "mm"
            "ROLL", "PITCH", "YAW" -> "deg"
            else -> ""
        }
    }

    private fun showSaveChartDialog(defaultName: String, onSave: (String) -> Unit) {
        val input = EditText(this)
        input.setText(defaultName)
        input.selectAll()

        AlertDialog.Builder(this)
            .setTitle("ファイル名を入力")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isNotEmpty()) {
                    val sanitizedFileName = FileUtils.sanitizeFileName(fileName)
                    onSave(sanitizedFileName)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun saveTrajectoryChartAsPng(fileName: String) {
        val chart = binding.trajectoryChart
        val data = trajectoryData ?: return

        // Save current settings
        val originalXAxisTextColor = chart.xAxis.textColor
        val originalYAxisTextColor = chart.axisLeft.textColor
        val originalDescriptionEnabled = chart.description.isEnabled

        try {
            // Temporarily change chart settings for export
            chart.xAxis.textColor = Color.BLACK
            chart.axisLeft.textColor = Color.BLACK

            // Add description with statistics only
            val stats = binding.tvTrajectoryStats.text.toString()

            chart.description.apply {
                isEnabled = true
                text = stats
                textColor = Color.BLACK
                textSize = 12f
            }

            // Set axis labels
            chart.xAxis.apply {
                setDrawLabels(true)
                textColor = Color.BLACK
            }
            chart.axisLeft.apply {
                setDrawLabels(true)
                textColor = Color.BLACK
            }

            // Refresh chart
            chart.invalidate()

            // Get bitmap
            val chartBitmap = chart.chartBitmap
            if (chartBitmap == null) {
                Toast.makeText(this, "グラフが表示されていません", Toast.LENGTH_SHORT).show()
                return
            }

            // Add axis labels to bitmap
            val xAxisLabel = binding.tvTrajectoryXAxisLabel.text.toString()
            val yAxisLabel = binding.tvTrajectoryYAxisLabel.text.toString()
            val bitmapWithLabels = addAxisLabelsToBitmap(chartBitmap, xAxisLabel, yAxisLabel)

            // Recycle original bitmap to free memory
            chartBitmap.recycle()

            // Save to file
            saveBitmapToFile(bitmapWithLabels, fileName)

            // Recycle final bitmap after saving
            bitmapWithLabels.recycle()

        } finally {
            // Restore original settings
            chart.xAxis.textColor = originalXAxisTextColor
            chart.axisLeft.textColor = originalYAxisTextColor
            chart.description.isEnabled = originalDescriptionEnabled
            chart.invalidate()
        }
    }

    private fun saveSpotChartAsPng(fileName: String) {
        val chart = binding.spotChart

        // Save current settings
        val originalXAxisTextColor = chart.xAxis.textColor
        val originalYAxisTextColor = chart.axisLeft.textColor
        val originalDescriptionEnabled = chart.description.isEnabled

        try {
            // Temporarily change chart settings for export
            chart.xAxis.textColor = Color.BLACK
            chart.axisLeft.textColor = Color.BLACK

            // Add description with statistics only
            val stats = binding.tvSpotStats.text.toString().replace("\n", " / ")

            chart.description.apply {
                isEnabled = true
                text = stats
                textColor = Color.BLACK
                textSize = 10f
            }

            // Set axis labels
            chart.xAxis.apply {
                setDrawLabels(true)
                textColor = Color.BLACK
            }
            chart.axisLeft.apply {
                setDrawLabels(true)
                textColor = Color.BLACK
            }

            // Refresh chart
            chart.invalidate()

            // Get bitmap
            val chartBitmap = chart.chartBitmap
            if (chartBitmap == null) {
                Toast.makeText(this, "グラフが表示されていません", Toast.LENGTH_SHORT).show()
                return
            }

            // Add axis labels to bitmap
            val xAxisLabel = binding.tvSpotXAxisLabel.text.toString()
            val yAxisLabel = binding.tvSpotYAxisLabel.text.toString()
            val bitmapWithLabels = addAxisLabelsToBitmap(chartBitmap, xAxisLabel, yAxisLabel)

            // Recycle original bitmap to free memory
            chartBitmap.recycle()

            // Save to file
            saveBitmapToFile(bitmapWithLabels, fileName)

            // Recycle final bitmap after saving
            bitmapWithLabels.recycle()

        } finally {
            // Restore original settings
            chart.xAxis.textColor = originalXAxisTextColor
            chart.axisLeft.textColor = originalYAxisTextColor
            chart.description.isEnabled = originalDescriptionEnabled
            chart.invalidate()
        }
    }

    private fun addAxisLabelsToBitmap(chartBitmap: Bitmap, xAxisLabel: String, yAxisLabel: String): Bitmap {
        // Calculate new bitmap size with space for axis labels
        val xLabelHeight = 60 // Space for X-axis label at bottom
        val yLabelWidth = 60  // Space for Y-axis label on left

        val newWidth = chartBitmap.width + yLabelWidth
        val newHeight = chartBitmap.height + xLabelHeight

        // Create new bitmap with extra space
        val newBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)

        // Fill with white background
        canvas.drawColor(Color.WHITE)

        // Draw original chart bitmap (offset to make room for Y-axis label)
        canvas.drawBitmap(chartBitmap, yLabelWidth.toFloat(), 0f, null)

        // Prepare paint for text
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Draw X-axis label (bottom center)
        val xLabelX = newWidth / 2f
        val xLabelY = chartBitmap.height + 45f
        canvas.drawText(xAxisLabel, xLabelX, xLabelY, textPaint)

        // Draw Y-axis label (left side, rotated 90 degrees counter-clockwise)
        canvas.save()
        canvas.rotate(-90f, yLabelWidth / 2f, newHeight / 2f)
        canvas.drawText(yAxisLabel, yLabelWidth / 2f, newHeight / 2f + 12f, textPaint)
        canvas.restore()

        return newBitmap
    }

    private fun saveBitmapToFile(bitmap: Bitmap, fileName: String) {
        try {
            val finalFileName = if (fileName.endsWith(".png")) fileName else "$fileName.png"

            // Save to configured path (same as tracking data)
            val savePath = configManager.getSavePath()
            if (savePath != null) {
                // Use SAF URI
                val uri = Uri.parse(savePath)
                val dir = DocumentFile.fromTreeUri(this, uri)
                val docFile = dir?.createFile("image/png", finalFileName)
                docFile?.uri?.let { fileUri ->
                    contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                    Toast.makeText(this, "グラフを保存しました: $finalFileName", Toast.LENGTH_LONG).show()
                }
            } else {
                // Default path if not configured
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), finalFileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Toast.makeText(this, "グラフを保存しました: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
