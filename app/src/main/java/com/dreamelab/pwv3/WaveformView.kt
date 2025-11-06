package com.dreamelab.pwv3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        strokeWidth = resources.displayMetrics.density * 2f
        style = Paint.Style.STROKE
    }

    // リングバッファ（表示点数）
    private var bufferSize = 2048
    private var samples = FloatArray(bufferSize)
    private var writePos = 0
    private var filled = 0

    // 外部からサンプルを追加する（配列をそのまま追加）
    fun appendSamples(incoming: FloatArray) {
        var offset = 0
        while (offset < incoming.size) {
            val space = bufferSize - writePos
            val toCopy = min(space, incoming.size - offset)
            System.arraycopy(incoming, offset, samples, writePos, toCopy)
            writePos = (writePos + toCopy) % bufferSize
            offset += toCopy
            filled = min(bufferSize, filled + toCopy)
        }
        postInvalidateOnAnimation()
    }

    // 必要ならバッファサイズ変更を許す
    fun setBufferCapacity(capacity: Int) {
        if (capacity <= 0) return
        val newBuf = FloatArray(capacity)
        val copyCount = min(filled, capacity)
        // 最新データを保持して移す（古いデータを捨てる）
        val start = (writePos - copyCount + bufferSize) % bufferSize
        for (i in 0 until copyCount) {
            newBuf[i] = samples[(start + i) % bufferSize]
        }
        samples = newBuf
        bufferSize = capacity
        filled = copyCount
        writePos = copyCount % bufferSize
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (filled == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val step = w / (filled - 1).coerceAtLeast(1)

        var idx = (writePos - filled + bufferSize) % bufferSize
        var x = 0f
        var prevX = 0f
        var prevY = midY

        // 簡易ポリライン描画
        for (i in 0 until filled) {
            val v = samples[(idx + i) % bufferSize]
            val y = midY - (v * midY) // incoming sample expected in -1..1
            if (i == 0) {
                prevX = x
                prevY = y
            } else {
                canvas.drawLine(prevX, prevY, x, y, paint)
                prevX = x
                prevY = y
            }
            x += step
        }
    }
}
