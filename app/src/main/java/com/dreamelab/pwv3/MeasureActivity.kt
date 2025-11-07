package com.dreamelab.pwv3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Color
import android.os.Build
import android.annotation.SuppressLint
import android.widget.ImageView
import android.view.View


class MeasureActivity : AppCompatActivity() {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.dreamelab.pwv3.USB_PERMISSION"
        private const val TAG = "MeasureActivity"
    }

    // UI
    private lateinit var tvSubject: TextView
    private lateinit var chart: LineChart
    private lateinit var btnBack: Button
    private lateinit var btnStart: Button
    private lateinit var btnStopSave: Button

    // measurement state
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var measuring = false
    private val sampleIntervalMs: Long = 200L
    private val entries = mutableListOf<Entry>()
    private var nextX = 0f

    // USB / serial (local usage still present but we will primarily use SerialManager callbacks)
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var serialPort: UsbSerialPort? = null
    private var permissionPendingIntent: PendingIntent? = null
    private var usbReceiverRegistered = false
    @Volatile private var ioRunning = false
    private var ioThread: Thread? = null

    // --- serialOnData を波形更新なしに置換 ---
    private val serialOnData: (Double, Double, Double) -> Unit = { tSec, ch1, ch2 ->
        runOnUiThread {
            try {
                if (!measuring) {
                    Log.d(TAG, "data received but not measuring: t=$tSec ch1=$ch1 ch2=$ch2")
                    return@runOnUiThread
                }

                // 2ch 同時追加（CH1: Blue, CH2: Red）
                addEntries(ch1.toFloat(), ch2.toFloat())
            } catch (e: Exception) {
                Log.w(TAG, "serialOnData handling failed", e)
            }
        }
    }

    private fun addEntries(ch1: Float, ch2: Float) {
        val data = chart.data ?: return
        if (data.dataSetCount < 2) return

        val set1 = data.getDataSetByIndex(0)
        val set2 = data.getDataSetByIndex(1)

        set1.addEntry(Entry(nextX, ch1))
        set2.addEntry(Entry(nextX, ch2))

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(120f)
        chart.moveViewToX(nextX)
        nextX += (sampleIntervalMs.toFloat() / 1000f)
    }

    private val serialOnStatus: (String) -> Unit = { msg ->
        runOnUiThread {
            Log.d(TAG, "SerialManager: $msg")
            Toast.makeText(this@MeasureActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // BroadcastReceiver used by this Activity (kept as before)
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val action = intent.action
                if (action == ACTION_USB_PERMISSION) {
                    // ...
                } else if (action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    // ...
                }
            } catch (e: Exception) {
                Log.w(TAG, "usbReceiver onReceive error", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)

        val logoView = (findViewById<View?>(R.id.logoImage) as? ImageView)
            ?: (findViewById<View?>(R.id.logo) as? ImageView)

        val helpView = (findViewById<View?>(R.id.button_help_text))
            ?: (findViewById<View?>(R.id.btn_help))  // toolbar の TextView または画面内の Button を受け取る

        if (logoView == null) Log.w(TAG, "logo not found in MeasureActivity layout")
        if (helpView == null) Log.w(TAG, "help view not found in MeasureActivity layout")

        logoView?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        helpView?.setOnClickListener {
            val url = "https://www.dreamelab.com/%E3%83%9B%E3%83%BC%E3%83%A0/help"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try { startActivity(intent) } catch (e: Exception) { Log.w(TAG, "open help url failed", e) }
        }

        val logo = findViewById<ImageView>(R.id.logoImage)
        val helpTxt = findViewById<TextView>(R.id.button_help_text)

        logo.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        helpTxt.setOnClickListener {
            val url = "https://www.dreamelab.com/%E3%83%9B%E3%83%BC%E3%83%A0/help"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        permissionPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // UI bind
        chart = findViewById(R.id.lineChart)
        tvSubject = findViewById(R.id.tv_subject)
        btnBack = findViewById(R.id.btn_back)
        btnStart = findViewById(R.id.btn_start)
        btnStopSave = findViewById(R.id.btn_stop)

        setupChart()

        val subject = intent?.getStringExtra("subject") ?: "subject_01"
        val stamp = SimpleDateFormat("yyMMdd-HH_mm_ss", Locale.getDefault()).format(Date())
        tvSubject.text = "$subject  $stamp"

        btnBack.setOnClickListener {
            closeSerial()
            finish()
        }
        btnStart.setOnClickListener { startMeasuring() }
        btnStopSave.setOnClickListener {
            if (measuring) {
                stopMeasuring()
                btnStopSave.text = "STOP/SAVE"
            } else {
                val filename = saveCsv(subject, stamp)
                if (filename != null) shareFile(filename)
            }
        }

        try {
            SerialManager.register(this, serialOnData, serialOnStatus)
            SerialManager.findAndRequestPermissionAndOpen()
        } catch (e: Exception) {
            Log.w(TAG, "SerialManager register failed", e)
        }

        registerUsbReceiverIfNeeded()
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
        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMinimum = 0f
        chart.axisRight.isEnabled = false

        // CH1: Blue
        val set1 = LineDataSet(mutableListOf(), "CH1").apply {
            color = Color.BLUE
            setDrawCircles(false)
            lineWidth = 1.8f
            mode = LineDataSet.Mode.LINEAR
        }

        // CH2: Red
        val set2 = LineDataSet(mutableListOf(), "CH2").apply {
            color = Color.RED
            setDrawCircles(false)
            lineWidth = 1.8f
            mode = LineDataSet.Mode.LINEAR
        }

        val data = LineData(set1 as ILineDataSet, set2 as ILineDataSet)
        chart.data = data
        chart.invalidate()
    }

    private fun startMeasuring() {
        if (measuring) return

        entries.clear()
        nextX = 0f

        if (chart.data == null || chart.data.dataSetCount < 2) {
            setupChart()
        }

        chart.data?.let { data ->
            for (i in 0 until data.dataSetCount) {
                data.getDataSetByIndex(i)?.clear()
            }
            data.notifyDataChanged()
        }

        chart.notifyDataSetChanged()
        chart.invalidate()

        measuring = true
        btnStart.isEnabled = false
        btnStopSave.text = "STOP/SAVE"
    }

    private fun stopMeasuring() {
        if (!measuring) return
        measuring = false
        btnStart.isEnabled = true
        btnStopSave.text = "SAVE"
    }

    private fun saveCsv(subject: String, stamp: String): File? {
        if (chart.data == null) return null
        val set = chart.data.getDataSetByIndex(0) ?: return null
        if (set.entryCount == 0) return null
        return try {
            val dir = File(getExternalFilesDir("measurements"), "")
            if (!dir.exists()) dir.mkdirs()
            val filename = "${subject}_${stamp}.csv"
            val file = File(dir, filename)
            FileOutputStream(file).use { fos ->
                fos.write("time,value\n".toByteArray())
                for (i in 0 until set.entryCount) {
                    val e = set.getEntryForIndex(i) ?: continue
                    val line = String.format(Locale.US, "%.3f,%.3f\n", e.x, e.y)
                    fos.write(line.toByteArray())
                }
            }
            file
        } catch (ex: Exception) {
            Log.w(TAG, "saveCsv error", ex)
            null
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = Uri.fromFile(file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share measurement"))
        } catch (e: Exception) {
            Log.w(TAG, "shareFile error", e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiverIfNeeded() {
        if (usbReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        var registeredOk = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ : フラグを明示
                registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(usbReceiver, filter)
            }
            registeredOk = true
        } catch (e: Exception) {
            Log.w(TAG, "registerUsbReceiverIfNeeded error", e)
            // フォールバック
            try {
                @Suppress("DEPRECATION")
                registerReceiver(usbReceiver, filter)
                registeredOk = true
            } catch (ex: Exception) {
                Log.w(TAG, "fallback registerReceiver failed", ex)
                registeredOk = false
            }
        }

        usbReceiverRegistered = registeredOk
    }

    fun connectDevice(device: UsbDevice) { /* unchanged */ }

    private fun requestUsbPermission(device: UsbDevice) { /* unchanged */ }

    private fun openSerial(device: UsbDevice): Unit { /* unchanged */ }

    private fun startIoLoop() { /* unchanged */ }

    private fun stopIoLoop() { /* unchanged */ }

    private fun closeSerial() { /* unchanged */ }

    private fun navigateToMeasureWindow() { /* unchanged */ }

    override fun onDestroy() {
        closeSerial()
        try {
            SerialManager.unregister(serialOnData, serialOnStatus)
        } catch (e: Exception) {
            Log.w(TAG, "SerialManager.unregister failed", e)
        }

        if (usbReceiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
