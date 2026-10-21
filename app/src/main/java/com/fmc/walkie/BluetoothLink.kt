package com.fmc.walkie

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothLink(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {

    interface Listener {
        fun onState(text: String)
        fun onConnected(name: String)
        fun onDisconnected()
        fun onAudio(data: ByteArray, length: Int)
    }

    private val serviceId: UUID = UUID.fromString("5f3c1a0e-7b2d-4c58-9a41-0d6e8b2f7c13")

    @Volatile
    private var closed = false
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    fun listen() {
        stop()
        closed = false
        Thread {
            try {
                listener.onState("Waiting for the other phone...")
                val serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("BtWalkie", serviceId)
                server = serverSocket
                val accepted = serverSocket.accept()
                serverSocket.close()
                server = null
                attach(accepted)
            } catch (e: IOException) {
                if (!closed) listener.onState("Hosting stopped")
            }
        }.start()
    }

    fun connect(device: BluetoothDevice) {
        stop()
        closed = false
        Thread {
            try {
                listener.onState("Connecting to ${device.name ?: device.address}...")
                val pending = device.createInsecureRfcommSocketToServiceRecord(serviceId)
                socket = pending
                pending.connect()
                attach(pending)
            } catch (e: IOException) {
                if (!closed) listener.onState("Could not connect. Press Host on the other phone first.")
            }
        }.start()
    }

    private fun attach(connected: BluetoothSocket) {
        socket = connected
        output = connected.outputStream
        listener.onConnected(connected.remoteDevice.name ?: connected.remoteDevice.address)
        val input = connected.inputStream
        val buffer = ByteArray(2048)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                listener.onAudio(buffer, count)
            }
        } catch (e: IOException) {
            closed = closed
        }
        try {
            connected.close()
        } catch (e: IOException) {
            closed = closed
        }
        output = null
        if (!closed) listener.onDisconnected()
    }

    fun send(data: ByteArray, length: Int): Boolean {
        val stream = output ?: return false
        return try {
            stream.write(data, 0, length)
            stream.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    fun stop() {
        closed = true
        try {
            server?.close()
        } catch (e: IOException) {
            closed = true
        }
        try {
            socket?.close()
        } catch (e: IOException) {
            closed = true
        }
        server = null
        socket = null
        output = null
    }
}
