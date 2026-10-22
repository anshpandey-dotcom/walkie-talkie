package com.fmc.walkie

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

object Op {
    const val AUDIO: Byte = 1
    const val VIDEO: Byte = 2
    const val PHOTO: Byte = 3
    const val ALERT: Byte = 4
    const val STATE: Byte = 5
    const val COMMAND: Byte = 6
}

object Cmd {
    const val FLASH: Byte = 1
    const val SWITCH: Byte = 2
    const val STREAM: Byte = 3
    const val MIC: Byte = 4
    const val SOS: Byte = 5
    const val SPEAK: Byte = 6
    const val SCREEN: Byte = 7
    const val PHOTO: Byte = 8
    const val DETECT: Byte = 9
    const val RECORD: Byte = 10
    const val TALK: Byte = 11
}

@SuppressLint("MissingPermission")
class Link(private val context: Context, private val asCamera: Boolean, private val listener: Listener) {

    interface Listener {
        fun onState(text: String)
        fun onFound(id: String, name: String)
        fun onConnectRequest(id: String)
        fun onConnected()
        fun onSecure(code: String)
        fun onDisconnected()
        fun onMessage(type: Byte, data: ByteArray, offset: Int, length: Int)
        fun onQuality(rttMs: Int, percent: Int)
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val uuid: UUID = UUID.fromString("5f3c1a0e-7b2d-4c58-9a41-0d6e8b2f7c13")
    private val handler = Handler(Looper.getMainLooper())
    private val answered = BooleanArray(8)
    private val writeLock = Any()
    private var pingSeq = 0
    private var rtt = 0

    @Volatile
    private var closed = false

    @Volatile
    private var channel: SecureChannel? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private var receiverRegistered = false
    private var pendingKey: ByteArray? = null

