package com.dreamelab.pwv3

import kotlin.math.abs
import kotlin.math.sqrt

object PeakUtils {
    // simple argrelmax implementation: order = 比較範囲 (前後 order 点)
    fun detectLocalMaxima(values: DoubleArray, order: Int = 200): IntArray {
        val n = values.size
        if (n == 0 || order <= 0 || n <= 2 * order) return IntArray(0)
        val out = ArrayList<Int>()
        for (i in order until n - order) {
            val vi = values[i]
            var isMax = true
            for (j in i - order .. i + order) {
                if (j == i) continue
                if (vi <= values[j]) { isMax = false; break }
            }
            if (isMax) out.add(i)
        }
        return out.toIntArray()
    }

    // 平均・標準偏差ヘルパー
    private fun mean(xs: DoubleArray): Double {
        if (xs.isEmpty()) return Double.NaN
        return xs.sum() / xs.size
    }
    private fun std(xs: DoubleArray): Double {
        if (xs.size <= 1) return 0.0
        val m = mean(xs)
        var s = 0.0
        for (v in xs) {
            val d = v - m
            s += d * d
        }
        val v = s / xs.size
        return sqrt(v)
    }

    // times: 時刻配列 (s)、peaks0/1: indices into times, vesselLengthCm: 血管長さ(cm)
    fun computePTTandPWV(
        times: DoubleArray,
        peaks0: IntArray,
        peaks1: IntArray,
        vesselLengthCm: Double,
        maxPairs: Int = 10
    ): PttPwvResult {
        if (times.isEmpty() || peaks0.isEmpty() || peaks1.isEmpty()) {
            return PttPwvResult(null, null, null, null, 0)
        }

        // peaks -> time に安全にマップ
        val t0List = ArrayList<Double>()
        for (idx in peaks0) {
            if (idx in times.indices) t0List.add(times[idx])
        }
        val t1List = ArrayList<Double>()
        for (idx in peaks1) {
            if (idx in times.indices) t1List.add(times[idx])
        }

        val availPairs = minOf(t0List.size, t1List.size)
        val nTake = minOf(availPairs, maxPairs)
        if (nTake == 0) return PttPwvResult(null, null, null, null, 0)

        val t0Last = t0List.takeLast(nTake)
        val t1Last = t1List.takeLast(nTake)

        // 明示的ループで差分を計算しフィルター
        val pttCandidatesList = ArrayList<Double>()
        val pairCount = minOf(t0Last.size, t1Last.size)
        for (i in 0 until pairCount) {
            val a = t0Last[i]
            val b = t1Last[i]
            val diff = abs(b - a)
            if (diff > 0.0 && diff < 0.15) {
                pttCandidatesList.add(diff)
            }
        }

        if (pttCandidatesList.isEmpty()) {
            return PttPwvResult(null, null, null, null, 0)
        }

        val pttCandidates = DoubleArray(pttCandidatesList.size) { i -> pttCandidatesList[i] }

        val pttMean = mean(pttCandidates)
        val pttStd = std(pttCandidates)
        val pttMeanMs = pttMean * 1000.0
        val pttStdMs = pttStd * 1000.0

        val lengthM = vesselLengthCm / 100.0
        val pwvList = DoubleArray(pttCandidates.size) { i -> lengthM / pttCandidates[i] }
        val pwvMean = mean(pwvList)
        val pwvStd = std(pwvList)

        return PttPwvResult(pttMeanMs, pttStdMs, pwvMean, pwvStd, pttCandidates.size)
    }
}