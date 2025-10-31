package com.dreamelab.pwv3

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.components.Legend
import android.widget.Button
import com.dreamelab.pwv3.R
import com.hoho.android.usbserial.driver.UsbSerialPort
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.text.get
import java.io.File
import java.io.BufferedWriter


class MeasureActivity : AppCompatActivity() {
    private lateinit var lineChart: LineChart
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val entriesCh1 = ArrayList<Entry>()
    private val entriesCh2 = ArrayList<Entry>()
    private var isMeasuring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)

        lineChart = findViewById(R.id.lineChart)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        setupChart()

        btnStart.setOnClickListener {
            isMeasuring = true
            // シリアル受信開始処理をここに
        }
        btnStop.setOnClickListener {
            isMeasuring = false
            // 保存処理をここに
        }
    }

    private fun setupChart() {
        val dataSetCh1 = LineDataSet(entriesCh1, "Node 0").apply { color = Color.BLUE }
        val dataSetCh2 = LineDataSet(entriesCh2, "Node 1").apply { color = Color.RED }
        val lineData = LineData(dataSetCh1, dataSetCh2)
        lineChart.data = lineData
        lineChart.legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
        lineChart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
        lineChart.description.isEnabled = false
    }

    // 受信データをグラフに追加
    fun onDataReceived(tSec: Double, ch1: Int, ch2: Int) {
        if (!isMeasuring) return
        entriesCh1.add(Entry(tSec.toFloat(), ch1.toFloat()))
        entriesCh2.add(Entry(tSec.toFloat(), ch2.toFloat()))
        lineChart.data.notifyDataChanged()
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
    }

    private var serialPort: UsbSerialPort? = null

    private fun connectUsbSerialPort() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device) ?: return

        serialPort = driver.ports[0]
        serialPort?.open(connection)
        serialPort?.setParameters(500000, 8, 1, UsbSerialPort.PARITY_NONE)
    }

    private fun startSerialReceive() {
        // 受信スレッド例
        Thread {
            val buffer = ByteArray(64)
            while (isMeasuring) {
                val len = serialPort?.read(buffer, 1000) ?: 0
                if (len > 0) {
                    val data = String(buffer, 0, len)
                    // データ解析例
                    val (tSec, ch1, ch2) = parseSerialData(data)
                    runOnUiThread { onDataReceived(tSec, ch1, ch2) }
                }
            }
        }.start()
    }

    private fun parseSerialData(data: String): Triple<Double, Int, Int> {
        // 例: "1.23,100,200"
        val parts = data.split(",")
        return Triple(parts[0].toDouble(), parts[1].toInt(), parts[2].toInt())
    }

    private fun saveDataToCsv() {
        val file = File(getExternalFilesDir(null), "data.csv")
        file.bufferedWriter().use { out: BufferedWriter ->
            out.write("Time,Ch1,Ch2\n")
            for (i in entriesCh1.indices) {
                val t = entriesCh1[i].x
                val ch1 = entriesCh1[i].y
                val ch2 = entriesCh2[i].y
                out.write("$t,$ch1,$ch2\n")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SerialDataBus.setListener { tSec, ch1, ch2 ->
            runOnUiThread {
                onDataReceived(tSec, ch1, ch2)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SerialDataBus.setListener(null)
    }
}


