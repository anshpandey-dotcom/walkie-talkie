package com.fmc.walkie

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : Activity(), BluetoothLink.Listener {

    private var adapter: BluetoothAdapter? = null
    private var link: BluetoothLink? = null
    private val audio = AudioEngine()
    private val devices = ArrayList<BluetoothDevice>()
    private val labels = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var statusView: TextView
    private lateinit var talkButton: Button
    private lateinit var disconnectButton: Button
    private var connected = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> deviceFrom(intent)?.let { addDevice(it, false) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> setStatus("Scan finished. Tap a phone to connect.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = findViewById(R.id.status)
        talkButton = findViewById(R.id.talk)
        disconnectButton = findViewById(R.id.disconnect)
        val hostButton = findViewById<Button>(R.id.host)
        val scanButton = findViewById<Button>(R.id.scan)
        val listView = findViewById<ListView>(R.id.devices)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = listAdapter

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetooth: BluetoothAdapter? = manager.adapter
        if (bluetooth == null) {
            setStatus("This phone has no Bluetooth")
            hostButton.isEnabled = false
            scanButton.isEnabled = false
            return
        }
        adapter = bluetooth
        link = BluetoothLink(bluetooth, this)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true

        hostButton.setOnClickListener {
            if (ready()) startHosting()
        }
        scanButton.setOnClickListener {
            if (ready()) startScan()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (ready()) {
                bluetooth.cancelDiscovery()
                link?.connect(devices[position])
            }
        }
        disconnectButton.setOnClickListener {
            link?.stop()
            onDisconnected()
        }
        talkButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    startTalk()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    stopTalk()
                    true
                }
                else -> false
            }
        }

        ready()
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return list
    }

    private fun ready(): Boolean {
        val bluetooth = adapter ?: return false
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return false
        }
        if (!bluetooth.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
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
            setStatus("Allow all permissions, then try again")
        } else {
            setStatus("Ready. Host on one phone, scan on the other.")
        }
    }

    private fun startHosting() {
        link?.listen()
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(intent)
    }

    private fun startScan() {
        val bluetooth = adapter ?: return
        devices.clear()
        labels.clear()
        bluetooth.bondedDevices.forEach { addDevice(it, true) }
        listAdapter.notifyDataSetChanged()
        bluetooth.cancelDiscovery()
        if (bluetooth.startDiscovery()) {
            setStatus("Scanning for nearby phones...")
        } else {
            setStatus("Scan failed. Turn on location and try again.")
        }
    }

    private fun addDevice(device: BluetoothDevice, paired: Boolean) {
        if (devices.any { it.address == device.address }) return
        devices.add(device)
        val name = device.name ?: "Unknown device"
        labels.add(if (paired) "$name (paired)\n${device.address}" else "$name\n${device.address}")
        listAdapter.notifyDataSetChanged()
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun startTalk() {
        if (!connected) return
        talkButton.text = "TALKING..."
        audio.startTalking { buffer, length -> link?.send(buffer, length) ?: false }
    }

    private fun stopTalk() {
        audio.stopTalking()
        talkButton.text = "HOLD TO TALK"
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    override fun onState(text: String) {
        setStatus(text)
    }

    override fun onConnected(name: String) {
        connected = true
        audio.startPlayback()
        runOnUiThread {
            statusView.text = "Connected to $name"
            talkButton.isEnabled = true
            disconnectButton.isEnabled = true
        }
    }

    override fun onDisconnected() {
        connected = false
        audio.stopTalking()
        audio.stopPlayback()
        runOnUiThread {
            statusView.text = "Disconnected"
            talkButton.text = "HOLD TO TALK"
            talkButton.isEnabled = false
            disconnectButton.isEnabled = false
        }
    }

    override fun onAudio(data: ByteArray, length: Int) {
        audio.play(data, length)
    }

    override fun onDestroy() {
        super.onDestroy()
        link?.stop()
        audio.stopTalking()
        audio.stopPlayback()
        adapter?.cancelDiscovery()
        if (receiverRegistered) unregisterReceiver(receiver)
    }
}
