package com.dreamelab.pwv3

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.lang.Exception
import android.widget.ImageView
import android.widget.TextView
import android.view.View

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

    // ActivityResult for file/folder pickers
    private val csvFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            csvFileEdit.setText(it.toString())
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            saveFolderEdit.setText(it.toString())
        }
    }

    // SerialManager callbacks (同じ参照を登録/解除に使う)
    private val serialOnData: (Double, Double, Double) -> Unit = { tSec, ch1, ch2 ->
        runOnUiThread {
            try {
                myTsecTextView.text = String.format("%.3f", tSec)
                myCh1TextView.text = String.format("%.1f", ch1)
                myCh2TextView.text = String.format("%.1f", ch2)
            } catch (e: Exception) {
                Log.w(TAG, "serialOnData update failed", e)
            }
        }
    }

    private val serialOnStatus: (String) -> Unit = { msg ->
        runOnUiThread {
            Log.d(TAG, "SerialManager: $msg")
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()

            // ポートオープン通知や既に接続済み通知を受けて MeasureActivity を一度だけ起動
            if (!measureLaunched && (msg.contains("ポートをオープン") || msg.contains("既に接続済み") || msg.contains("既に許可あり"))) {
                measureLaunched = true
                try {
                    val intent = Intent(this@SettingsActivity, MeasureActivity::class.java)
                    // 必要なら追加の extras を設定
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "startActivity MeasureActivity failed", e)
                    Toast.makeText(this, "測定画面の起動に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // 既存のレイアウト名に合わせてください

        val logoSView = (findViewById<View?>(R.id.logoImage) as? ImageView)
            ?: (findViewById<View?>(R.id.logo) as? ImageView)

        val helpSView = (findViewById<View?>(R.id.button_help_text))
            ?: (findViewById<View?>(R.id.btn_help))

        if (logoSView == null) Log.w(TAG, "logo not found in SettingsActivity layout")
        if (helpSView == null) Log.w(TAG, "help view not found in SettingsActivity layout")

        logoSView?.setOnClickListener {
            finish()
        }

        helpSView?.setOnClickListener {
            val url = "https://www.dreamelab.com/%E3%83%9B%E3%83%BC%E3%83%A0/help"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try { startActivity(intent) } catch (e: Exception) { Log.w(TAG, "open help url failed", e) }
        }

        val logoS = findViewById<ImageView>(R.id.logoImage)
        val helpTxtS = findViewById<TextView>(R.id.button_help_text)

        if (logoS == null) Log.w(TAG, "logoImage not found in layout (toolbar not included?)")
        if (helpTxtS == null) Log.w(TAG, "button_help_text not found in layout (toolbar not included?)")

        logoS.setOnClickListener {
            finish()
        }

        helpTxtS.setOnClickListener {
            val url = "https://www.dreamelab.com/%E3%83%9B%E3%83%BC%E3%83%A0/help"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

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

        // fileLogger 初期化（プロジェクト内の実装に依存）
        try {
            fileLogger = FileLogger(this)
        } catch (e: Exception) {
            Log.w(TAG, "FileLogger init failed", e)
        }

        // ボタン処理
        csvSelectButton.setOnClickListener {
            csvFilePicker.launch(arrayOf("*/*"))
        }
        saveFolderSelectButton.setOnClickListener {
            folderPicker.launch(null)
        }

        measureButton.setOnClickListener {
            // 測定開始要求: SerialManager に許可確認と接続を依頼
            measureLaunched = false
            try {
                SerialManager.findAndRequestPermissionAndOpen()
                Toast.makeText(this, "接続を開始します...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "measureButton click error", e)
                Toast.makeText(this, "接続開始に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }

        closeButton.setOnClickListener {
            finish()
        }

        // SerialManager 登録
        try {
            SerialManager.register(this, serialOnData, serialOnStatus)
        } catch (e: Exception) {
            Log.w(TAG, "SerialManager.register failed", e)
        }
    }

    override fun onDestroy() {
        // SerialManager から解除（登録時と同じハンドラを渡す）
        try {
            SerialManager.unregister(serialOnData, serialOnStatus)
        } catch (e: Exception) {
            Log.w(TAG, "SerialManager.unregister failed", e)
        }

        // fileLogger 解放
        try {
            fileLogger.close()
        } catch (e: Exception) {
            // ignore
        }

        super.onDestroy()
    }
}
