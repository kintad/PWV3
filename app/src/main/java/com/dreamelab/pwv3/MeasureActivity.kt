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
import com.dreamelab.pwv3.view.WaveformView
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
import kotlin.random.Random

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
    private lateinit var waveformView: WaveformView

    // measurement state
    private val handler = Handler(Looper.getMainLooper())
    private var measuring = false
    private val sampleIntervalMs: Long = 200L
    private val entries = mutableListOf<Entry>()
    private var nextX = 0f

    // USB / serial
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var serialPort: UsbSerialPort? = null
    private var permissionPendingIntent: PendingIntent? = null
    private var usbReceiverRegistered = false

    // I/O thread
    @Volatile private var ioRunning = false
    private var ioThread: Thread? = null

    // BroadcastReceiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val action = intent.action
                if (action == ACTION_USB_PERMISSION) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        Log.d(TAG, "USB permission granted for $device")
                        if (openSerial(device)) {
                            navigateToMeasureWindow()
                        } else {
                            runOnUiThread { Toast.makeText(this@MeasureActivity, "接続に失敗しました（許可後）", Toast.LENGTH_SHORT).show() }
                        }
                    } else {
                        Log.d(TAG, "USB permission denied")
                        runOnUiThread { Toast.makeText(this@MeasureActivity, "USB許可が拒否されました", Toast.LENGTH_SHORT).show() }
                    }
                } else if (action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        Log.d(TAG, "USB device detached: $device")
                        closeSerial()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "usbReceiver onReceive error", e)
            }
        }
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!measuring) return
            val simulatedValue = 6000f + Random.nextFloat() * 1400f
            addEntry(simulatedValue)
            handler.postDelayed(this, sampleIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)

        usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        permissionPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // UI bind
        waveformView = findViewById(R.id.waveformView)
        chart = findViewById(R.id.lineChart)
        tvSubject = findViewById(R.id.tv_subject)
        btnBack = findViewById(R.id.btn_back)
        btnStart = findViewById(R.id.btn_start)
        btnStopSave = findViewById(R.id.btn_stop)

        setupChart()

        val samples = FloatArray(1024) { i -> Math.sin(i / 10.0).toFloat() }
        waveformView.setSamples(samples)

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
        handler.post(sampleRunnable)
        btnStart.isEnabled = false
        btnStopSave.text = "STOP/SAVE"
    }

    private fun stopMeasuring() {
        if (!measuring) return
        measuring = false
        handler.removeCallbacks(sampleRunnable)
        btnStart.isEnabled = true
        btnStopSave.text = "SAVE"
    }

    private fun saveCsv(subject: String, stamp: String): File? {
        if (entries.isEmpty()) return null
        return try {
            val dir = File(getExternalFilesDir("measurements"), "")
            if (!dir.exists()) dir.mkdirs()
            val filename = "${subject}_${stamp}.csv"
            val file = File(dir, filename)
            FileOutputStream(file).use { fos ->
                fos.write("time,value\n".toByteArray())
                for (e in entries) {
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

    // USB / serial helper methods

    private fun registerUsbReceiverIfNeeded() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: NoSuchMethodError) {
            registerReceiver(usbReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "registerUsbReceiverIfNeeded error", e)
            try { registerReceiver(usbReceiver, filter) } catch (_: Exception) {}
        }
        usbReceiverRegistered = true
    }

    fun connectDevice(device: UsbDevice) {
        usbDevice = device
        if (usbManager?.hasPermission(device) == true) {
            if (openSerial(device)) {
                navigateToMeasureWindow()
            } else {
                Toast.makeText(this, "ポートオープン失敗（既に許可あり）", Toast.LENGTH_SHORT).show()
            }
        } else {
            requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        usbDevice = device
        val pending = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        permissionPendingIntent = pending
        try {
            usbManager?.requestPermission(device, pending)
        } catch (e: Exception) {
            Log.w(TAG, "requestUsbPermission error", e)
        }
    }

    /**
     * デバイス接続してシリアルポートを開く。成功すれば true を返す。
     */
    private fun openSerial(device: UsbDevice): Boolean {
        // 前の接続は閉じるが receiver はそのまま保持する
        stopIoLoop()
        try {
            serialPort?.let {
                try { it.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        serialPort = null
        usbConnection = null
        usbDevice = null
        usbInterface = null

        val manager = usbManager ?: return false

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = drivers.firstOrNull { it.device == device } ?: drivers.firstOrNull { it.device.deviceId == device.deviceId }
        if (driver == null) {
            Log.w(TAG, "No driver found for device $device")
            return false
        }
        val port = driver.ports.firstOrNull() ?: run {
            Log.w(TAG, "No port in driver")
            return false
        }

        val conn = manager.openDevice(device) ?: run {
            Log.w(TAG, "openDevice returned null")
            return false
        }

        return try {
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            usbConnection = conn
            usbDevice = device
            startIoLoop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "openSerial error", e)
            try { conn.close() } catch (_: Exception) {}
            false
        }
    }

    private fun startIoLoop() {
        stopIoLoop()
        ioRunning = true
        ioThread = Thread {
            val buffer = ByteArray(1024)
            while (ioRunning) {
                try {
                    val sp = serialPort ?: break
                    val len = sp.read(buffer, 100)
                    if (len > 0) {
                        val v = 6000f + Random.nextFloat() * 1400f
                        runOnUiThread { addEntry(v) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "IO read error", e)
                    break
                }
            }
        }.also { it.start() }
    }

    private fun stopIoLoop() {
        ioRunning = false
        try { ioThread?.join(200) } catch (_: Exception) {}
        ioThread = null
    }

    private fun closeSerial() {
        // 接続を閉じるが BroadcastReceiver は activity 生存中は維持する
        stopIoLoop()
        try {
            serialPort?.let {
                try { it.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        serialPort = null
        try { usbConnection?.close() } catch (_: Exception) {}
        usbConnection = null
        usbDevice = null
        usbInterface = null
        // NOTE: レシーバの unregister は onDestroy() のみで行う
    }

    private fun navigateToMeasureWindow() {
        runOnUiThread {
            startActivity(Intent(this, MeasureWindowActivity::class.java))
        }
    }

    override fun onDestroy() {
        closeSerial()
        if (usbReceiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
