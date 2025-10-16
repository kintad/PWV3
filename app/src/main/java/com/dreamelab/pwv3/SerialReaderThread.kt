package com.dreamelab.pwv3

import com.hoho.android.usbserial.driver.UsbSerialPort

class SerialReaderThread : Thread {
    private val port: UsbSerialPort
    private val onDataReceived: (Double, Int, Int) -> Unit

    constructor(port: UsbSerialPort, onDataReceived: (Double, Int, Int) -> Unit) : super() {
        this.port = port
        this.onDataReceived = onDataReceived
    }

    var running = true

    override fun run() {
        val buffer =  mutableListOf<Byte>()
        while (running) {
            try {
                val readBuffer = ByteArray(100)
                val readSize = port.read(readBuffer, 100) // readSizeはInt型
                val available: List<Byte> = buffer.take(readSize)
                if (available.isNotEmpty()) {
                    buffer.addAll(available.toList())
                    while (buffer.size >= 9) {
                        if (buffer[0] != 0xFF.toByte()) {
                            buffer.removeAt(0)
                            continue
                        }
                        val packet = buffer.take(9)
                        buffer.subList(0, 9).clear()
                        val tUs = (packet[1].toInt() and 0xFF) or
                                ((packet[2].toInt() and 0xFF) shl 8) or
                                ((packet[3].toInt() and 0xFF) shl 16) or
                                ((packet[4].toInt() and 0xFF) shl 24)
                        val tSec = tUs / 1_000_000.0
                        val ch1 = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
                        val ch2 = ((packet[7].toInt() and 0xFF) shl 8) or (packet[8].toInt() and 0xFF)
                        onDataReceived(tSec, ch1, ch2)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
    }

    fun stopThread() {
        running = false
    }
}
