package com.dreamelab.pwv3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class SerialManager(private val ctx: Context) : ContextWrapper(ctx) {
    companion object {
        private const val TAG = "SerialManager"
    }

    private val usbManager: UsbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val usbPermissionAction = "${ctx.packageName}.USB_PERMISSION"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent == null) {
                    Log.w(TAG, "onReceive: null intent")
                    return
                }
                val action = intent.action
                if (action == usbPermissionAction) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission result: device=$device granted=$granted")
                    // 必要ならここで接続処理のコールバックを呼ぶ
                }
            } catch (e: Exception) {
                Log.e(TAG, "usbPermissionReceiver onReceive failed", e)
            }
        }
    }

    /**
     * レシーバーを登録する。
     * Android 14(API 34) 以降では明示的に RECEIVER_NOT_EXPORTED/RECEIVER_EXPORTED を指定する必要があるため、
     * API に応じて適切なオーバーロードを呼び分ける。
     */

    fun registerReceivers() {
        val filter = IntentFilter(USB_PERMISSION) // USB_PERMISSION は適宜定義されている前提
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+：エクスポートフラグを明示
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // 旧 API：従来の呼び出し
                registerReceiver(usbPermissionReceiver, filter)
            }
            Log.d(TAG, "usbPermissionReceiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerReceivers failed", e)
        }
    }

    fun unregisterReceivers() {
        try {
            unregisterReceiver(usbPermissionReceiver)
            Log.d(TAG, "usbPermissionReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            // 未登録や既に解除済みの安全対処
            Log.w(TAG, "usbPermissionReceiver was not registered", e)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterReceivers failed", e)
        }
    }

    /**
     * 指定デバイスに対してパーミッション要求を行う。
     * PendingIntent のフラグは API に応じて設定する。
     */
    fun requestUsbPermission(device: UsbDevice) {
        try {
            val intent = Intent(usbPermissionAction)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+ では明示的に MUTABLE/IMMUTABLE を指定
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(ctx, 0, intent, flags)
            usbManager.requestPermission(device, pi)
            Log.d(TAG, "requestUsbPermission sent for device=$device")
        } catch (e: Exception) {
            Log.e(TAG, "requestUsbPermission failed", e)
        }
    }

    /**
     * デバッグ用：現在のメソッド存在チェックや状態をログ出力する簡易ユーティリティ。
     * 必要に応じて呼び出して動作確認に使える。
     */
    fun logSerialManagerInfo() {
        try {
            val cls = this::class.java
            val hasRegister = try { cls.getMethod("registerReceivers"); true } catch (_: NoSuchMethodException) { false }
            val hasUnregister = try { cls.getMethod("unregisterReceivers"); true } catch (_: NoSuchMethodException) { false }
            Log.d(TAG, "SerialManager methods: registerReceivers=$hasRegister unregisterReceivers=$hasUnregister")
        } catch (e: Exception) {
            Log.e(TAG, "logSerialManagerInfo failed", e)
        }
    }
}
