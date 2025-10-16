package com.dreamelab.pwv3

import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.toString
import android.content.Intent
import android.provider.DocumentsContract
import android.net.Uri
import android.widget.Button
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.hardware.usb.UsbDevice
import kotlin.collections.get
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileWriter
import android.app.Activity
import java.io.IOException
import kotlin.collections.get
import kotlin.printStackTrace


class SettingsActivity : AppCompatActivity() {

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
    private lateinit var receiver: Receiver
    private var serialThread: SerialReaderThread? = null

    private val csvFilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { csvFileEdit.setText(it.path) }
        }
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { saveFolderEdit.setText(uri.path) }
        }


    // onActivityResultで選択結果を受け取る
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                csvFileEdit.setText(uri.path)
            }
        }
    }

    fun saveLogToPwv3Data(context: Context, log: String) {
         val pwv3dataDir = File(context.getExternalFilesDir(null), "pwv3data")
        if (!pwv3dataDir.exists()) {
            pwv3dataDir.mkdir()
        }
        val logFile = File(pwv3dataDir, "log.txt")
        try {
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(log)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(context, "ログ書き込み失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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

        // Detection modeのSpinnerに選択肢をセット
        val detectionModes = arrayOf("peak", "rising")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, detectionModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        detectionModeSpinner.adapter = adapter
        // Spinnerの下線デザインを追加
        detectionModeSpinner.setBackgroundResource(R.drawable.edittext_underline)

        // Spinnerの選択リスナー
        detectionModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val selected = detectionModes[position]
                if (selected == "rising") {
                    detectPercentEdit.isEnabled = true
                } else {
                    detectPercentEdit.isEnabled = false
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 保存フォルダ選択
        saveFolderSelectButton.setOnClickListener {
            folderPicker.launch(null)
        }

        // CSVファイル選択
        csvSelectButton.setOnClickListener {
            val initialDir = saveFolderEdit.text.toString()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(initialDir))
            }
            startActivityForResult(intent, 1001)
        }

        measureButton.setOnClickListener {
            // 入力値取得
            val subjectId = subjectIdEdit.text.toString()
            val vesselLength = vesselLengthEdit.text.toString().toDoubleOrNull() ?: 20.0
            val nonlinearFactor = nonlinearFactorEdit.text.toString().toDoubleOrNull() ?: 1.3
            val serialPort = serialPortSpinner.selectedItem?.toString() ?: ""
            val f1 = f1Edit.text.toString().toDoubleOrNull() ?: 0.4
            val f2 = f2Edit.text.toString().toDoubleOrNull() ?: 10.0
            val mode = detectionModeSpinner.selectedItem?.toString() ?: "peak"
            val detectPercent = detectPercentEdit.text.toString().toIntOrNull() ?: 0
            val saveFolder = saveFolderEdit.text.toString()
            val csvFile = csvFileEdit.text.toString()
            val prober = UsbSerialProber.getDefaultProber()
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val availableDrivers = prober.findAllDrivers(usbManager)
            val receiver = Receiver()
            val filter = IntentFilter("com.dreamelab.pwv3.USB_PERMISSION")
            val receiverFlags =
                ContextCompat.RECEIVER_NOT_EXPORTED // Or ContextCompat.RECEIVER_EXPORTED if needed
            ContextCompat.registerReceiver(this, receiver, filter, receiverFlags)

//            override fun onReceive(context: Context, intent: Intent) {
//                    // 受信時の処理
//                }
// availableDrivers取得後にポート名リストをSpinnerへセット
            if (availableDrivers.isNotEmpty()) {
                val portNames = mutableListOf<String>()
                availableDrivers.forEachIndexed { driverIdx, driver ->
                    driver.ports.forEachIndexed { portIdx, _ ->
                        portNames.add("Driver${driverIdx} Port${portIdx}")
                    }
                }
                val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, portNames)
                portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                serialPortSpinner.adapter = portAdapter
            } else {
                serialPortSpinner.adapter = null
            }

            val logBuilder = StringBuilder()
            logBuilder.appendLine("==== 測定開始 ====")
            logBuilder.appendLine("日時: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            logBuilder.appendLine("subjectId: $subjectId")
            logBuilder.appendLine("vesselLength: $vesselLength")
            logBuilder.appendLine("nonlinearFactor: $nonlinearFactor")
            logBuilder.appendLine("serialPort: $serialPort")
            logBuilder.appendLine("f1: $f1")
            logBuilder.appendLine("f2: $f2")
            logBuilder.appendLine("mode: $mode")
            logBuilder.appendLine("detectPercent: $detectPercent")
            logBuilder.appendLine("saveFolder: $saveFolder")
            logBuilder.appendLine("csvFile: $csvFile")

            if (availableDrivers.isEmpty()) {
                logBuilder.appendLine("原因: USBデバイスが接続されていない／認識できていない")
            } else {
                val driver = availableDrivers[0]
                if (driver == null) {
                    logBuilder.appendLine("原因: ドライバが見つからない")
                } else if (driver.ports.isEmpty()) {
                    logBuilder.appendLine("原因: ポートが見つからない")
                } else {
                    logBuilder.appendLine("USBデバイス・ドライバ・ポート検出済み")
                }
            }
            logBuilder.appendLine("=================")
            saveLogToPwv3Data(this, logBuilder.toString())

            if (availableDrivers.isNotEmpty()) {
                val driver = availableDrivers[0]
                val device = driver.device
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, Intent("com.dreamelab.pwv3.USB_PERMISSION"),
                    PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(device, permissionIntent)
            } else {
                Toast.makeText(this, "USBデバイスが見つかりません", Toast.LENGTH_SHORT).show()
            }
        }


        analyzeButton.setOnClickListener {
            // 解析処理
        }
        closeButton.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    fun updateSerialPortSpinner(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val prober = UsbSerialProber.getDefaultProber()
        val driver = prober.probeDevice(device)
        android.util.Log.d("SettingsActivity", "probeDevice result: $driver")
        if (driver == null) {
            runOnUiThread {
                Toast.makeText(context, "ドライバが見つかりません", Toast.LENGTH_SHORT).show()
                serialPortSpinner.adapter = null
            }
            return
        }
        android.util.Log.d("SettingsActivity", "ports: ${driver.ports.size}")
        if (driver.ports.isEmpty()) {
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
}

class Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // USBパーミッションの受信処理
        if (intent?.action == "com.dreamelab.pwv3.USB_PERMISSION") {
            // ここにUSBパーミッション取得後の処理を書く
        // 受信時の処理
        }
    }
}




