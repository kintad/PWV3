package com.dreamelab.pwv3.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FIRFilter(
    var fs: Double = 1000.0,
    var numtaps: Int = 101,
    var f1: Double = 0.4,
    var f2: Double = 10.0
) {
    private var b: DoubleArray? = null

    fun design() {
        // 簡易: bandpass を窓付き sinc で作る（f1 < f2）
        val M = if (numtaps % 2 == 0) numtaps + 1 else numtaps
        val h = DoubleArray(M)
        val fc1 = f1 / fs
        val fc2 = f2 / fs
        val m0 = (M - 1) / 2.0
        for (n in 0 until M) {
            val k = n - m0
            val ideal = if (k == 0.0) {
                2.0 * (fc2 - fc1)
            } else {
                (sin(2.0 * PI * fc2 * k) - sin(2.0 * PI * fc1 * k)) / (PI * k)
            }
            // Hamming window
            val win = 0.54 - 0.46 * kotlin.math.cos(2.0 * PI * n / (M - 1))
            h[n] = ideal * win
        }
        b = h
    }

    fun apply(data: DoubleArray): DoubleArray {
        val coeff = b ?: return data.copyOf()
        val n = data.size
        val m = coeff.size
        if (n < m) return data.copyOf()
        // 単純な線形畳み込み + 前後逆順で疑似ゼロ位相
        val forward = convolve(data, coeff)
        val reversed = forward.reversedArray()
        val backward = convolve(reversed, coeff)
        return backward.reversedArray()
    }

    private fun convolve(x: DoubleArray, h: DoubleArray): DoubleArray {
        val nx = x.size
        val nh = h.size
        val y = DoubleArray(nx)
        val half = nh / 2
        for (i in 0 until nx) {
            var s = 0.0
            for (k in 0 until nh) {
                val xi = i - k + half
                if (xi in 0 until nx) s += h[k] * x[xi]
            }
            y[i] = s
        }
        return y
    }

    fun smooth(data: DoubleArray, windowLength: Int = 5): DoubleArray {
        val w = if (windowLength <= 1) 1 else windowLength
        val out = DoubleArray(data.size)
        val half = w / 2
        for (i in data.indices) {
            var s = 0.0
            var cnt = 0
            for (j in (i - half)..(i + half)) {
                if (j in data.indices) { s += data[j]; cnt++ }
            }
            out[i] = if (cnt > 0) s / cnt else data[i]
        }
        return out
    }
}