package com.fmc.walkie

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.roundToInt

@SuppressLint("MissingPermission", "ClickableViewAccessibility", "SetTextI18n")
class MainActivity : Activity(), RadioLink.Listener {

    private class Peer(val id: String, val name: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var link: RadioLink
    private lateinit var callsignInput: EditText
    private lateinit var statusView: TextView
    private lateinit var signalView: TextView
    private lateinit var searchButton: Button
    private lateinit var peerList: ListView
    private lateinit var chatList: ListView
    private lateinit var chatBar: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var talkButton: Button
    private lateinit var voxButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var peerAdapter: ArrayAdapter<String>
    private lateinit var chatAdapter: ArrayAdapter<String>

    private val audio = AudioEngine()
    private val peers = ArrayList<Peer>()
    private val peerLabels = ArrayList<String>()
    private val messages = ArrayList<String>()
    private var bluetooth: BluetoothAdapter? = null
    private var proximity: Proximity? = null
    private var request: AlertDialog? = null
    private var searching = false
    private var peerName = ""
    private var reconnectTo: String? = null
    private var closedByUser = false
    private var distanceText = "--"
    private var signalPercent = 100
    private var pingMs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        prefs = getSharedPreferences("radio", Context.MODE_PRIVATE)
        callsignInput = findViewById(R.id.callsign)
        statusView = findViewById(R.id.status)
        signalView = findViewById(R.id.signal)
        searchButton = findViewById(R.id.search)
        peerList = findViewById(R.id.peers)
        chatList = findViewById(R.id.chat)
        chatBar = findViewById(R.id.chat_bar)
        messageInput = findViewById(R.id.message)
        talkButton = findViewById(R.id.talk)
        voxButton = findViewById(R.id.vox)
        disconnectButton = findViewById(R.id.disconnect)

        peerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, peerLabels)
        peerList.adapter = peerAdapter
        chatAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        chatList.adapter = chatAdapter

        callsignInput.setText(prefs.getString("callsign", null) ?: "UNIT-${(100..999).random()}")
        bluetooth = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        link = RadioLink(this, this)

