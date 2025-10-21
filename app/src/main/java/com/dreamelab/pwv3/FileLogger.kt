package com.dreamelab.pwv3

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FileLogger(context: Context, private val filename: String = "log.txt") {
    companion object { private const val TAG = "FileLogger" }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val file: File

    init {
        val dir = context.getExternalFilesDir("logs") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        file = File(dir, filename)
        Log.d(TAG, "logging to ${file.absolutePath}")
    }

    fun appendLine(tSec: Int, ch1: Double, ch2: Double) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time, $tSec, $ch1, $ch2\n"
        executor.submit {
            try {
                FileWriter(file, true).use { it.append(line) }
            } catch (e: IOException) {
                Log.e(TAG, "appendLine failed", e)
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}