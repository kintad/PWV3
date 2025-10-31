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

    private const val TAG = "SerialManager"


    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        }
        }
        }
        }


        try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        } else {
    }
        } catch (e: Exception) {
        }
        try {
        } catch (e: Exception) {
        }
        try {
    }
                    } catch (e: Exception) {
            }
        }

                try {
                } catch (e: Exception) {
                }
            }
        }
