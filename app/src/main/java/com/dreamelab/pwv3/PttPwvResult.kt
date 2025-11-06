package com.dreamelab.pwv3

data class PttPwvResult(
    val pttMeanMs: Double?,   // 平均 PTT (ms) - null は計算不可を意味する
    val pttStdMs: Double?,
    val pwvMean: Double?,     // 平均 PWV (m/s)
    val pwvStd: Double?,
    val nPairs: Int           // 実際に使ったペア数
)