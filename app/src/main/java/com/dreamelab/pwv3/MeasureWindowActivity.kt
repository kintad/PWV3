package com.dreamelab.pwv3

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MeasureWindowActivity : AppCompatActivity() {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.dreamelab.pwv3.USB_PERMISSION"
        private const val TAG = "MeasureWindowActivity"
    }

    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var permissionPendingIntent: PendingIntent? = null
    private var usbReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val action = intent.action
                Log.d(TAG, "onReceive action=$action")
                when (action) {
                    ACTION_USB_PERMISSION -> {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(TAG, "permission granted=$granted device=$device")
                        if (granted && device != null) {
                            val ok = openUsbDevice(device)
                            Log.d(TAG, "openUsbDevice result=$ok")
                            if (ok) {
                                Toast.makeText(context, "ポートオープン成功", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "ポートオープン失敗（許可後）", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "USB許可が拒否されました", Toast.LENGTH_SHORT).show()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        Log.d(TAG, "ACTION_USB_DEVICE_DETACHED device=$device")
                        closeUsbDevice()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "usbReceiver onReceive error", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        permissionPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        registerUsbReceiverSafely()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiverSafely() {
        if (usbReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+ では明示的に exported フラグを渡す
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // API <26 はフラグ無しの呼び出し（Lint の警告は @SuppressLint で抑制）
            registerReceiver(usbReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    fun requestPermissionAndConnect(device: UsbDevice) {
        usbDevice = device
        if (usbManager?.hasPermission(device) == true) {
            Log.d(TAG, "hasPermission -> open directly")
            val ok = openUsbDevice(device)
            Log.d(TAG, "openUsbDevice result=$ok")
            if (!ok) Toast.makeText(this, "ポートオープン失敗（既に許可あり）", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "requesting permission for $device")
            try {
                usbManager?.requestPermission(device, permissionPendingIntent)
            } catch (e: Exception) {
                Log.w(TAG, "requestPermission error", e)
                Toast.makeText(this, "Permission request failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUsbDevice(device: UsbDevice): Boolean {
        closeUsbDevice()
        try {
            val conn = usbManager?.openDevice(device)
            if (conn == null) {
                Log.w(TAG, "openDevice returned null")
                return false
            }
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull()
            if (intf == null) {
                Log.w(TAG, "no interface")
                try { conn.close() } catch (_: Exception) {}
                return false
            }
            if (!conn.claimInterface(intf, true)) {
                Log.w(TAG, "claimInterface failed")
                try { conn.close() } catch (_: Exception) {}
                return false
            }
            usbDevice = device
            usbConnection = conn
            usbInterface = intf
            startIoLoop()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "openUsbDevice exception", e)
            closeUsbDevice()
            return false
        }
    }

    private fun closeUsbDevice() {
        try {
            usbConnection?.let { conn ->
                usbInterface?.let { iface ->
                    try { conn.releaseInterface(iface) } catch (_: Exception) {}
                }
                try { conn.close() } catch (_: Exception) {}
            }
        } finally {
            usbConnection = null
            usbInterface = null
            usbDevice = null
        }
        stopIoLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (usbReceiverRegistered) unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        usbReceiverRegistered = false
        closeUsbDevice()
    }

    private fun startIoLoop() { /* start reading thread */ }
    private fun stopIoLoop() { /* stop reading thread */ }
}
