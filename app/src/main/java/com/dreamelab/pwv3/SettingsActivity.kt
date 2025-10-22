// kotlin
package com.dreamelab.pwv3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.provider.DocumentsContract
import android.net.Uri
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.charset.Charset
import kotlin.collections.get
import kotlin.or
import kotlin.text.compareTo
import android.annotation.SuppressLint

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"

    // 省略（UIプロパティ宣言は既存のまま）...
    private lateinit var subjectIdEdit: EditText
    private lateinit var vesselLengthEdit: EditText
    private lateinit var nonlinearFactorEdit: EditText
    private lateinit var csvFileEdit: EditText
    private lateinit var csvSelectButton: Button
    private lateinit var detectionModeSpinner: Spinner
    private lateinit var detectPercentEdit: EditText
    private lateinit var f1Edit: EditText
    private lateinit var f2Edit: EditText
    private lateinit var applyButton: Button
    private lateinit var saveFolderEdit: EditText
    private lateinit var saveFolderSelectButton: Button
    private lateinit var serialPortSpinner: Spinner
    private lateinit var measureButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var closeButton: Button

    private lateinit var myTsecTextView: TextView
    private lateinit var myCh1TextView: TextView
    private lateinit var myCh2TextView: TextView
    private lateinit var fileLogger: FileLogger

    private var serialThread: SerialReaderThread? = null
    private var showedHeartbeat = false

    // 統一したアクション名に変更
    private val ACTION_USB_PERMISSION = "com.dreamelab.pwv3.USB_PERMISSION"
    private lateinit var usbManager: UsbManager

    // onCreate登録用の共通レシーバ
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(
                TAG,
                "usbPermissionReceiver.onReceive action=${intent?.action} extras=${intent?.extras}"
            )
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result granted=$granted device=$device")
                if (granted && device != null) {
                    openSerialFromDevice(device)
                } else {
                    Log.w(TAG, "USB permission denied or device null")
                    Toast.makeText(
                        this@SettingsActivity,
                        "USB許可が拒否されました",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val csvFilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { csvFileEdit.setText(it.path) }
        }
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { saveFolderEdit.setText(uri.path) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // 明示的なフラグ付与（API 33+）と互換処理。Lint の警告を防ぐために古い経路は suppress。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 明示的に非エクスポートで登録（API 33+）
            registerReceiver(
                usbPermissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            // 古い API ではフラグ付きオーバーロードが無いため Lint を抑制して従来通り登録
            @Suppress("DEPRECATION")
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        // findViewById 周りは既存コードと同じ
        subjectIdEdit = findViewById(R.id.edit_subject_id)
        vesselLengthEdit = findViewById(R.id.edit_vessel_length)
        nonlinearFactorEdit = findViewById(R.id.edit_nonlinear_factor)
        csvFileEdit = findViewById(R.id.edit_csv_file)
        csvSelectButton = findViewById(R.id.button_csv_select)
        detectionModeSpinner = findViewById(R.id.spinnerDetectionMode)
        detectPercentEdit = findViewById(R.id.edit_detect_percent)
        f1Edit = findViewById(R.id.edit_f1)
        f2Edit = findViewById(R.id.edit_f2)
        applyButton = findViewById(R.id.button_apply)
        saveFolderEdit = findViewById(R.id.edit_save_folder)
        saveFolderSelectButton = findViewById(R.id.button_save_folder_select)
        serialPortSpinner = findViewById(R.id.spinner_serial_port)
        measureButton = findViewById(R.id.button_measure)
        analyzeButton = findViewById(R.id.button_analyze)
        closeButton = findViewById(R.id.button_close)

        myTsecTextView = findViewById(R.id.text_tsec)
        myCh1TextView = findViewById(R.id.text_ch1)
        myCh2TextView = findViewById(R.id.text_ch2)

        fileLogger = FileLogger(this, "log.txt")

        // UI初期設定（省略）
        val detectionModes = arrayOf("peak", "rising")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, detectionModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        detectionModeSpinner.adapter = adapter
        detectionModeSpinner.setBackgroundResource(R.drawable.edittext_underline)

        detectionModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val selected = detectionModes[position]
                detectPercentEdit.isEnabled = (selected == "rising")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        saveFolderSelectButton.setOnClickListener {
            folderPicker.launch(null)
        }

        csvSelectButton.setOnClickListener {
            // CSV を選ぶ（MIME を必要に応じて "text/*" や "*/*" に変更）
            csvFilePicker.launch("text/csv")
        }

        measureButton.setOnClickListener {
            startDeviceSelectionAndOpen()
        }

        analyzeButton.setOnClickListener {
            Toast.makeText(this, "解析開始（未実装）", Toast.LENGTH_SHORT).show()
        }

        closeButton.setOnClickListener {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                csvFileEdit.setText(uri.path)
            }
        }
    }

    private fun startDeviceSelectionAndOpen() {
        val prober = UsbSerialProber.getDefaultProber()

        // まず低レベルの deviceList をログ出力（デバッグ用 ）
        val deviceList = usbManager.deviceList
        Log.d(TAG, "usbManager.deviceList size=${deviceList.size} keys=${deviceList.keys}")
        for ((key, dev) in deviceList) {
            Log.d(TAG, "deviceList entry key=$key name=${dev.deviceName} vendor=${dev.vendorId} product=${dev.productId}")
        }

        val availableDrivers = prober.findAllDrivers(usbManager)
        Log.d(TAG, "startDeviceSelectionAndOpen: found drivers=${availableDrivers.size}")

        if (availableDrivers.isNotEmpty()) {
            val driverOrFirst = availableDrivers[0]
            val device = driverOrFirst.device
            Log.d(TAG, "selected device: name=${device.deviceName} vendor=${device.vendorId} product=${device.productId}")

            if (usbManager.hasPermission(device)) {
                openSerialFromDevice(device)
                return
            }

            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, piFlags)
            Log.d(TAG, "requestPermission sent for device=$device with flags=$piFlags")
            usbManager.requestPermission(device, permissionIntent)
            Toast.makeText(this, "USB許可ダイアログを表示しました", Toast.LENGTH_SHORT).show()
            return
        }

        // prober が空でも deviceList にデバイスがある場合はフォールバックして許可を要求する
        if (deviceList.isNotEmpty()) {
            val device = deviceList.values.first()
            Log.d(TAG, "fallback using usbManager.deviceList first device: ${device.deviceName} vendor=${device.vendorId} product=${device.productId}")

            if (usbManager.hasPermission(device)) {
                openSerialFromDevice(device)
                return
            }

            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, piFlags)
            usbManager.requestPermission(device, permissionIntent)
            Toast.makeText(this, "USB許可ダイアログを表示しました（フォールバック）", Toast.LENGTH_SHORT).show()
            return
        }

        // どちらも空なら見つからない
        Toast.makeText(this@SettingsActivity, "USBデバイスが見つかりません", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "no usb drivers found by prober and deviceList is empty")
    }

    private fun openSerialFromDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver != null) {
            openPortAndStart(driver)
        } else {
            Toast.makeText(this, "ドライバが見つかりません", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateSerialPortSpinner(context: Context, device: UsbDevice) {
        val prober = UsbSerialProber.getDefaultProber()
        val driver = prober.probeDevice(device)
        Log.d(TAG, "probeDevice result: $driver")
        if (driver == null || driver.ports.isEmpty()) {
            runOnUiThread {
                Toast.makeText(context, "ポートが見つかりません", Toast.LENGTH_SHORT).show()
                serialPortSpinner.adapter = null
            }
            return
        }
        val portNames = driver.ports.mapIndexed { idx, _ -> "Port $idx" }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, portNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        runOnUiThread {
            serialPortSpinner.adapter = adapter
        }
    }

    private fun openPortAndStart(driver: com.hoho.android.usbserial.driver.UsbSerialDriver) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = driver.device

        // 常にスピナーを更新してポート名を表示できるようにする
        updateSerialPortSpinner(this, device)

        val port = driver.ports.firstOrNull()
        val portIndex = if (port != null) driver.ports.indexOf(port) else -1
        Log.d(
            TAG,
            "openPortAndStart device=${device.deviceName} vendor=${device.vendorId} product=${device.productId} ports=${driver.ports.size} selectedPortIndex=$portIndex"
        )

        if (port == null) {
            Toast.makeText(this@SettingsActivity, "ポートが見つかりません", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val connection: UsbDeviceConnection? = usbManager.openDevice(device)
        if (connection == null) {
            Toast.makeText(this@SettingsActivity, "デバイス接続に失敗しました", Toast.LENGTH_SHORT)
                .show()
            Log.w(TAG, "openDevice returned null for $device")
            return
        }

        try {
            port.open(connection)
            port.setParameters(
                500000, 8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            try {
                val setDtr = port.javaClass.getMethod("setDTR", Boolean::class.javaPrimitiveType)
                setDtr.invoke(port, true)
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "setDTR not supported on this port impl")
            } catch (e: Exception) {
                Log.w(TAG, "setDTR failed", e)
            }
            try {
                val setRts = port.javaClass.getMethod("setRTS", Boolean::class.javaPrimitiveType)
                setRts.invoke(port, true)
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "setRTS not supported on this port impl")
            } catch (e: Exception) {
                Log.w(TAG, "setRTS failed", e)
            }

            Log.d(TAG, "port opened: $port")
            runOnUiThread {
                if (portIndex >= 0 && serialPortSpinner.adapter != null) {
                    try {
                        serialPortSpinner.setSelection(portIndex)
                    } catch (_: Exception) {
                    }
                }
                Toast.makeText(
                    this@SettingsActivity,
                    "シリアルポートをオープンしました (Port $portIndex)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                this@SettingsActivity,
                "ポートオープンに失敗: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            try {
                connection.close()
            } catch (_: Exception) {
            }
            return
        }

        // シリアル受信スレッド開始
        serialThread?.interrupt()
        serialThread = SerialReaderThread(port) { tSec, ch1, ch2 ->
            try {
                // fileLogger.appendLine が Int を期待する場合は toInt() で変換して渡す
                fileLogger.appendLine(tSec.toInt(),
                    ch1.toDouble(),
                    ch2.toDouble())

                // SerialDataBus.post は Double を期待する想定なので Double のまま渡す
                SerialDataBus.post(tSec.toDouble(),
                    ch1.toInt(),
                    ch2.toInt())
            } catch (e: Exception) {
                Log.w(TAG, "fileLogger.appendLine failed", e)
            }

            runOnUiThread {
                if (tSec == -1.0) {
                    if (!showedHeartbeat) {
                        showedHeartbeat = true
                        Toast.makeText(
                            this@SettingsActivity,
                            "シリアルスレッド開始（ハートビート受信）",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.d(TAG, "heartbeat received")
                    }
                    return@runOnUiThread
                }

                Log.d(TAG, "Serial data tSec:$tSec ch1:$ch1 ch2:$ch2")
                myTsecTextView.text = tSec.toString()
                myCh1TextView.text = ch1.toString()
                myCh2TextView.text = ch2.toString()
            }
        }
        serialThread?.start()
        Log.d(TAG, "serialThread.start() called for Port $portIndex")
        runOnUiThread {
            Toast.makeText(
                this@SettingsActivity,
                "シリアル受信スレッドを開始しました (Port $portIndex)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterReceiver failed or already unregistered", e)
        }

        try {
            serialThread?.interrupt()
            serialThread = null
        } catch (e: Exception) {
            Log.w(TAG, "serialThread interrupt failed", e)
        }

        try {
            fileLogger.close()
        } catch (e: Exception) {
            Log.w(TAG, "fileLogger.close failed", e)
        }

        super.onDestroy()
    }

    // 簡易シリアル読み取りスレッド（デバッグログ追加）
    private class SerialReaderThread(
        private val port: UsbSerialPort,
        private val onData: (Double, Double, Double) -> Unit
    ) : Thread() {
        @Volatile
        private var running = true

        override fun run() {
            val threadTag = "SerialReaderThread"
            Log.d(threadTag, "run() entered")
            val readBuffer = ByteArray(1024)
            val sb = StringBuilder()
            try {
                while (running && !isInterrupted) {
                    try {
                        val len = port.read(readBuffer, 200) // timeout 200ms
                        if (len > 0) {
                            val s = String(readBuffer, 0, len, Charset.forName("UTF-8"))
                            Log.d(threadTag, "read len=$len data='${s.replace("\n", "\\n")}'")
                            sb.append(s)
                            // 行単位で処理
                            var idx: Int
                            while (true) {
                                idx = sb.indexOf("\n")
                                if (idx == -1) break
                                val line = sb.substring(0, idx).trim()
                                sb.delete(0, idx + 1)
                                if (line.isEmpty()) continue
                                processLine(line)
                            }
                        } else {
                            // len == 0 の場合はタイムアウト（ログ少なめ）
                        }
                    } catch (e: IOException) {
                        Log.e(threadTag, "read IOException", e)
                        break
                    } catch (e: Exception) {
                        Log.e(threadTag, "read Exception", e)
                    }
                }
            } finally {
                try {
                    port.close()
                } catch (_: Exception) {
                }
                Log.d(threadTag, "run() exiting, port closed")
            }
        }

        private fun processLine(line: String) {
            val threadTag = "SerialReaderThread"
            Log.d(threadTag, "processLine: '$line'")
            if (line.equals("HB", ignoreCase = true) || line.equals(
                    "heartbeat",
                    ignoreCase = true
                )
            ) {
                onData(-1.0, 0.0, 0.0)
                return
            }
            val parts = line.split(",", " ", "\t").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                try {
                    val tSec =
                        parts[0].toDoubleOrNull() ?: parts[0].toIntOrNull()?.toDouble() ?: 0.0
                    val ch1 = parts[1].toDoubleOrNull() ?: 0.0
                    val ch2 = parts[2].toDoubleOrNull() ?: 0.0
                    onData(tSec.toDouble(), ch1.toDouble(), ch2.toDouble())
                } catch (e: Exception) {
                    Log.e(threadTag, "parse error for line='$line'", e)
                }
            } else {
                Log.d(threadTag, "partial or unrecognized line: '$line'")
            }
        }

        override fun interrupt() {
            running = false
            super.interrupt()
        }
    }
}