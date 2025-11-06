package com.dreamelab.pwv3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RealtimePlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val gridPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 1f }
    private val ch1Paint = Paint().apply { color = Color.BLUE; strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val ch2Paint = Paint().apply { color = Color.RED; strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true }

    // 表示用バッファ（相対時刻, ch1, ch2）
    private var times = FloatArray(0)
    private var ch1 = FloatArray(0)
    private var ch2 = FloatArray(0)
    private var displayWidthSec = 10.0f

    @Synchronized
    fun updateSamples(relTimes: FloatArray, ch1v: FloatArray, ch2v: FloatArray, displayWidthSec: Float = 10f) {
        this.times = relTimes
        this.ch1 = ch1v
        this.ch2 = ch2v
        this.displayWidthSec = displayWidthSec
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 軸グリッド
        val nH = 4
        for (i in 0..nH) {
            val y = i * height.toFloat() / nH
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        // 時間軸ラベル
        canvas.drawText("W=${displayWidthSec}s", 10f, 40f, textPaint)

        if (times.isEmpty() || ch1.isEmpty() || ch2.isEmpty()) return

        // 時刻は 0..displayWidthSec の範囲を想定
        val tMin = times.first()
        val tMax = max(times.last(), tMin + displayWidthSec)
        val tRange = max(0.0001f, tMax - tMin)

        // 値レンジ（自動スケール）
        var vMin = Float.MAX_VALUE
        var vMax = -Float.MAX_VALUE
        for (v in ch1) { vMin = min(vMin, v); vMax = max(vMax, v) }
        for (v in ch2) { vMin = min(vMin, v); vMax = max(vMax, v) }
        if (vMin == vMax) { vMin -= 1f; vMax += 1f }
        val vRange = vMax - vMin

        fun tx(t: Float) = ( (t - tMin) / tRange ) * width.toFloat()
        fun ty(v: Float) = height.toFloat() - ((v - vMin) / vRange) * height.toFloat()

        // パス作成
        val path1 = Path()
        val path2 = Path()
        for (i in times.indices) {
            val x = tx(times[i])
            val y1 = ty(ch1[i])
            val y2 = ty(ch2[i])
            if (i == 0) {
                path1.moveTo(x, y1)
                path2.moveTo(x, y2)
            } else {
                path1.lineTo(x, y1)
                path2.lineTo(x, y2)
            }
        }
        canvas.drawPath(path1, ch1Paint)
        canvas.drawPath(path2, ch2Paint)
    }
}
