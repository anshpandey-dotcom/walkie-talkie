package com.fmc.walkie

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class RadioFx(private val sampleRate: Int) {

    private val voiceHigh = Biquad.highPass(sampleRate, 380.0)
    private val voiceLow = Biquad.lowPass(sampleRate, 2700.0)
    private val voiceLowSecond = Biquad.lowPass(sampleRate, 2700.0)
    private val noiseHigh = Biquad.highPass(sampleRate, 500.0)
    private val noiseLow = Biquad.lowPass(sampleRate, 3200.0)
    private val random = Random(System.nanoTime())

    fun voice(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        for (i in input.indices) {
            var value = input[i] / 32768f
            value = voiceLowSecond.process(voiceLow.process(voiceHigh.process(value)))
            value *= 3.4f
            value /= 1f + abs(value)
            value += hiss() * 0.018f
            output[i] = toSample(value)
        }
        return output
    }

    fun squelch(millis: Int, level: Float): ShortArray {
        val count = sampleRate * millis / 1000
        return ShortArray(count) { i ->
            val fade = 1f - i.toFloat() / count
            toSample(hiss() * level * fade)
        }
    }

    fun beep(millis: Int, frequency: Double, level: Float): ShortArray {
        val count = sampleRate * millis / 1000
        val edge = sampleRate / 200
        return ShortArray(count) { i ->
            val envelope = minOf(1f, i.toFloat() / edge, (count - i).toFloat() / edge)
            toSample(sin(2.0 * PI * frequency * i / sampleRate).toFloat() * level * envelope)
        }
    }

    private fun hiss(): Float {
        val raw = random.nextFloat() * 2f - 1f
        return noiseLow.process(noiseHigh.process(raw)) * 2.2f
    }

    private fun toSample(value: Float): Short {
        return (value.coerceIn(-1f, 1f) * 30000f).toInt().toShort()
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Float): Float {
            val x = input.toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y.toFloat()
        }

        companion object {
            private const val Q = 0.707

            fun lowPass(rate: Int, cutoff: Double): Biquad {
                val w = 2.0 * PI * cutoff / rate
                val alpha = sin(w) / (2.0 * Q)
                val c = cos(w)
                val a0 = 1.0 + alpha
                return Biquad((1.0 - c) / 2.0 / a0, (1.0 - c) / a0, (1.0 - c) / 2.0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }

            fun highPass(rate: Int, cutoff: Double): Biquad {
                val w = 2.0 * PI * cutoff / rate
                val alpha = sin(w) / (2.0 * Q)
                val c = cos(w)
                val a0 = 1.0 + alpha
                return Biquad((1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }
        }
    }
}
