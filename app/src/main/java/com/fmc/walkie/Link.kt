package com.fmc.walkie

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.ByteBuffer

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

class Link(context: Context, private val asCamera: Boolean, private val listener: Listener) {

    interface Listener {
        fun onState(text: String)
        fun onFound(id: String)
        fun onConnectRequest(id: String)
        fun onConnected()
        fun onSecure(code: String)
        fun onDisconnected()
        fun onMessage(type: Byte, data: ByteArray, offset: Int, length: Int)
        fun onQuality(rttMs: Int, percent: Int)
    }

    private val client = Nearby.getConnectionsClient(context)
    private val serviceId = "com.fmc.walkie.secure"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val handler = Handler(Looper.getMainLooper())
    private val answered = BooleanArray(8)
    private val inFlight = HashSet<Long>()
    private var code = ""
    private var pingSeq = 0
    private var rtt = 0

    @Volatile
    private var channel: SecureChannel? = null

    @Volatile
    private var peer: String? = null
    private var pendingKey: ByteArray? = null

    val isConnected: Boolean
        get() = peer != null

    private val pinger = object : Runnable {
        override fun run() {
            if (peer == null) return
            listener.onQuality(rtt, answered.count { it } * 100 / answered.size)
            pingSeq += 1
            answered[pingSeq % answered.size] = false
            internal(PING, ByteBuffer.allocate(12).putInt(pingSeq).putLong(SystemClock.elapsedRealtime()).array())
            handler.postDelayed(this, 1000)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.isNotEmpty()) receive(bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) inFlight.remove(update.payloadId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (info.isIncomingConnection) listener.onConnectRequest(endpointId) else accept(endpointId)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                listener.onState("Connection failed. Check the code and try again.")
                return
            }
            stop()
            val secure = SecureChannel()
            channel = secure
            peer = endpointId
            answered.fill(true)
            inFlight.clear()
            rtt = 0
            listener.onConnected()
            client.sendPayload(endpointId, Payload.fromBytes(byteArrayOf(KEY) + secure.publicKey))
            pendingKey?.let {
                pendingKey = null
                receive(it)
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != peer) return
            reset()
            listener.onDisconnected()
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == serviceId && info.endpointName == code) {
                listener.onFound(endpointId)
                client.requestConnection(code, endpointId, lifecycle)
                    .addOnFailureListener { listener.onState("Could not reach the camera: ${it.message}") }
            }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    fun start(pairingCode: String) {
        code = pairingCode
        stop()
        client.stopAllEndpoints()
        if (asCamera) {
            client.startAdvertising(code, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
                .addOnFailureListener { listener.onState("Could not go live: ${it.message}") }
        } else {
            client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
                .addOnFailureListener { listener.onState("Could not scan: ${it.message}") }
        }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
    }

    fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloads)
    }

    fun reject(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    fun disconnect() {
        peer?.let { client.disconnectFromEndpoint(it) }
        reset()
    }

    fun shutdown() {
        stop()
        client.stopAllEndpoints()
        reset()
    }

    fun send(type: Byte, body: ByteArray = ByteArray(0)): Boolean {
        val target = peer ?: return false
        val sealed = channel?.seal(byteArrayOf(type) + body) ?: return false
        client.sendPayload(target, Payload.fromBytes(byteArrayOf(SEALED) + sealed))
        return true
    }

    fun sendMedia(type: Byte, body: ByteArray): Boolean {
        if (inFlight.size >= 3) return false
        val target = peer ?: return false
        val sealed = channel?.seal(byteArrayOf(type) + body) ?: return false
        val payload = Payload.fromBytes(byteArrayOf(SEALED) + sealed)
        client.sendPayload(target, payload)
        inFlight.add(payload.id)
        return true
    }

    private fun internal(type: Byte, body: ByteArray) {
        val target = peer ?: return
        val sealed = channel?.seal(byteArrayOf(type) + body) ?: return
        client.sendPayload(target, Payload.fromBytes(byteArrayOf(SEALED) + sealed))
    }

    private fun reset() {
        handler.removeCallbacks(pinger)
        peer = null
        channel = null
        pendingKey = null
        inFlight.clear()
    }

    private fun receive(bytes: ByteArray) {
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

    private companion object {
        const val KEY: Byte = 120
        const val SEALED: Byte = 121
        const val PING: Byte = 122
        const val PONG: Byte = 123
    }
}
