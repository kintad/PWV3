package com.dreamelab.pwv3.analysis

data class AnalysisResult(
    val peaks0: IntArray,
    val peaks1: IntArray,
    val rising0: IntArray?,
    val rising1: IntArray?,
    val pttMeanMs: Double?,
    val pttStdMs: Double?,
    val pwvMean: Double?,
    val pwvStd: Double?
)

class PeakAnalyzer(
    private val vesselLengthCm: Double,
    var mode: String = "peak",
    var detectPercent: Int = 0
) {

    // 単純な局所最大検出（近傍 order）
    private fun findLocalMaxima(values: DoubleArray, order: Int = 5): IntArray {
        val list = ArrayList<Int>()
        val n = values.size
        for (i in order until n - order) {
            var ok = true
            val v = values[i]
            for (k in i - order .. i + order) {
                if (k == i) continue
                if (values[k] >= v) { ok = false; break }
            }
            if (ok) list.add(i)
        }
        return list.toIntArray()
    }

    private fun findRisingPoint(times: DoubleArray, values: DoubleArray, peakIdx: Int): Int {
        val peakVal = values[peakIdx]
        // baseline: 最後の局所最小（単純にピーク前で最小値を採る）
        val before = values.copyOfRange(0, peakIdx + 1)
        var baselineIdx = 0
        var baselineVal = before[0]
        for (i in before.indices) {
            if (before[i] < baselineVal) { baselineVal = before[i]; baselineIdx = i }
        }
        val target = baselineVal + (peakVal - baselineVal) * detectPercent / 100.0
        var baseIdx = baselineIdx
        for (i in baselineIdx until peakIdx) {
            if (values[i] < target) baseIdx = i
        }
        // 直線交点近似：detect point -> peak を結ぶ直線と baseline の交点
        val x0 = times[baseIdx]; val y0 = values[baseIdx]
        val x1 = times[peakIdx]; val y1 = values[peakIdx]
        if (x1 == x0) return baseIdx
        val a = (y1 - y0) / (x1 - x0)
        val b = y0 - a * x0
        return if (a != 0.0) {
            val crossTime = (baselineVal - b) / a
            // 最も近い index を返す
            var idx = 0
            var best = Double.POSITIVE_INFINITY
            for (i in 0 until times.size) {
                val d = kotlin.math.abs(times[i] - crossTime)
                if (d < best) { best = d; idx = i }
            }
            idx
        } else baseIdx
    }

    fun analyze(times: DoubleArray, ch0: DoubleArray, ch1: DoubleArray, order: Int = 5): AnalysisResult {
        val peaks0 = if (mode == "peak") findLocalMaxima(ch0, order) else findLocalMaxima(ch0, 200)
        val peaks1 = if (mode == "peak") findLocalMaxima(ch1, order) else findLocalMaxima(ch1, 200)
        val rising0 = if (mode == "rising") peaks0.map { findRisingPoint(times, ch0, it) }.toIntArray() else null
        val rising1 = if (mode == "rising") peaks1.map { findRisingPoint(times, ch1, it) }.toIntArray() else null

        // PTT/PWV 計算（最新 n ペア）
        val t0List = (if (mode == "rising") rising0 ?: peaks0 else peaks0).map { times[it] }.toDoubleArray()
        val t1List = (if (mode == "rising") rising1 ?: peaks1 else peaks1).map { times[it] }.toDoubleArray()
        val n = minOf(t0List.size, t1List.size, 10)
        if (n == 0) return AnalysisResult(peaks0, peaks1, rising0, rising1, null, null, null, null)
        val t0last = t0List.takeLast(n).toDoubleArray()
        val t1last = t1List.takeLast(n).toDoubleArray()
        val ptt = DoubleArray(n) { kotlin.math.abs(t1last[it] - t0last[it]) }
        val valid = ptt.filter { it > 0.0 && it < 0.15 }.toDoubleArray()
        if (valid.isEmpty()) return AnalysisResult(peaks0, peaks1, rising0, rising1, null, null, null, null)
        val mean = valid.average()
        val std = kotlin.math.sqrt(valid.map { (it - mean)*(it - mean) }.average())
        val meanMs = mean * 1000.0
        val stdMs = std * 1000.0
        val pwv = valid.map { (vesselLengthCm / 100.0) / it }.toDoubleArray()
        val pwvMean = pwv.average()
        val pwvStd = kotlin.math.sqrt(pwv.map { (it - pwvMean)*(it - pwvMean) }.average())

        return AnalysisResult(peaks0, peaks1, rising0, rising1, meanMs, stdMs, pwvMean, pwvStd)
    }
}