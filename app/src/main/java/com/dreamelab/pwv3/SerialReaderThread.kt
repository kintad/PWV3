package com.dreamelab.pwv3

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList

class SerialReaderThread(
    private val input: InputStream,
    private val onDataBg: ((tSec: Double, ch1: Int, ch2: Int) -> Unit)? = null,
    private val onDataUi: ((tSec: Double, ch1: Int, ch2: Int) -> Unit)? = null
) : Thread() {

    @Volatile
    private var running = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tmp = ByteArray(256)
    private val buffer = ArrayList<Byte>()

    fun stopRunning() {
        running = false
        interrupt()
        try {
            input.close()
        } catch (_: IOException) { }
    }

    override fun run() {
        try {
            while (running && !isInterrupted) {
                val read = try {
                    input.read(tmp)
                } catch (e: IOException) {
                    break
                }
                if (read > 0) {
                    // append read bytes to buffer
                    for (i in 0 until read) buffer.add(tmp[i])
                    // parse packets while possible
                    loop@ while (buffer.size >= 9) {
                        val first = buffer[0].toInt() and 0xFF
                        if (first != 0xFF) {
                            // discard until header found
                            buffer.removeAt(0)
                            continue@loop
                        }
                        // ensure 9 bytes available
                        if (buffer.size < 9) break@loop
                        // extract 9 bytes
                        val packet = ByteArray(9)
                        for (i in 0 until 9) packet[i] = buffer.removeAt(0)
                        // parse timestamp (little-endian, bytes 1..4)
                        val tUs = (packet[1].toLong() and 0xFF) or
                                ((packet[2].toLong() and 0xFF) shl 8) or
                                ((packet[3].toLong() and 0xFF) shl 16) or
                                ((packet[4].toLong() and 0xFF) shl 24)
                        val tSec = tUs / 1_000_000.0
                        // parse channels (big-endian 2 bytes each)
                        val ch1 = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
                        val ch2 = ((packet[7].toInt() and 0xFF) shl 8) or (packet[8].toInt() and 0xFF)
                        // background callback
                        try {
                            onDataBg?.invoke(tSec, ch1, ch2)
                        } catch (_: Exception) { }
                        // UI callback on main thread
                        if (onDataUi != null) {
                            mainHandler.post {
                                try {
                                    onDataUi.invoke(tSec, ch1, ch2)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                } else {
                    // avoid busy loop
                    try { sleep(1) } catch (_: InterruptedException) { break }
                }
            }
        } catch (_: Throwable) {
            // thread ending
        } finally {
            try { input.close() } catch (_: IOException) {}
        }
    }
}
