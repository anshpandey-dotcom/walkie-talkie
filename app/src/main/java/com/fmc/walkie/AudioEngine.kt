package com.fmc.walkie

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

@SuppressLint("MissingPermission")
class AudioEngine {

    private val sampleRate = 8000
    private val scratch = ByteArray(8192)
    private var carry = -1
    private var counter = 0

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var active = 0

    @Synchronized
    fun startPlayback() {
        stopPlayback()
        val minSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val created = AudioTrack.Builder()
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
            .setBufferSizeInBytes(minSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        carry = -1
        created.play()
        track = created
    }

    @Synchronized
    fun stopPlayback() {
        val current = track ?: return
        track = null
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            carry = -1
        }
        current.release()
    }

    fun play(data: ByteArray, length: Int) {
        val current = track ?: return
        var size = 0
        if (carry >= 0) {
            scratch[0] = carry.toByte()
            size = 1
            carry = -1
        }
        System.arraycopy(data, 0, scratch, size, length)
        size += length
        if (size % 2 != 0) {
            carry = scratch[size - 1].toInt() and 0xFF
            size -= 1
        }
        if (size > 0) current.write(scratch, 0, size)
    }

    fun startTalking(send: (ByteArray, Int) -> Boolean) {
        if (active != 0) return
        val minSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minSize * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        counter += 1
        val id = counter
        active = id
        recorder.startRecording()
        Thread {
            val buffer = ByteArray(640)
            while (active == id) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0 && !send(buffer, count)) break
            }
            if (active == id) active = 0
            try {
                recorder.stop()
            } catch (e: IllegalStateException) {
                active = active
            }
            recorder.release()
        }.start()
    }

    fun stopTalking() {
        active = 0
    }
}