        searchButton.setOnClickListener {
            if (searching) stopSearch() else if (ready()) startSearch()
        }
        peerList.setOnItemClickListener { _, _, position, _ ->
            val peer = peers.getOrNull(position) ?: return@setOnItemClickListener
            reconnectTo = null
            setStatus("Calling ${peer.name}...")
            link.connect(peer.id)
        }
        talkButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    audio.pushToTalk = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    audio.pushToTalk = false
                    true
                }
                else -> false
            }
        }
        voxButton.setOnClickListener {
            audio.vox = !audio.vox
            voxButton.text = if (audio.vox) "VOX: ON" else "VOX: OFF"
        }
        disconnectButton.setOnClickListener {
            reconnectTo = null
            closedByUser = true
            link.disconnect()
            onDisconnected()
        }
        findViewById<Button>(R.id.send).setOnClickListener { sendMessage() }
        messageInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        showIdle()
        if (bluetooth == null) {
            setStatus("This phone has no Bluetooth")
            searchButton.isEnabled = false
        } else {
            ready()
        }
    }

    private fun callsign(): String {
        val text = callsignInput.text.toString().trim().uppercase().take(16)
        return text.ifEmpty { "UNIT-${(100..999).random()}" }
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return list
    }

    private fun ready(): Boolean {
        val adapter = bluetooth ?: return false
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return false
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        if (Build.VERSION.SDK_INT in 28..32) {
            val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!location.isLocationEnabled) {
                setStatus("Turn on Location, then press SEARCH")
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            setStatus("Allow all permissions, then press SEARCH")
        } else {
            setStatus("Ready. Press SEARCH on both phones.")
        }
    }

    private fun startSearch() {
        val name = callsign()
        callsignInput.setText(name)
        prefs.edit().putString("callsign", name).apply()
        peers.clear()
        peerLabels.clear()
        peerAdapter.notifyDataSetChanged()
        link.search(name)
        searching = true
        searchButton.text = "STOP SEARCH"
        setStatus("On air as $name. Looking for radios... Keep Bluetooth and Wi-Fi ON, no internet needed.")
    }

    private fun stopSearch() {
        link.stopSearch()
        searching = false
        searchButton.text = "SEARCH"
        setStatus("Search stopped")
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() || !link.isConnected) return
        link.sendText(text)
        addMessage("ME", text)
        messageInput.setText("")
    }

    private fun addMessage(from: String, text: String) {
        messages.add("$from: $text")
        chatAdapter.notifyDataSetChanged()
        chatList.setSelection(messages.size - 1)
    }

    private fun showIdle() {
        callsignInput.isEnabled = true
        searchButton.visibility = View.VISIBLE
        peerList.visibility = View.VISIBLE
        signalView.visibility = View.GONE
        chatList.visibility = View.GONE
        chatBar.visibility = View.GONE
        talkButton.isEnabled = false
        talkButton.text = "HOLD TO TALK"
        voxButton.isEnabled = false
        disconnectButton.isEnabled = false
    }

    private fun showConnected() {
        callsignInput.isEnabled = false
        searchButton.visibility = View.GONE
        peerList.visibility = View.GONE
        signalView.visibility = View.VISIBLE
        chatList.visibility = View.VISIBLE
        chatBar.visibility = View.VISIBLE
        talkButton.isEnabled = true
        voxButton.isEnabled = true
        disconnectButton.isEnabled = true
        renderSignal()
    }

    private fun renderSignal() {
        val bars = when {
            signalPercent >= 90 -> 4
            signalPercent >= 70 -> 3
            signalPercent >= 40 -> 2
            signalPercent > 0 -> 1
            else -> 0
        }
        val word = when (bars) {
            4 -> "STRONG"
            3 -> "GOOD"
            2 -> "WEAK"
            1 -> "POOR"
            else -> "LOST"
        }
        signalView.text = "LINK  ${"■".repeat(bars)}${"□".repeat(4 - bars)}  $word $signalPercent%   PING $pingMs ms\n" +
            "DISTANCE  $distanceText"
    }

    private fun boostVolume() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) < max / 2) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, max * 3 / 4, 0)
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    override fun onState(text: String) {
        setStatus(text)
    }

    override fun onPeerFound(id: String, name: String) {
        peers.removeAll { it.id == id }
        peers.add(Peer(id, name))
        peerLabels.clear()
        peers.forEach { peerLabels.add("${it.name}\nTap to connect") }
        peerAdapter.notifyDataSetChanged()
        setStatus("Found ${peers.size} radio(s). Tap one to connect.")
        if (name == reconnectTo && callsign() < name) link.connect(id)
    }

    override fun onPeerLost(id: String) {
        peers.removeAll { it.id == id }
        peerLabels.clear()
        peers.forEach { peerLabels.add("${it.name}\nTap to connect") }
        peerAdapter.notifyDataSetChanged()
    }

    override fun onConnectionRequest(id: String, name: String) {
        if (name == reconnectTo) {
            link.accept(id)
            return
        }
        request?.dismiss()
        request = AlertDialog.Builder(this)
            .setTitle("Incoming radio")
            .setMessage("$name wants to connect")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ -> link.accept(id) }
            .setNegativeButton("Reject") { _, _ -> link.reject(id) }
            .show()
    }

    override fun onConnected(name: String) {
        request?.dismiss()
        searching = false
        searchButton.text = "SEARCH"
        peerName = name
        reconnectTo = null
        signalPercent = 100
        pingMs = 0
        distanceText = "measuring..."
        boostVolume()
        val adapter = bluetooth
        if (adapter != null) {
            proximity = Proximity(adapter) { meters ->
                distanceText = when {
                    meters == null -> "far / out of Bluetooth range"
                    meters < 1.0 -> "under 1 m (approx)"
                    else -> "~${meters.roundToInt()} m (approx)"
                }
                renderSignal()
            }.also {
                it.start()
                link.sendHello(it.token)
            }
        }
        audio.start(
            onFrame = { link.sendAudio(it) },
            onTalk = { talking ->
                link.sendTalk(talking)
                runOnUiThread { talkButton.text = if (talking) "TRANSMITTING..." else "HOLD TO TALK" }
            }
        )
        setStatus("Connected to $peerName. Channel open.")
        showConnected()
    }

    override fun onDisconnected() {
        val wasConnected = peerName.isNotEmpty()
        audio.stop()
        proximity?.stop()
        proximity = null
        showIdle()
        if (wasConnected && !closedByUser) reconnectTo = peerName
        closedByUser = false
        peerName = ""
        if (reconnectTo != null) {
            startSearch()
            setStatus("Link lost with $reconnectTo. Reconnecting...")
        } else {
            setStatus("Disconnected. Press SEARCH to find radios.")
        }
    }

    override fun onAudio(data: ByteArray, offset: Int, length: Int) {
        audio.receive(data, offset, length)
    }

    override fun onText(text: String) {
        addMessage(peerName, text)
    }

    override fun onTalkState(talking: Boolean) {
        audio.remoteTalk(talking)
        setStatus(if (talking) "$peerName is talking..." else "Connected to $peerName. Channel open.")
    }

    override fun onHello(token: Int, name: String) {
        proximity?.peerToken = token
        if (name.isNotEmpty()) peerName = name
    }

    override fun onQuality(rttMs: Int, percent: Int) {
        signalPercent = percent
        pingMs = rttMs
        renderSignal()
    }

    override fun onDestroy() {
        super.onDestroy()
        request?.dismiss()
        audio.stop()
        proximity?.stop()
        link.shutdown()
    }
}
