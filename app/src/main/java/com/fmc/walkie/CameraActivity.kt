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
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

@SuppressLint("MissingPermission", "SetTextI18n")
class CameraActivity : Activity(), LinkService.Ui, ServiceConnection {

    private lateinit var lock: Lock
    private lateinit var codeInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var qrImage: android.widget.ImageView
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var detectButton: Button
    private lateinit var blackButton: Button
    private lateinit var lockButton: Button
    private lateinit var blackout: View
    private var service: LinkService? = null
    private var request: AlertDialog? = null
    private var unlocked = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        lock = Lock(this)
        codeInput = findViewById(R.id.code)
        nameInput = findViewById(R.id.camname)
        qrImage = findViewById(R.id.qr)
        statusView = findViewById(R.id.status)
        toggleButton = findViewById(R.id.toggle)
        detectButton = findViewById(R.id.detect)
        blackButton = findViewById(R.id.black)
        lockButton = findViewById(R.id.lock)
        blackout = findViewById(R.id.blackout)
        updateLockLabel()
        lockButton.setOnClickListener { toggleLock() }

        val prefs = getSharedPreferences("cam", Context.MODE_PRIVATE)
        val savedCode = prefs.getString("code", null) ?: (100000..999999).random().toString()
        codeInput.setText(savedCode)
        nameInput.setText(prefs.getString("camname", "") ?: "")
        renderQr()
        toggleButton.setOnClickListener { if (running) stop() else start() }
        detectButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            svc.setDetecting(!svc.detecting)
        }
        blackButton.setOnClickListener { setBlack(true) }
        blackout.setOnLongClickListener {
            setBlack(false)
            true
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
        request?.dismiss()
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
        running = svc.connected || svc.code.isNotEmpty() && svc.status != "Idle"
        refresh()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    private fun start() {
        val code = codeInput.text.toString()
        if (code.length != 6) {
            statusView.text = "Enter a 6 digit code"
            return
        }
        if (!ready()) return
        val name = nameInput.text.toString().trim().take(16)
        getSharedPreferences("cam", Context.MODE_PRIVATE).edit()
            .putString("code", code).putString("camname", name).apply()
        renderQr()
        val intent = Intent(this, LinkService::class.java)
            .putExtra(LinkService.EXTRA_CAMERA, true)
            .putExtra(LinkService.EXTRA_CODE, code)
            .putExtra(LinkService.EXTRA_NAME, name)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        service?.begin(true, code, name)
        running = true
        refresh()
    }

    private fun stop() {
        service?.shutdown()
        running = false
        refresh()
    }

    private fun setBlack(black: Boolean) {
        blackout.visibility = if (black) View.VISIBLE else View.GONE
        val params = window.attributes
        params.screenBrightness = if (black) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    private fun permissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
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
                statusView.text = "Turn on Location, then press GO LIVE"
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else statusView.text = "Allow Camera, Mic and Location, then press GO LIVE"
    }

    override fun refresh() {
        val svc = service
        if (svc != null) {
            statusView.text = svc.status
            running = svc.connected || svc.status.startsWith("Camera live") || svc.status.startsWith("Monitor")
            detectButton.text = if (svc.detecting) "DETECTION: ON" else "DETECTION: OFF"
        }
        toggleButton.text = if (running) "STOP" else "GO LIVE"
        codeInput.isEnabled = !running
    }

    override fun onScreen(black: Boolean) {
        runOnUiThread { setBlack(black) }
    }

    override fun onIncoming() {
        runOnUiThread {
            val svc = service ?: return@runOnUiThread
            request?.dismiss()
            request = AlertDialog.Builder(this)
                .setTitle("Connection request")
                .setMessage("A monitor wants to view this camera. Allow?")
                .setCancelable(false)
                .setPositiveButton("Accept") { _, _ -> svc.acceptRequest() }
                .setNegativeButton("Reject") { _, _ -> svc.rejectRequest() }
                .show()
        }
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

    private fun renderQr() {
        val code = codeInput.text.toString()
        if (code.length != 6) {
            qrImage.visibility = View.GONE
            return
        }
        val name = nameInput.text.toString().trim().take(16)
        try {
            qrImage.setImageBitmap(Qr.encode("SENTRY:$code:$name", 480))
            qrImage.visibility = View.VISIBLE
        } catch (e: Exception) {
            qrImage.visibility = View.GONE
        }
    }
}