    val isConnected: Boolean
        get() = output != null

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = deviceOf(intent) ?: return
                    listener.onFound(device.address, device.name ?: "Unknown device")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> listener.onState("Scan finished. Tap a camera to connect.")
            }
        }
    }

    private val pinger = object : Runnable {
        override fun run() {
            if (output == null) return
            listener.onQuality(rtt, answered.count { it } * 100 / answered.size)
            pingSeq += 1
            answered[pingSeq % answered.size] = false
            internal(PING, ByteBuffer.allocate(12).putInt(pingSeq).putLong(SystemClock.elapsedRealtime()).array())
            handler.postDelayed(this, 1000)
        }
    }

    fun start(code: String, name: String = "") {
        closed = false
        if (asCamera) listen() else discover()
    }

    private fun listen() {
        val bt = adapter ?: return
        Thread {
            try {
                val serverSocket = bt.listenUsingInsecureRfcommWithServiceRecord("Sentry", uuid)
                server = serverSocket
                listener.onState("Camera live. Waiting for monitor...")
                while (!closed) {
                    val accepted = serverSocket.accept()
                    attach(accepted)
                }
            } catch (e: IOException) {
                if (!closed) listener.onState("Camera stopped listening.")
            }
        }.start()
    }

    private fun discover() {
        val bt = adapter ?: return
        bt.bondedDevices?.forEach { listener.onFound(it.address, it.name ?: it.address) }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
            receiverRegistered = true
        }
        bt.cancelDiscovery()
        if (bt.startDiscovery()) listener.onState("Scanning for cameras...") else listener.onState("Scan failed. Turn on Location and try again.")
    }

    fun rescan() {
        val bt = adapter ?: return
        bt.bondedDevices?.forEach { listener.onFound(it.address, it.name ?: it.address) }
        bt.cancelDiscovery()
        bt.startDiscovery()
    }

    fun connect(address: String) {
        val bt = adapter ?: return
        bt.cancelDiscovery()
        closed = false
        Thread {
            try {
                val device = bt.getRemoteDevice(address)
                listener.onState("Connecting to ${device.name ?: address}...")
                val pending = device.createInsecureRfcommSocketToServiceRecord(uuid)
                pending.connect()
                attach(pending)
            } catch (e: IOException) {
                listener.onState("Could not connect. Press GO LIVE on the camera first.")
            }
        }.start()
    }

    private fun attach(connected: BluetoothSocket) {
        socket = connected
        output = connected.outputStream
        val secure = SecureChannel()
        channel = secure
        answered.fill(true)
        rtt = 0
        pendingKey = null
        listener.onConnected()
        writeFrame(byteArrayOf(KEY) + secure.publicKey)
        val input = connected.inputStream
        val header = ByteArray(4)
        try {
            while (!closed) {
                if (!readFully(input, header, 4)) break
                val length = (header[0].toInt() and 0xFF shl 24) or
                    (header[1].toInt() and 0xFF shl 16) or
                    (header[2].toInt() and 0xFF shl 8) or
                    (header[3].toInt() and 0xFF)
                if (length <= 0 || length > 1_000_000) break
                val body = ByteArray(length)
                if (!readFully(input, body, length)) break
                receive(body)
            }
        } catch (e: IOException) {
            closed = closed
        }
        close(connected)
        channel = null
        output = null
        handler.removeCallbacks(pinger)
        if (!closed) listener.onDisconnected()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val count = try {
                input.read(buffer, read, length - read)
            } catch (e: IOException) {
                return false
            }
            if (count < 0) return false
            read += count
        }
        return true
    }

    fun send(type: Byte, body: ByteArray = ByteArray(0)): Boolean {
        val sealed = channel?.seal(byteArrayOf(type) + body) ?: return false
        return writeFrame(byteArrayOf(SEALED) + sealed)
    }

    fun sendMedia(type: Byte, body: ByteArray): Boolean = send(type, body)

    private fun internal(type: Byte, body: ByteArray) {
        val sealed = channel?.seal(byteArrayOf(type) + body) ?: return
        writeFrame(byteArrayOf(SEALED) + sealed)
    }

    private fun writeFrame(frame: ByteArray): Boolean {
        val stream = output ?: return false
        return try {
            synchronized(writeLock) {
                stream.write(
                    byteArrayOf(
                        (frame.size ushr 24).toByte(),
                        (frame.size ushr 16).toByte(),
                        (frame.size ushr 8).toByte(),
                        frame.size.toByte()
                    )
                )
                stream.write(frame)
                stream.flush()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun receive(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val secure = channel
        if (secure == null) {
            if (bytes[0] == KEY) pendingKey = bytes
            return
        }
        when (bytes[0]) {
            KEY -> if (secure.agree(bytes.copyOfRange(1, bytes.size))) {
                listener.onSecure(secure.code)
                handler.removeCallbacks(pinger)
                handler.post(pinger)
            }
            SEALED -> secure.open(bytes, 1)?.let { if (it.isNotEmpty()) handle(it) }
        }
    }

    private fun handle(bytes: ByteArray) {
        when (bytes[0]) {
            PING -> internal(PONG, bytes.copyOfRange(1, bytes.size))
            PONG -> if (bytes.size >= 13) {
                val buffer = ByteBuffer.wrap(bytes, 1, 12)
                val seq = buffer.int
                val sent = buffer.long
                if (pingSeq - seq < answered.size) {
                    answered[seq % answered.size] = true
                    rtt = (SystemClock.elapsedRealtime() - sent).toInt()
                }
            }
            else -> listener.onMessage(bytes[0], bytes, 1, bytes.size - 1)
        }
    }

    fun stop() {
        adapter?.cancelDiscovery()
    }

    fun accept(id: String) = Unit

    fun reject(id: String) = Unit

    fun disconnect() {
        closed = true
        handler.removeCallbacks(pinger)
        close(socket)
        socket = null
        output = null
        channel = null
    }

    fun shutdown() {
        closed = true
        handler.removeCallbacks(pinger)
        adapter?.cancelDiscovery()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
                receiverRegistered = false
            }
            receiverRegistered = false
        }
        try {
            server?.close()
        } catch (e: IOException) {
            server = null
        }
        close(socket)
        server = null
        socket = null
        output = null
        channel = null
    }

    private fun close(target: BluetoothSocket?) {
        try {
            target?.close()
        } catch (e: IOException) {
            closed = closed
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private companion object {
        const val KEY: Byte = 120
        const val SEALED: Byte = 121
        const val PING: Byte = 122
        const val PONG: Byte = 123
    }
}
