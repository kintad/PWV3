package  com.dreamelab.pwv3.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dreamelab.pwv3.R

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var samples: FloatArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.purple_500) // 例の色
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val path = Path()

    fun setSamples(s: FloatArray?) {
        samples = s
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // デフォルト高さを 240dp に設定（必要なら変更）
        val defaultHeight = (240 * resources.displayMetrics.density).toInt()
        val h = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = samples ?: return
        if (s.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        path.reset()
        val step = w / (s.size - 1).coerceAtLeast(1)

        // 値を -1..1 に正規化して描画する想定
        for (i in s.indices) {
            val x = i * step
            val y = centerY - (s[i].coerceIn(-1f, 1f) * (h / 2f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}