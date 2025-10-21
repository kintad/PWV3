package com.dreamelab.pwv3

object SerialDataBus {
    private var listener: ((tSec: Double, ch1: Int, ch2: Int) -> Unit)? = null

    fun setListener(l: ((Double, Int, Int) -> Unit)?) {
        listener = l
    }

    fun post(tSec: Double, ch1: Int, ch2: Int) {
        listener?.invoke(tSec, ch1, ch2)
    }
}