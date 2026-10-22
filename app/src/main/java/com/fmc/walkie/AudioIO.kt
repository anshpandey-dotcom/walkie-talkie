package com.fmc.walkie

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

@SuppressLint("MissingPermission")
class AudioIO {

    private val sampleRate = 8000
    private val frameSize = 320
    private val queue = LinkedBlockingQueue<ShortArray>()

    @Volatile
    private var running = false

    @Volatile
    var gain = 1f

    @Volatile
    var muted = false

    @Volatile
    var transmit = true

    fun start(onFrame: (ByteArray) -> Unit, onLevel: (Double) -> Unit) {
        if (running) return
        running = true
        queue.clear()
        Thread { playLoop() }.start()
        Thread { captureLoop(onFrame, onLevel) }.start()
    }

    fun stop() {
        running = false
        queue.clear()
    }

    fun receive(data: ByteArray, offset: Int, length: Int) {
        if (!running || muted) return
        val count = length / 2
        val pcm = ShortArray(count) { i ->
            val index = offset + i * 2
            var value = ((data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)) * gain
            if (value > 32767f) value = 32767f else if (value < -32768f) value = -32768f
            value.toInt().toShort()
        }
        while (queue.size > 16) queue.poll()
        queue.offer(pcm)
    }

    private fun playLoop() {
        val minSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minSize, frameSize * 2) * 3)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            track.write(chunk, 0, chunk.size)
        }
        try {
            track.stop()
        } catch (e: IllegalStateException) {
            queue.clear()
        }
        track.release()
    }

    private fun captureLoop(onFrame: (ByteArray) -> Unit, onLevel: (Double) -> Unit) {
        val minSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minSize, frameSize * 2) * 4
        val recorder = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufferSize)
            ?: open(MediaRecorder.AudioSource.MIC, bufferSize) ?: return
        val effects = attachEffects(recorder.audioSessionId)
        recorder.startRecording()
        val frame = ShortArray(frameSize)
        while (running) {
            val count = recorder.read(frame, 0, frameSize)
            if (count < 0) break
            if (count == 0) continue
            onLevel(level(frame, count))
            if (transmit) onFrame(toBytes(frame, count))
        }
        try {
            recorder.stop()
        } catch (e: IllegalStateException) {
            effects.forEach { it.release() }
        }
        recorder.release()
        effects.forEach { it.release() }
    }

    private fun open(source: Int, bufferSize: Int): AudioRecord? {
        val recorder = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
        recorder.release()
        return null
    }

    private fun attachEffects(session: Int): List<AudioEffect> {
        val effects = mutableListOf<AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { effects.add(it) }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { effects.add(it) }
        effects.forEach { it.enabled = true }
        return effects
    }

    private fun level(frame: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) sum += frame[i].toDouble() * frame[i]
        return sqrt(sum / count)
    }

    private fun toBytes(frame: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = frame[i].toInt()
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }
}
