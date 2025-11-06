package com.dreamelab.pwv3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SineWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var bufferSize = 512
    private var buffer = FloatArray(bufferSize)
    private var writeIndex = 0
    private var filled = false

    fun setBufferSize(size: Int) {
        if (size <= 0) return
        bufferSize = size
        buffer = FloatArray(bufferSize)
        writeIndex = 0
        filled = false
        invalidate()
    }

    // 呼び出し元は UI スレッドで呼ぶか post して呼ぶこと
    fun pushSample(value: Float) {
        buffer[writeIndex] = value
        writeIndex++
        if (writeIndex >= bufferSize) {
            writeIndex = 0
            filled = true
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val path = Path()
        val visibleCount = if (filled) bufferSize else writeIndex
        if (visibleCount == 0) return

        val xStep = width.toFloat() / max(1, visibleCount - 1)
        // スケーリング：入力が -1..1 想定。必要なら調整。
        val scale = height * 0.4f
        val cy = height / 2f

        // oldest -> newest を描画するために循環バッファを考慮
        val start = if (filled) (writeIndex) % bufferSize else 0
        for (i in 0 until visibleCount) {
            val idx = (start + i) % bufferSize
            val sample = buffer[idx]
            val x = i * xStep
            val y = cy - (sample * scale)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, paint)
    }
}
