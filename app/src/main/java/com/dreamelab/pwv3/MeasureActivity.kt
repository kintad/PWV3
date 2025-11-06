package com.dreamelab.pwv3

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.Toast
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MeasureActivity : Activity() {

    companion object {
        private const val TAG = "MeasureActivity"
        private const val BAUD_RATE = 500_000
        private const val PACKET_LENGTH = 9
    }

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var backButton: Button

    private var usbManager: UsbManager? = null
    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var reader: SerialReaderThread? = null

    // フルデータ保存用
    private val fullData = mutableListOf<Triple<Double, Int, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure) // layout ファイル名に合わせてください

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        startButton = findViewById(R.id.button_start)
        stopButton = findViewById(R.id.button_stop)
        backButton = findViewById(R.id.button_back)

        startButton.setOnClickListener { startSerialReading() }
        stopButton.setOnClickListener { stopAndSave() }
        backButton.setOnClickListener { onBackPressed() }

        stopButton.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReader()
        closePort()
    }

    private fun startSerialReading() {
        if (reader != null) {
            Toast.makeText(this, "Already started", Toast.LENGTH_SHORT).show()
            return
        }

        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No USB serial device found", Toast.LENGTH_LONG).show()
            return
        }

        // 先頭のデバイスを使用
        val driver: UsbSerialDriver = availableDrivers[0]
        val device: UsbDevice = driver.device
        // 権限確認はアプリ側で行うこと。ここでは単純に open を試みる
        connection = usbManager?.openDevice(device)
        if (connection == null) {
            Toast.makeText(this, "Cannot open USB device (permission?)", Toast.LENGTH_LONG).show()
            return
        }

        port = driver.ports.firstOrNull()
        if (port == null) {
            Toast.makeText(this, "No port on device", Toast.LENGTH_LONG).show()
            return
        }

        try {
            port?.open(connection)
            port?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            Log.e(TAG, "port open failed", e)
            Toast.makeText(this, "Port open failed: ${e.message}", Toast.LENGTH_LONG).show()
            closePort()
            return
        }

        fullData.clear()
        reader = SerialReaderThread(port!!) { t, c1, c2 ->
            runOnUiThread {
                fullData.add(Triple(t, c1, c2))
                // 必要ならここでUI更新（グラフ等）を行う
            }
        }
        reader?.start()

        startButton.isEnabled = false
        stopButton.isEnabled = true
        Toast.makeText(this, "Measurement started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAndSave() {
        stopReader()
        closePort()
        startButton.isEnabled = true
        stopButton.isEnabled = false
        val file = saveCsv()
        Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun stopReader() {
        try {
            reader?.stopThread()
            reader?.join(500)
        } catch (e: Exception) {
            Log.w(TAG, "stopReader exception", e)
        } finally {
            reader = null
        }
    }

    private fun closePort() {
        try {
            port?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close port failed", e)
        } finally {
            port = null
            connection?.close()
            connection = null
        }
    }

    private fun saveCsv(): File {
        val dir = getExternalFilesDir("pwv3") ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val now = System.currentTimeMillis()
        val file = File(dir, "measurement_${now}.csv")
        try {
            FileWriter(file).use { fw ->
                fw.appendLine("Time(s),Node1,Node2")
                if (fullData.isNotEmpty()) {
                    val t0 = fullData.first().first
                    for ((t, c1, c2) in fullData) {
                        fw.appendLine(String.format("%.6f,%d,%d", t - t0, c1, c2))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "saveCsv failed", e)
        }
        return file
    }

    // 内部受信スレッド（Arduino のバイナリパケットをパース）
    private class SerialReaderThread(
        private val port: UsbSerialPort,
        private val onData: (Double, Int, Int) -> Unit
    ) : Thread("SerialReaderThread") {
        @Volatile
        private var running = true

        private val buf = ByteArray(2048)
        private val buffer = ArrayList<Byte>()

        fun stopThread() {
            running = false
            try { interrupt() } catch (_: Exception) {}
        }

        override fun run() {
            try {
                while (running && !isInterrupted) {
                    try {
                        val len = port.read(buf, 200)
                        if (len > 0) {
                            for (i in 0 until len) buffer.add(buf[i])
                            // パケット長が揃うまでループ
                            while (buffer.size >= PACKET_LENGTH) {
                                if (buffer[0].toInt() and 0xFF != 0xFF) {
                                    // ヘッダでなければ先頭を削除
                                    buffer.removeAt(0)
                                    continue
                                }
                                // ヘッダ確認できたら9バイト切り出し
                                if (buffer.size < PACKET_LENGTH) break
                                val packet = ByteArray(PACKET_LENGTH)
                                for (i in 0 until PACKET_LENGTH) packet[i] = buffer.removeAt(0)
                                // パース: packet[0] == 0xFF
                                val t_us = ((packet[1].toInt() and 0xFF)) or
                                        ((packet[2].toInt() and 0xFF) shl 8) or
                                        ((packet[3].toInt() and 0xFF) shl 16) or
                                        ((packet[4].toInt() and 0xFF) shl 24)
                                val t_sec = t_us.toDouble() / 1_000_000.0
                                val ch1 = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
                                val ch2 = ((packet[7].toInt() and 0xFF) shl 8) or (packet[8].toInt() and 0xFF)
                                safeOnData(t_sec, ch1, ch2)
                            }
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "read IOException", e)
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "read loop exception", e)
                    }
                }
            } finally {
                try { port.close() } catch (e: Exception) { Log.w(TAG, "port close failed", e) }
            }
        }

        private fun safeOnData(t: Double, c1: Int, c2: Int) {
            try { onData(t, c1, c2) } catch (e: Exception) { Log.w(TAG, "onData callback failed", e) }
        }
    }
}
