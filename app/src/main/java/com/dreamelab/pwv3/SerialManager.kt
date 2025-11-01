// kotlin
package com.dreamelab.pwv3

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList

object SerialManager {
    private const val TAG = "SerialManager"
    private const val ACTION_USB_PERMISSION = "com.dreamelab.pwv3.USB_PERMISSION"

    private var appContext: Context? = null
    private var usbManager: UsbManager? = null

    private val dataListeners = CopyOnWriteArrayList<(Double, Double, Double) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var registeredReceiver = false
    private var serialThread: SerialReaderThread? = null
    private var currentPort: UsbSerialPort? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "usbPermissionReceiver action=${intent?.action}")
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    notifyStatus("USB許可取得: 開始")
                    openSerialFromDevice(device)
                } else {
                    notifyStatus("USB許可が拒否されました")
                    Toast.makeText(appContext, "USB許可が拒否されました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun register(context: Context, onData: (Double, Double, Double) -> Unit, onStatus: (String) -> Unit) {
        if (appContext == null) {
            appContext = context.applicationContext
            usbManager = appContext?.getSystemService(Context.USB_SERVICE) as UsbManager
        }
        dataListeners.add(onData)
        statusListeners.add(onStatus)
        ensureReceiverRegistered()
        notifyStatus("SerialManager 登録: listeners=${dataListeners.size}")
    }

    fun unregister(onData: (Double, Double, Double) -> Unit, onStatus: (String) -> Unit) {
        dataListeners.remove(onData)
        statusListeners.remove(onStatus)
        notifyStatus("SerialManager 解除: listeners=${dataListeners.size}")
        if (dataListeners.isEmpty() && statusListeners.isEmpty()) {
            releaseAll()
        }
    }

    fun findAndRequestPermissionAndOpen() {
        // 受信器を確実に登録しておく（許可フローのため）
        ensureReceiverRegistered()

        val um = usbManager ?: run {
            notifyStatus("UsbManager 未初期化")
            return
        }
        val prober = UsbSerialProber.getDefaultProber()
        val deviceList = um.deviceList
        notifyStatus("デバイス検出: ${deviceList.size}")

        val drivers = prober.findAllDrivers(um)
        val device = when {
            drivers.isNotEmpty() -> drivers[0].device
            deviceList.isNotEmpty() -> deviceList.values.first()
            else -> null
        }

        if (device == null) {
            notifyStatus("USBデバイスが見つかりません")
            return
        }

        notifyStatus("対象デバイス: vendor=${device.vendorId} product=${device.productId}")

        if (um.hasPermission(device)) {
            notifyStatus("既に許可あり。接続を試行します")
            try {
                openSerialFromDevice(device)
            } catch (e: Exception) {
                notifyStatus("openSerialFromDevice 例外: ${e.message}")
                Log.w(TAG, "openSerialFromDevice failed", e)
            }
            return
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(appContext?.packageName) }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(appContext, 0, intent, piFlags)
        um.requestPermission(device, permissionIntent)
        notifyStatus("USB許可ダイアログを表示しました")
    }

    private fun openSerialFromDevice(device: UsbDevice) {
        val um = usbManager ?: run {
            notifyStatus("UsbManager 未初期化 (openSerialFromDevice)")
            return
        }
        val prober = UsbSerialProber.getDefaultProber()
        val driver = prober.probeDevice(device)
        if (driver == null) {
            notifyStatus("ドライバが見つかりません")
            return
        }
        notifyStatus("ドライバ検出: ${driver.device.deviceName}, ports=${driver.ports.size}")
        // 修正: 同期版 openPortAndStart -> 非同期版 openPortAndStartAsync を呼ぶ
        openPortAndStartAsync(driver)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val ctx = appContext ?: run {
            notifyStatus("appContext が未初期化 (requestUsbPermission)")
            return
        }
        try {
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(ctx.packageName) }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(ctx, 0, intent, piFlags)
            usbManager?.requestPermission(device, permissionIntent)
            notifyStatus("USB許可を再要求しました (device=${device.deviceName})")
        } catch (e: Exception) {
            notifyStatus("requestUsbPermission 失敗: ${e.message}")
            Log.w(TAG, "requestUsbPermission failed", e)
        }
    }

    private fun openPortAndStartAsync(driver: com.hoho.android.usbserial.driver.UsbSerialDriver) {
        Thread {
            try {
                val um = usbManager ?: run {
                    notifyStatus("UsbManager 未初期化 (openPortAndStartAsync)")
                    return@Thread
                }
                val device = driver.device
                notifyStatus("openPortAndStartAsync 開始 device=${device.deviceName}")

                // 既存のポート/スレッドを安全に閉じる
                try {
                    serialThread?.interrupt()
                    serialThread = null
                } catch (e: Exception) {
                    Log.w(TAG, "serialThread interrupt failed", e)
                }
                try {
                    currentPort?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "currentPort close failed", e)
                } finally {
                    currentPort = null
                }

                val port = driver.ports.firstOrNull()
                if (port == null) {
                    notifyStatus("ポートが見つかりません: ports=${driver.ports.size}")
                    return@Thread
                }

                // openDevice を複数回試行する（タイミング/競合対策）
                var connection: UsbDeviceConnection? = null
                var attempts = 0
                val maxAttempts = 5
                while (attempts < maxAttempts) {
                    attempts++
                    try {
                        connection = um.openDevice(device)
                        if (connection != null) {
                            notifyStatus("openDevice 成功 (attempt=$attempts)")
                            break
                        } else {
                            notifyStatus("openDevice returned null (attempt=$attempts)")
                            Log.w(TAG, "openDevice returned null for $device (attempt=$attempts)")

                            // 権限を再要求する（念のため）
                            requestUsbPermission(device)
                            Thread.sleep(250L)
                        }
                    } catch (e: Exception) {
                        notifyStatus("openDevice 例外: ${e.message} (attempt=$attempts)")
                        Log.w(TAG, "openDevice exception", e)
                        try { Thread.sleep(250L) } catch (_: InterruptedException) { break }
                    }
                }

                if (connection == null) {
                    notifyStatus("デバイス接続に失敗しました（複数回試行）")
                    return@Thread
                }

                try {
                    notifyStatus("ポートを開いて設定を行います")
                    port.open(connection)
                    port.setParameters(500000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    try { port.javaClass.getMethod("setDTR", Boolean::class.javaPrimitiveType).invoke(port, true) } catch (_: Exception) {}
                    try { port.javaClass.getMethod("setRTS", Boolean::class.javaPrimitiveType).invoke(port, true) } catch (_: Exception) {}

                    currentPort = port
                    notifyStatus("ポートをオープンしました")
                    Log.d(TAG, "Port opened: $port")
                } catch (e: Exception) {
                    notifyStatus("ポートオープン失敗: ${e.message}")
                    Log.w(TAG, "port open failed", e)
                    try { connection.close() } catch (_: Exception) {}
                    return@Thread
                }

                // 読み取りスレッド開始
                try {
                    serialThread?.interrupt()
                    serialThread = SerialReaderThread(port) { t, c1, c2 -> notifyData(t, c1, c2) }
                    serialThread?.start()
                    notifyStatus("シリアル読み取りスレッドを開始しました")
                } catch (e: Exception) {
                    notifyStatus("serial thread start failed: ${e.message}")
                    Log.w(TAG, "serial thread start failed", e)
                }
            } catch (e: Exception) {
                notifyStatus("openPortAndStartAsync 例外: ${e.message}")
                Log.w(TAG, "openPortAndStartAsync failed", e)
            }
        }.start()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureReceiverRegistered() {
        if (registeredReceiver) return
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        registeredReceiver = true
        notifyStatus("usbPermissionReceiver を登録しました")
    }

    private fun releaseAll() {
        notifyStatus("SerialManager 解放")
        try {
            if (registeredReceiver) {
                appContext?.unregisterReceiver(usbPermissionReceiver)
                registeredReceiver = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "unregisterReceiver failed", e)
        }
        try {
            serialThread?.interrupt()
            serialThread = null
        } catch (e: Exception) {
            Log.w(TAG, "serial thread stop failed", e)
        }
        try {
            currentPort?.close()
            currentPort = null
        } catch (_: Exception) {}
    }

    private fun notifyData(t: Double, c1: Double, c2: Double) {
        for (l in dataListeners) {
            try { l(t, c1, c2) } catch (e: Exception) { Log.w(TAG, "data listener failed", e) }
        }
    }

    private fun notifyStatus(msg: String) {
        Log.d(TAG, msg)
        for (l in statusListeners) {
            try { l(msg) } catch (e: Exception) { Log.w(TAG, "status listener failed", e) }
        }
    }

    // 内部受信スレッド（簡易）
    private class SerialReaderThread(
        private val port: UsbSerialPort,
        private val onData: (Double, Double, Double) -> Unit
    ) : Thread() {
        @Volatile
        private var running = true

        override fun run() {
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            try {
                while (running && !isInterrupted) {
                    try {
                        val len = port.read(buf, 200)
                        if (len > 0) {
                            val s = String(buf, 0, len, Charset.forName("UTF-8"))
                            sb.append(s)
                            var idx: Int
                            while (true) {
                                idx = sb.indexOf("\n")
                                if (idx == -1) break
                                val line = sb.substring(0, idx).trim()
                                sb.delete(0, idx + 1)
                                if (line.isEmpty()) continue
                                parseLine(line)
                            }
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "read IOException", e)
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "read Exception", e)
                    }
                }
            } finally {
                try { port.close() } catch (_: Exception) {}
            }
        }

        private fun parseLine(line: String) {
            if (line.equals("HB", ignoreCase = true) || line.equals("heartbeat", ignoreCase = true)) {
                onData(-1.0, 0.0, 0.0); return
            }
            val parts = line.split(",", " ", "\t").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                try {
                    val t = parts[0].toDoubleOrNull() ?: parts[0].toIntOrNull()?.toDouble() ?: 0.0
                    val c1 = parts[1].toDoubleOrNull() ?: 0.0
                    val c2 = parts[2].toDoubleOrNull() ?: 0.0
                    onData(t, c1, c2)
                } catch (e: Exception) {
                    Log.e(TAG, "parse error", e)
                }
            }
        }

        override fun interrupt() {
            running = false
            super.interrupt()
        }
    }
}
