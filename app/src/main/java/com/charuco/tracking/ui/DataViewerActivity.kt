package com.charuco.tracking.ui

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.charuco.tracking.databinding.ActivityDataViewerBinding
import com.charuco.tracking.utils.DataLoader
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

            axisRight.isEnabled = false

            // Keep aspect ratio close to 1:1 for better visualization
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = true

            setExtraOffsets(10f, 10f, 10f, 20f)
            invalidate()
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

            axisRight.isEnabled = false
            legend.isEnabled = true

            // Keep aspect ratio close to 1:1 for better visualization
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = true

            // Try to keep the same scale on both axes
            setScaleMinima(1f, 1f)

            setExtraOffsets(10f, 10f, 10f, 20f)
            invalidate()
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
}
