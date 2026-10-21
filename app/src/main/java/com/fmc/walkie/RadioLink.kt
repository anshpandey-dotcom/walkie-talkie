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

class RadioLink(context: Context, private val listener: Listener) {

    interface Listener {
        fun onState(text: String)
        fun onPeerFound(id: String, name: String)
        fun onPeerLost(id: String)
        fun onConnectionRequest(id: String, name: String)
        fun onConnected(name: String)
        fun onSecure(code: String)
        fun onDisconnected()
        fun onAudio(data: ByteArray, offset: Int, length: Int)
        fun onVideo(data: ByteArray, offset: Int, length: Int, rotation: Int)
        fun onText(text: String)
        fun onTalkState(talking: Boolean)
        fun onHello(token: Int, name: String)
        fun onQuality(rttMs: Int, percent: Int)
    }

    private val client = Nearby.getConnectionsClient(context)
    private val serviceId = "com.fmc.walkie.radio"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val handler = Handler(Looper.getMainLooper())
    private val names = HashMap<String, String>()
    private val answered = BooleanArray(10)
    private val videoInFlight = HashSet<Long>()
    private var callsign = ""
    private var pendingKey: ByteArray? = null
    private var pingSeq = 0
    private var rtt = 0

    @Volatile
    private var channel: SecureChannel? = null

    @Volatile
    private var peer: String? = null

    private val pinger = object : Runnable {
        override fun run() {
            if (peer == null) return
            listener.onQuality(rtt, answered.count { it } * 100 / answered.size)
            pingSeq += 1
            answered[pingSeq % answered.size] = false
            send(PING, ByteBuffer.allocate(12).putInt(pingSeq).putLong(SystemClock.elapsedRealtime()).array())
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
            if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) videoInFlight.remove(update.payloadId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            names[endpointId] = info.endpointName
            if (info.isIncomingConnection) {
                listener.onConnectionRequest(endpointId, info.endpointName)
            } else {
                accept(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                listener.onState("Link refused or failed. Try again.")
                return
            }
            stopSearch()
            val secure = SecureChannel()
            channel = secure
            peer = endpointId
            answered.fill(true)
            videoInFlight.clear()
            rtt = 0
            listener.onConnected(names[endpointId] ?: "UNKNOWN")
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
            if (info.serviceId == serviceId) listener.onPeerFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            listener.onPeerLost(endpointId)
        }
    }

    fun search(name: String) {
        callsign = name
        stopSearch()
        client.startAdvertising(name, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { listener.onState("Could not go on air: ${it.message}") }
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { listener.onState("Could not scan: ${it.message}") }
    }

    fun stopSearch() {
        client.stopAdvertising()
        client.stopDiscovery()
    }

    fun connect(endpointId: String) {
        client.requestConnection(callsign, endpointId, lifecycle)
            .addOnFailureListener { listener.onState("Could not reach that radio: ${it.message}") }
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
        stopSearch()
        client.stopAllEndpoints()
        reset()
    }

    fun sendAudio(frame: ByteArray) {
        send(AUDIO, frame)
    }

    fun sendText(text: String) {
        send(TEXT, text.toByteArray(Charsets.UTF_8))
    }

    fun sendTalk(talking: Boolean) {
        send(if (talking) TALK_ON else TALK_OFF, ByteArray(0))
    }

    fun sendHello(token: Int) {
        send(HELLO, ByteBuffer.allocate(4).putInt(token).array() + callsign.toByteArray(Charsets.UTF_8))
    }

    fun sendVideo(jpeg: ByteArray, rotation: Int): Boolean {
        if (videoInFlight.size >= 2) return false
        val id = send(VIDEO, byteArrayOf((rotation / 90).toByte()) + jpeg) ?: return false
        videoInFlight.add(id)
        return true
    }

    private fun reset() {
        handler.removeCallbacks(pinger)
        peer = null
        channel = null
        videoInFlight.clear()
    }

    private fun send(type: Byte, body: ByteArray): Long? {
        val target = peer ?: return null
        val sealed = channel?.seal(byteArrayOf(type) + body) ?: return null
        val payload = Payload.fromBytes(byteArrayOf(SEALED) + sealed)
        client.sendPayload(target, payload)
        return payload.id
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
        val size = bytes.size - 1
        when (bytes[0]) {
            AUDIO -> listener.onAudio(bytes, 1, size)
            VIDEO -> if (size > 1) listener.onVideo(bytes, 2, size - 1, bytes[1] * 90)
            TEXT -> listener.onText(String(bytes, 1, size, Charsets.UTF_8))
            PING -> send(PONG, bytes.copyOfRange(1, bytes.size))
            PONG -> if (size >= 12) {
                val buffer = ByteBuffer.wrap(bytes, 1, 12)
                val seq = buffer.int
                val sent = buffer.long
                if (pingSeq - seq < answered.size) {
                    answered[seq % answered.size] = true
                    rtt = (SystemClock.elapsedRealtime() - sent).toInt()
                }
            }
            TALK_ON -> listener.onTalkState(true)
            TALK_OFF -> listener.onTalkState(false)
            HELLO -> if (size >= 4) {
                val token = ByteBuffer.wrap(bytes, 1, 4).int
                listener.onHello(token, String(bytes, 5, size - 4, Charsets.UTF_8))
            }
        }
    }

    private companion object {
        const val AUDIO: Byte = 1
        const val TEXT: Byte = 2
        const val PING: Byte = 3
        const val PONG: Byte = 4
        const val TALK_ON: Byte = 5
        const val TALK_OFF: Byte = 6
        const val HELLO: Byte = 7
        const val KEY: Byte = 8
        const val SEALED: Byte = 9
        const val VIDEO: Byte = 10
    }
}
