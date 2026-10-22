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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
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
    private lateinit var lockButton: Button
    private lateinit var savedList: ListView
    private lateinit var savedAdapter: ArrayAdapter<String>
    private val savedCodes = ArrayList<String>()
    private val savedLabels = ArrayList<String>()
    private var service: LinkService? = null
    private var unlocked = false
    private var gainStep = 1
    private var detectOn = true
    private var camBlack = false
    private var fullscreen = false
    private var scaleFactor = 1f
    private var videoBaseHeight = 0
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

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
        lockButton = findViewById(R.id.lock)
        savedList = findViewById(R.id.saved)

        savedAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, savedLabels)
        savedList.adapter = savedAdapter
        loadSaved()
        savedList.setOnItemClickListener { _, _, position, _ ->
            codeInput.setText(savedCodes.getOrNull(position) ?: "")
            connect()
        }
        updateLockLabel()
        lockButton.setOnClickListener { toggleLock() }
        findViewById<Button>(R.id.clips).setOnClickListener {
            startActivity(Intent(this, ClipsActivity::class.java))
        }
        findViewById<Button>(R.id.scan).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
            } else {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            }
        }

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
        findViewById<Button>(R.id.fullscreen).setOnClickListener { setFullscreen(!fullscreen) }
        findViewById<Button>(R.id.camscreen).setOnClickListener {
            camBlack = !camBlack
            service?.setCameraScreen(camBlack)
            (it as Button).text = if (camBlack) "CAM SCREEN ON" else "CAM SCREEN OFF"
        }
        findViewById<Button>(R.id.hangup).setOnClickListener { service?.shutdown() }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 5f)
                videoView.scaleX = scaleFactor
                videoView.scaleY = scaleFactor
                return true
            }
        })
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scaleFactor = 1f
                videoView.scaleX = 1f
                videoView.scaleY = 1f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fullscreen) setFullscreen(false)
                return true
            }
        })
        videoView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }
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
        val prefs = getSharedPreferences("mon", Context.MODE_PRIVATE)
        prefs.edit().putString("code", code).apply()
        val codes = LinkedHashSet(prefs.getStringSet("cameras", emptySet()) ?: emptySet())
        codes.add(code)
        prefs.edit().putStringSet("cameras", codes).apply()
        loadSaved()
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
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == CAMERA_REQUEST) {
            if (ok) startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
            else statusView.text = "Camera needed to scan the QR"
            return
        }
        if (ok) connect() else statusView.text = "Allow Mic and Location, then press CONNECT"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCAN_REQUEST && resultCode == RESULT_OK) {
            val code = data?.getStringExtra("code") ?: return
            codeInput.setText(code)
            data.getStringExtra("name")?.let {
                if (it.isNotEmpty()) getSharedPreferences("mon", Context.MODE_PRIVATE).edit().putString("name_$code", it).apply()
            }
            connect()
        }
    }

    private companion object {
        const val CAMERA_REQUEST = 2
        const val SCAN_REQUEST = 3
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
            "LINK $bars ${svc.signalPercent}%  PING ${svc.pingMs}ms\nDISTANCE ${svc.distanceText}\n$secure\nLAST MOTION: ${lastMotion(svc.lastAlertAt)}"
        } else {
            svc.status
        }
    }

    private fun lastMotion(at: Long): String {
        if (at == 0L) return "none yet"
        val mins = (System.currentTimeMillis() - at) / 60000
        val clock = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(at))
        return when {
            mins <= 0L -> "just now ($clock)"
            mins == 1L -> "1 min ago ($clock)"
            mins < 60L -> "$mins min ago ($clock)"
            else -> "at $clock"
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val params = videoView.layoutParams
        if (on) {
            if (videoBaseHeight == 0) videoBaseHeight = videoView.height
            params.height = resources.displayMetrics.heightPixels
            setup.visibility = View.GONE
            controls.visibility = View.GONE
            statusView.visibility = View.GONE
        } else {
            params.height = if (videoBaseHeight > 0) videoBaseHeight else (240 * resources.displayMetrics.density).toInt()
            controls.visibility = View.VISIBLE
            statusView.visibility = View.VISIBLE
        }
        videoView.layoutParams = params
    }

    override fun onRemoteFrame(frame: Bitmap) {
        runOnUiThread {
            videoView.setImageBitmap(frame)
            videoView.visibility = View.VISIBLE
        }
    }

    private fun loadSaved() {
        val prefs = getSharedPreferences("mon", Context.MODE_PRIVATE)
        val codes = (prefs.getStringSet("cameras", emptySet()) ?: emptySet()).sorted()
        savedCodes.clear()
        savedLabels.clear()
        codes.forEach { code ->
            savedCodes.add(code)
            val name = prefs.getString("name_$code", null)
            savedLabels.add(if (name.isNullOrEmpty()) "Camera $code" else "$name  ·  $code")
        }
        savedAdapter.notifyDataSetChanged()
    }

    private fun toggleLock() {
        if (lock.isEnabled) {
            lock.disable()
            updateLockLabel()
        } else {
            lock.enable { updateLockLabel() }
        }
    }

    private fun updateLockLabel() {
        lockButton.text = if (lock.isEnabled) "APP LOCK: ON" else "APP LOCK: OFF"
    }
}
