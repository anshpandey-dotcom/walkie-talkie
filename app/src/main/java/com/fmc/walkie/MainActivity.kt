package com.fmc.walkie

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

@SuppressLint("MissingPermission", "ClickableViewAccessibility", "SetTextI18n")
class MainActivity : Activity(), RadioService.Ui, ServiceConnection {

    private lateinit var prefs: SharedPreferences
    private lateinit var callsignInput: EditText
    private lateinit var statusView: TextView
    private lateinit var signalView: TextView
    private lateinit var searchButton: Button
    private lateinit var peerList: ListView
    private lateinit var videoPanel: View
    private lateinit var remoteView: ImageView
    private lateinit var localView: TextureView
    private lateinit var chatList: ListView
    private lateinit var chatBar: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var talkButton: Button
    private lateinit var voxButton: Button
    private lateinit var videoButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var peerAdapter: ArrayAdapter<String>
    private lateinit var chatAdapter: ArrayAdapter<String>

    private val handler = Handler(Looper.getMainLooper())
    private val peerLabels = ArrayList<String>()
    private val messages = ArrayList<String>()
    private var bluetooth: BluetoothAdapter? = null
    private var radio: RadioService? = null
    private var request: AlertDialog? = null
    private var video: VideoSender? = null

    private val hideRemote = Runnable {
        remoteView.setImageDrawable(null)
        remoteView.visibility = View.GONE
        if (video == null) videoPanel.visibility = View.GONE
    }

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
        videoPanel = findViewById(R.id.video_panel)
        remoteView = findViewById(R.id.remote_video)
        localView = findViewById(R.id.local_video)
        chatList = findViewById(R.id.chat)
        chatBar = findViewById(R.id.chat_bar)
        messageInput = findViewById(R.id.message)
        talkButton = findViewById(R.id.talk)
        voxButton = findViewById(R.id.vox)
        videoButton = findViewById(R.id.video)
        disconnectButton = findViewById(R.id.disconnect)

        peerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, peerLabels)
        peerList.adapter = peerAdapter
        chatAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        chatList.adapter = chatAdapter

        callsignInput.setText(prefs.getString("callsign", null) ?: "UNIT-${(100..999).random()}")
        bluetooth = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        searchButton.setOnClickListener {
            val service = radio ?: return@setOnClickListener
            if (service.searching) service.stopSearch() else if (ready()) startSearch(service)
        }
        peerList.setOnItemClickListener { _, _, position, _ ->
            val service = radio ?: return@setOnItemClickListener
            service.peers.getOrNull(position)?.let { service.connect(it) }
        }
        talkButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    radio?.setTouchTalk(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    radio?.setTouchTalk(false)
                    true
                }
                else -> false
            }
        }
        voxButton.setOnClickListener { radio?.toggleVox() }
        videoButton.setOnClickListener {
            when {
                video != null -> stopVideo()
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> startVideo()
                else -> requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            }
        }
        disconnectButton.setOnClickListener { radio?.hangUp() }
        findViewById<Button>(R.id.send).setOnClickListener { sendMessage() }
        messageInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        if (bluetooth == null) {
            statusView.text = "This phone has no Bluetooth"
            searchButton.isEnabled = false
        } else {
            ready()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RadioService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        stopVideo()
        handler.removeCallbacks(hideRemote)
        request?.dismiss()
        radio?.ui = null
        radio = null
        unbindService(this)
        super.onStop()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val service = (binder as RadioService.LocalBinder).service
        radio = service
        service.ui = this
        refresh()
        service.pendingRequest?.let { onIncoming(it.id, it.name) }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        radio = null
    }

    private fun callsign(): String {
        val text = callsignInput.text.toString().trim().uppercase().take(16)
        return text.ifEmpty { "UNIT-${(100..999).random()}" }
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return list
    }

    private fun optionalPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun granted(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun ready(): Boolean {
        val adapter = bluetooth ?: return false
        if (requiredPermissions().any { !granted(it) }) {
            val ask = (requiredPermissions() + optionalPermissions()).filter { !granted(it) }
            requestPermissions(ask.toTypedArray(), PERMISSION_REQUEST)
            return false
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!location.isLocationEnabled) {
                statusView.text = "Turn on Location, then press SEARCH"
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
        if (requestCode == CAMERA_REQUEST) {
            if (granted(Manifest.permission.CAMERA)) startVideo()
            return
        }
        statusView.text = if (requiredPermissions().all { granted(it) }) {
            "Ready. Press SEARCH on both phones."
        } else {
            "Allow all permissions (Location too), then press SEARCH"
        }
    }

    private fun startSearch(service: RadioService) {
        val name = callsign()
        callsignInput.setText(name)
        prefs.edit().putString("callsign", name).apply()
        val intent = Intent(this, RadioService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        service.search(name)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        radio?.sendText(text)
        messageInput.setText("")
    }

    private fun startVideo() {
        if (radio?.connected != true || video != null) return
        videoPanel.visibility = View.VISIBLE
        localView.visibility = View.VISIBLE
        video = VideoSender(localView) { jpeg, rotation -> radio?.sendVideo(jpeg, rotation) ?: false }.also { it.start() }
        videoButton.text = "VIDEO: ON"
    }

    private fun stopVideo() {
        video?.stop()
        video = null
        localView.visibility = View.GONE
        if (remoteView.visibility != View.VISIBLE) videoPanel.visibility = View.GONE
        videoButton.text = "VIDEO: OFF"
    }

    override fun refresh() {
        val service = radio ?: return
        statusView.text = service.status
        searchButton.text = if (service.searching) "STOP SEARCH" else "SEARCH"
        peerLabels.clear()
        service.peers.forEach { peerLabels.add("${it.name}\nTap to connect") }
        peerAdapter.notifyDataSetChanged()
        if (messages != service.messages) {
            messages.clear()
            messages.addAll(service.messages)
            chatAdapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) chatList.setSelection(messages.size - 1)
        }
        val connected = service.connected
        callsignInput.isEnabled = !connected && !service.searching
        searchButton.visibility = if (connected) View.GONE else View.VISIBLE
        peerList.visibility = if (connected) View.GONE else View.VISIBLE
        signalView.visibility = if (connected) View.VISIBLE else View.GONE
        chatList.visibility = if (connected) View.VISIBLE else View.GONE
        chatBar.visibility = if (connected) View.VISIBLE else View.GONE
        talkButton.isEnabled = connected
        voxButton.isEnabled = connected
        videoButton.isEnabled = connected
        disconnectButton.isEnabled = connected
        talkButton.text = if (service.transmitting) "TRANSMITTING..." else "HOLD TO TALK"
        voxButton.text = if (service.vox) "VOX: ON" else "VOX: OFF"
        if (connected) {
            signalView.text = signalText(service)
        } else {
            stopVideo()
            handler.removeCallbacks(hideRemote)
            hideRemote.run()
        }
    }

    private fun signalText(service: RadioService): String {
        val percent = service.signalPercent
        val bars = when {
            percent >= 90 -> 4
            percent >= 70 -> 3
            percent >= 40 -> 2
            percent > 0 -> 1
            else -> 0
        }
        val word = when (bars) {
            4 -> "STRONG"
            3 -> "GOOD"
            2 -> "WEAK"
            1 -> "POOR"
            else -> "LOST"
        }
        val secure = if (service.secureCode.isEmpty()) "SECURING..." else "E2EE ON  CODE ${service.secureCode}"
        return "LINK  ${"■".repeat(bars)}${"□".repeat(4 - bars)}  $word $percent%   PING ${service.pingMs} ms\n" +
            "DISTANCE  ${service.distanceText}\n" +
            secure
    }

    override fun onIncoming(id: String, name: String) {
        request?.dismiss()
        request = AlertDialog.Builder(this)
            .setTitle("Incoming radio")
            .setMessage("$name wants to connect")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ -> radio?.accept(id) }
            .setNegativeButton("Reject") { _, _ -> radio?.reject(id) }
            .show()
    }

    override fun onVideo(frame: Bitmap) {
        remoteView.setImageBitmap(frame)
        remoteView.visibility = View.VISIBLE
        videoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideRemote)
        handler.postDelayed(hideRemote, 2500)
    }

    private companion object {
        const val PERMISSION_REQUEST = 1
        const val CAMERA_REQUEST = 2
    }
}
