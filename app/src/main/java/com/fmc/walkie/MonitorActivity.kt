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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("MissingPermission", "SetTextI18n", "ClickableViewAccessibility")
class MonitorActivity : Activity(), LinkService.Ui, ServiceConnection {

    private lateinit var lock: Lock
    private lateinit var codeInput: EditText
    private lateinit var statusView: TextView
    private lateinit var videoView: ImageView
    private lateinit var setup: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var liveButton: Button
    private lateinit var listenButton: Button
    private lateinit var muteButton: Button
    private lateinit var gainButton: Button
    private lateinit var detectButton: Button
    private var service: LinkService? = null
    private var unlocked = false
    private var gainStep = 1
    private var detectOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        lock = Lock(this)

        codeInput = findViewById(R.id.code)
        statusView = findViewById(R.id.status)
        videoView = findViewById(R.id.video)
        setup = findViewById(R.id.setup)
        controls = findViewById(R.id.controls)
        liveButton = findViewById(R.id.live)
        listenButton = findViewById(R.id.listen)
        muteButton = findViewById(R.id.mute)
        gainButton = findViewById(R.id.gain)
        detectButton = findViewById(R.id.detect)

        codeInput.setText(getSharedPreferences("mon", Context.MODE_PRIVATE).getString("code", "") ?: "")
        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        liveButton.setOnClickListener { service?.let { it.setStreaming(!it.streaming) } }
        listenButton.setOnClickListener { service?.let { it.setListening(!it.listening) } }
        muteButton.setOnClickListener {
            service?.toggleMute()
            muteButton.text = if (service?.muted == true) "UNMUTE" else "MUTE"
        }
        gainButton.setOnClickListener {
            gainStep = when (gainStep) {
                1 -> 2
                2 -> 4
                else -> 1
            }
            service?.setAudioGain(gainStep.toFloat())
            gainButton.text = "VOL x$gainStep"
        }
        findViewById<Button>(R.id.flash).setOnClickListener { command(Cmd.FLASH, toggleFlash()) }
        findViewById<Button>(R.id.switchcam).setOnClickListener { command(Cmd.SWITCH, 0) }
        findViewById<Button>(R.id.record).setOnClickListener {
            service?.startRecording()
            statusView.text = "Recording this clip..."
        }
        detectButton.setOnClickListener {
            detectOn = !detectOn
            command(Cmd.DETECT, if (detectOn) 1 else 0)
            detectButton.text = if (detectOn) "DETECT: ON" else "DETECT: OFF"
        }
        findViewById<Button>(R.id.sos).setOnClickListener { command(Cmd.SOS, 1) }
        findViewById<Button>(R.id.speak).setOnClickListener { speakDialog() }
        findViewById<Button>(R.id.hangup).setOnClickListener { service?.shutdown() }
        findViewById<Button>(R.id.talk).setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    service?.talk(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    service?.talk(false)
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, LinkService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked) lock.gate { unlocked = true }
    }

    override fun onStop() {
        service?.ui = null
        service = null
        try {
            unbindService(this)
        } catch (e: IllegalArgumentException) {
            unlocked = unlocked
        }
        super.onStop()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val svc = (binder as LinkService.LocalBinder).service
        service = svc
        svc.ui = this
        refresh()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    private var flashState = false

    private fun toggleFlash(): Int {
        flashState = !flashState
        return if (flashState) 1 else 0
    }

    private fun command(cmd: Byte, arg: Int) {
        service?.command(cmd, arg)
    }

    private fun speakDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Message to play loudly"
        }
        AlertDialog.Builder(this)
            .setTitle("Speak on camera")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) service?.command(Cmd.SPEAK, text = text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect() {
        val code = codeInput.text.toString()
        if (code.length != 6) {
            statusView.text = "Enter the 6 digit code from the camera"
            return
        }
        if (!ready()) return
        getSharedPreferences("mon", Context.MODE_PRIVATE).edit().putString("code", code).apply()
        val intent = Intent(this, LinkService::class.java)
            .putExtra(LinkService.EXTRA_CAMERA, false)
            .putExtra(LinkService.EXTRA_CODE, code)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        service?.begin(false, code)
        statusView.text = "Searching for camera..."
    }

    private fun permissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun ready(): Boolean {
        val missing = permissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return false
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            statusView.text = "No Bluetooth on this phone"
            return false
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!location.isLocationEnabled) {
                statusView.text = "Turn on Location, then press CONNECT"
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) connect()
        else statusView.text = "Allow Mic and Location, then press CONNECT"
    }

    override fun refresh() {
        val svc = service ?: return
        val connected = svc.connected
        setup.visibility = if (connected) View.GONE else View.VISIBLE
        controls.visibility = if (connected) View.VISIBLE else View.GONE
        videoView.visibility = if (connected) View.VISIBLE else View.GONE
        liveButton.text = if (svc.streaming) "LIVE: ON" else "LIVE: OFF"
        listenButton.text = if (svc.listening) "LISTEN: ON" else "LISTEN: OFF"
        val bars = when {
            svc.signalPercent >= 90 -> "||||"
            svc.signalPercent >= 70 -> "|||."
            svc.signalPercent >= 40 -> "||.."
            svc.signalPercent > 0 -> "|..."
            else -> "...."
        }
        val secure = if (svc.secureCode.isEmpty()) "securing..." else "E2EE ${svc.secureCode}"
        statusView.text = if (connected) {
            "LINK $bars ${svc.signalPercent}%  PING ${svc.pingMs}ms\nDISTANCE ${svc.distanceText}\n$secure" +
                if (svc.lastAlert.isNotEmpty()) "\nLAST ALERT: ${svc.lastAlert}" else ""
        } else {
            svc.status
        }
    }

    override fun onRemoteFrame(frame: Bitmap) {
        runOnUiThread {
            videoView.setImageBitmap(frame)
            videoView.visibility = View.VISIBLE
        }
    }
}
