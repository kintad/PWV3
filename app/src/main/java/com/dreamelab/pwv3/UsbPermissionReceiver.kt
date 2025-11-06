package com.dreamelab.pwv3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast

private const val TAG = "USB_DEBUG"
const val ACTION_USB_PERMISSION = "com.dreamelab.pwv3.USB_PERMISSION"

// BroadcastReceiver: 権限応答と attach/detach をログ
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_USB_PERMISSION -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "ACTION_USB_PERMISSION: device=${device?.deviceName} granted=$granted")
                Toast.makeText(context, "USB perm: $granted ${device?.deviceName}", Toast.LENGTH_SHORT).show()
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                Log.d(TAG, "DEVICE_ATTACHED: ${dev?.deviceName}")
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                Log.d(TAG, "DEVICE_DETACHED: ${dev?.deviceName}")
            }
        }
    }
}

// 以下を SettingsActivity のメソッドとして追加する
// フィールド例:
// private lateinit var usbManager: UsbManager
// private val usbPermissionReceiver = UsbPermissionReceiver()

fun setupUsbInActivity(context: Context) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    // ログ出力
    val deviceList = usbManager.deviceList
    Log.d(TAG, "logConnectedUsbDevices: count=${deviceList.size}")
    if (deviceList.isEmpty()) {
        Toast.makeText(context, "usbManager.deviceList が空です。OTG/ケーブル/電源を確認してください。", Toast.LENGTH_LONG).show()
    } else {
        for ((key, dev) in deviceList) {
            Log.d(TAG, "USB device key=$key name=${dev.deviceName} vid=0x${dev.vendorId.toString(16)} pid=0x${dev.productId.toString(16)}")
            // 例: 最初のデバイスに権限要求
            requestPermissionForDevice(context, usbManager, dev)
        }
    }
}

fun requestPermissionForDevice(context: Context, usbManager: UsbManager, device: UsbDevice) {
    if (usbManager.hasPermission(device)) {
        Log.d(TAG, "Already has permission for ${device.deviceName}")
        return
    }
    val pi = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    )
    usbManager.requestPermission(device, pi)
    Log.d(TAG, "Requested permission for ${device.deviceName}")
}

// register/unregister helper（Activity の onCreate/onDestroy で呼ぶ）
fun registerUsbReceiverCompat(context: Context, receiver: UsbPermissionReceiver) {
    val filter = IntentFilter().apply {
        addAction(ACTION_USB_PERMISSION)
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
    }
}

fun unregisterUsbReceiverCompat(context: Context, receiver: UsbPermissionReceiver) {
    try {
        context.unregisterReceiver(receiver)
    } catch (e: Exception) {
        Log.w(TAG, "unregister failed", e)
    }
}