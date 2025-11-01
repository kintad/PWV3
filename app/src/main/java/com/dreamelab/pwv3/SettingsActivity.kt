package com.dreamelab.pwv3

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.widget.Toast

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"

    // UI
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

    // MeasureActivity を一度だけ起動するフラグ
    private var measureLaunched = false

    // SerialManager 登録状態フラグ
    private var serialRegistered = false

    // ActivityResult for file/folder pickers
    private val csvFilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { csvFileEdit.setText(uri.toString()) }
        }
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let { saveFolderEdit.setText(uri.toString()) }
        }

    // SerialManager callbacks (同じ参照を登録/解除に使う)
    private val serialOnData: (Double, Double, Double) -> Unit = { tSec, ch1, ch2 ->
        runOnUiThread {
            try {
                try { fileLogger.appendLine(tSec.toInt(), ch1.toDouble(), ch2.toDouble()) } catch (e: Exception) { Log.w(TAG, "fileLogger.appendLine failed", e) }
                try { SerialDataBus.post(tSec.toDouble(), ch1.toInt(), ch2.toInt()) } catch (e: Exception) { Log.w(TAG, "SerialDataBus.post failed", e) }

                if (tSec == -1.0) {
                    Log.d(TAG, "heartbeat received")
                } else {
                    myTsecTextView.text = tSec.toString()
                    myCh1TextView.text = ch1.toString()
                    myCh2TextView.text = ch2.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "serialOnData handler failed", e)
            }
        }
    }

    private val serialOnStatus: (String) -> Unit = { msg ->
        runOnUiThread {
            // デバッグログを追加：実際に届くメッセージを確認する
            Log.d(TAG, "SerialManager status raw: \"$msg\"")
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

            // ポートオープンの判定を複数候補で柔軟にチェック
            val opened = msg.contains("ポートをオープン") ||
                    msg.contains("opened", ignoreCase = true) ||
                    msg.contains("open", ignoreCase = true) ||
                    msg.contains("ポートを開") // 「開く」「開きました」などに対応

            if (!measureLaunched && opened) {
                measureLaunched = true
                try {
                    val intent = Intent(this@SettingsActivity, MeasureActivity::class.java)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "startActivity failed", e)
                    Toast.makeText(this, "測定画面の起動に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // findViewById 初期化
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

        csvSelectButton.setOnClickListener { csvFilePicker.launch("text/csv") }
        saveFolderSelectButton.setOnClickListener { folderPicker.launch(null) }

        applyButton.setOnClickListener { Toast.makeText(this, "設定を適用しました", Toast.LENGTH_SHORT).show() }
        analyzeButton.setOnClickListener { Toast.makeText(this, "解析開始（未実装）", Toast.LENGTH_SHORT).show() }
        closeButton.setOnClickListener { finish() }

        // onCreate では SerialManager.register を呼ばない（重複防止）
        measureButton.setOnClickListener {
            SerialManager.findAndRequestPermissionAndOpen()
        }
    }

    override fun onStart() {
        super.onStart()
        // 戻ってきたときに再度 MeasureActivity を起動できるようにリセット
        measureLaunched = false

        // SerialManager 登録（重複登録防止）
        if (!serialRegistered) {
            try {
                SerialManager.register(this, serialOnData, serialOnStatus)
                serialRegistered = true
                Log.d(TAG, "SerialManager registered")
            } catch (e: Exception) {
                Log.w(TAG, "SerialManager.register failed", e)
            }
        }
    }

    override fun onStop() {
        // SerialManager 解除を onStop に移動（重複解除防止）
        if (serialRegistered) {
            try {
                SerialManager.unregister(serialOnData, serialOnStatus)
                serialRegistered = false
                Log.d(TAG, "SerialManager unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "SerialManager.unregister failed", e)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        try {
            fileLogger.close()
        } catch (e: Exception) {
            Log.w(TAG, "fileLogger.close failed", e)
        }
        super.onDestroy()
    }
}
