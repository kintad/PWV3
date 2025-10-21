package com.dreamelab.pwv3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

class Receiver(private val onPermissionGranted: (UsbDevice?) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.dreamelab.pwv3.USB_PERMISSION") {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            var device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            if (granted) {
                // intent の EXTRA_DEVICE が null の場合はフォールバックで deviceList を走査して許可済みのものを探す
                if (device == null && context != null) {
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                    device = usbManager?.deviceList?.values?.firstOrNull { d ->
                        usbManager.hasPermission(d)
                    }
                }
                onPermissionGranted(device)
            } else {
                onPermissionGranted(null)
            }
        }
    }
}