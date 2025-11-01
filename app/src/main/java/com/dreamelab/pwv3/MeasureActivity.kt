package com.dreamelab.pwv3

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dreamelab.pwv3.view.WaveformView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet

class MeasureActivity : AppCompatActivity() {
    companion object { private const val TAG = "MeasureActivity" }

    private lateinit var tvSubject: TextView
    private lateinit var chart: LineChart
    private lateinit var btnBack: Button
    private lateinit var btnStart: Button
    private lateinit var btnStopSave: Button
    private lateinit var waveformView: WaveformView

    private val entries = mutableListOf<Entry>()
    private var nextX = 0f
    private var measuring = false
    private val sampleIntervalMs: Long = 200L

    // SerialManager callbacks (keep same instance for register/unregister)
    private val serialOnData: (Double, Double, Double) -> Unit = { tSec, ch1, ch2 ->
        runOnUiThread {
            try {
                // WaveformView の API に合わせて安全に呼ぶ
                waveformView.addSamplesSafe(ch1.toFloat(), ch2.toFloat())
                // 必要ならチャートへも追加（例として ch1 を追加）
                addEntry(ch1.toFloat())
            } catch (e: Exception) {
                Log.w(TAG, "append sample failed", e)
            }
        }
    }
    private val serialOnStatus: (String) -> Unit = { msg ->
        runOnUiThread {
            Log.d(TAG, "serial status: $msg")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)
        waveformView = findViewById(R.id.waveformView)
        chart = findViewById(R.id.lineChart)
        tvSubject = findViewById(R.id.tv_subject)
        btnBack = findViewById(R.id.btn_back)
        btnStart = findViewById(R.id.btn_start)
        btnStopSave = findViewById(R.id.btn_stop)

        setupChart()

        btnBack.setOnClickListener {
            finish()
        }
        btnStart.setOnClickListener { startMeasuring() }
        btnStopSave.setOnClickListener {
            if (measuring) stopMeasuring() else btnStopSave.text = "SAVE" // 実装に合わせて処理追加
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            SerialManager.register(this, serialOnData, serialOnStatus)
            Log.d(TAG, "SerialManager registered")
            // 必要ならここでデバイス検出→許可→接続開始
            // SerialManager.findAndRequestPermissionAndOpen()
        } catch (e: Exception) {
            Log.w(TAG, "SerialManager.register failed", e)
        }
    }

    override fun onStop() {
        try {
            SerialManager.unregister(serialOnData, serialOnStatus)
            Log.d(TAG, "SerialManager unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "SerialManager.unregister failed", e)
        }
        super.onStop()
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        val x = chart.xAxis
        x.position = XAxis.XAxisPosition.BOTTOM
        x.setDrawGridLines(true)
        x.granularity = 1f
        val set = LineDataSet(mutableListOf(), "measurement").apply {
            mode = LineDataSet.Mode.LINEAR
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            color = 0xFF5E35B1.toInt()
        }
        chart.data = LineData(set as ILineDataSet)
        chart.invalidate()
    }

    private fun addEntry(value: Float) {
        val data = chart.data ?: return
        if (data.dataSetCount == 0) return
        val set = data.getDataSetByIndex(0)
        val entry = Entry(nextX, value)
        set.addEntry(entry)
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(120f)
        chart.moveViewToX(nextX)
        entries.add(entry)
        nextX += (sampleIntervalMs.toFloat() / 1000f)
    }

    private fun startMeasuring() {
        if (measuring) return
        entries.clear()
        nextX = 0f
        chart.data?.clearValues()
        chart.data?.getDataSetByIndex(0)?.clear()
        chart.notifyDataSetChanged()
        chart.invalidate()
        measuring = true
    }

    private fun stopMeasuring() {
        if (!measuring) return
        measuring = false
    }

    // WaveformView のメソッド名が不定の場合に備えた安全ラッパー
    private fun WaveformView.addSamplesSafe(ch1: Float, ch2: Float) {
        try {
            // まず通常想定されるメソッドを直接呼ぶ
            try {
                val m = this::class.java.getMethod("addSamples", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                m.invoke(this, ch1, ch2)
                return
            } catch (_: NoSuchMethodException) {}

            // 単一値メソッドを連続して呼ぶパターン
            val singleNames = arrayOf("addSample", "pushSample", "addPoint")
            for (n in singleNames) {
                try {
                    val m = this::class.java.getMethod(n, Float::class.javaPrimitiveType)
                    m.invoke(this, ch1)
                    m.invoke(this, ch2)
                    return
                } catch (_: NoSuchMethodException) {}
            }

            // 別名の二引数メソッド
            val pairNames = arrayOf("addData", "push")
            for (n in pairNames) {
                try {
                    val m = this::class.java.getMethod(n, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    m.invoke(this, ch1, ch2)
                    return
                } catch (_: NoSuchMethodException) {}
            }

            // 見つからなければログのみ
            Log.w(TAG, "WaveformView: no suitable addSamples method found")
        } catch (e: Exception) {
            Log.w(TAG, "addSamplesSafe failed", e)
        }
    }
}
