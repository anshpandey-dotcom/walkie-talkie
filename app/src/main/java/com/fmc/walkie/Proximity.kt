package com.fmc.walkie

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.random.Random

@SuppressLint("MissingPermission")
class Proximity(private val adapter: BluetoothAdapter, private val onDistance: (Double?) -> Unit) {

    private val serviceUuid = ParcelUuid.fromString("0000b7a1-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private var smoothed = Double.NaN
    private var lastSeen = 0L
    private var running = false

    val token = Random.nextInt()

    @Volatile
    var peerToken: Int? = null

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(serviceUuid) ?: return
            if (data.size < 4 || ByteBuffer.wrap(data).int != peerToken) return
            val rssi = result.rssi.toDouble()
            smoothed = if (smoothed.isNaN()) rssi else smoothed * 0.8 + rssi * 0.2
            lastSeen = SystemClock.elapsedRealtime()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val fresh = SystemClock.elapsedRealtime() - lastSeen < 6000
            onDistance(if (fresh && !smoothed.isNaN()) 10.0.pow((-59.0 - smoothed) / 22.0) else null)
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        smoothed = Double.NaN
        lastSeen = 0L
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, ByteBuffer.allocate(4).putInt(token).array())
            .build()
        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            adapter.bluetoothLeScanner?.startScan(listOf(filter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            running = false
            return
        }
        handler.post(ticker)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            peerToken = null
        } catch (e: IllegalStateException) {
            peerToken = null
        }
    }
}
