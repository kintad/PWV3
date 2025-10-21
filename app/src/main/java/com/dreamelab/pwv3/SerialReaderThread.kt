package com.dreamelab.pwv3

import android.util.Log
import java.io.IOException
import java.nio.charset.Charset
import com.hoho.android.usbserial.driver.UsbSerialPort

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
                        Log.d(threadTag, "read len=$len data='${s.replace("\n","\\n")}'")
                        sb.append(s)
                        var idx: Int
                        while (true) {
                            idx = sb.indexOf("\n")
                            if (idx == -1) break
                            val line = sb.substring(0, idx).trim()
                            sb.delete(0, idx + 1)
                            if (line.isEmpty()) continue
                            processLine(line)
                        }
                    }
                } catch (e: IOException) {
                    Log.e(threadTag, "read IOException", e)
                    break
                } catch (e: Exception) {
                    Log.e(threadTag, "read Exception", e)
                }
            }
        } finally {
            try { port.close() } catch (_: Exception) {}
            Log.d(threadTag, "run() exiting, port closed")
        }
    }

    private fun processLine(line: String) {
        val threadTag = "SerialReaderThread"
        Log.d(threadTag, "processLine: '$line'")
        if (line.equals("HB", ignoreCase = true) || line.equals("heartbeat", ignoreCase = true)) {
            onData(-1.0, 0.0, 0.0)
            return
        }
        val parts = line.split(",", " ", "\t").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 3) {
            try {
                val tSec = parts[0].toDoubleOrNull() ?: parts[0].toIntOrNull()?.toDouble() ?: 0.0
                val ch1 = parts[1].toDoubleOrNull() ?: 0.0
                val ch2 = parts[2].toDoubleOrNull() ?: 0.0
                onData(tSec, ch1, ch2)
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