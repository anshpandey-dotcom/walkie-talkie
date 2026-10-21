package com.fmc.walkie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class RadioService : Service(), RadioLink.Listener {

    interface Ui {
        fun refresh()
        fun onIncoming(id: String, name: String)
        fun onVideo(frame: Bitmap)
    }

    class Peer(val id: String, val name: String)

    inner class LocalBinder : Binder() {
        val service: RadioService
            get() = this@RadioService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = AudioEngine()
    private lateinit var link: RadioLink
    private lateinit var notifier: NotificationManager
    private var proximity: Proximity? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var closedByUser = false
    private var reconnectTo: String? = null
    private var touchDown = false
    private var latched = false

    var ui: Ui? = null
        set(value) {
            field = value
            if (value != null) notifier.cancel(NOTIFY_MESSAGE)
        }

    val peers = ArrayList<Peer>()
    val messages = ArrayList<String>()

    var status = "Press SEARCH on both phones"
        private set
    var searching = false
        private set
    var connected = false
        private set
    var peerName = ""
        private set
    var transmitting = false
        private set
    var secureCode = ""
        private set
    var signalPercent = 100
        private set
    var pingMs = 0
        private set
    var distanceText = "--"
        private set
    var pendingRequest: Peer? = null
        private set

    val vox: Boolean
        get() = audio.vox

    private var callsign = ""

    override fun onCreate() {
        super.onCreate()
        link = RadioLink(this, this)
        notifier = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifier.createNotificationChannel(
                NotificationChannel(CHANNEL_LINK, "Radio link", NotificationManager.IMPORTANCE_LOW)
            )
            notifier.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Calls and messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TALK -> {
                latched = !latched
                applyTalk()
                changed()
            }
            ACTION_VOX -> toggleVox()
            ACTION_HANG_UP -> hangUp()
            ACTION_STOP -> stopSearch()
            ACTION_ACCEPT -> intent.getStringExtra(EXTRA_ID)?.let { accept(it) }
            ACTION_REJECT -> intent.getStringExtra(EXTRA_ID)?.let { reject(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        proximity?.stop()
        link.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    fun search(name: String) {
        callsign = name
        peers.clear()
        link.search(name)
        searching = true
        status = "On air as $name. Looking for radios... Keep Bluetooth, Wi-Fi and Location ON. No internet needed."
        changed()
    }

    fun stopSearch() {
        link.stopSearch()
        searching = false
        reconnectTo = null
        if (!connected) status = "Search stopped"
        changed()
        settle()
    }

    fun connect(peer: Peer) {
        reconnectTo = null
        status = "Calling ${peer.name}..."
        changed()
        link.connect(peer.id)
    }

    fun accept(id: String) {
        pendingRequest = null
        notifier.cancel(NOTIFY_REQUEST)
        link.accept(id)
    }

    fun reject(id: String) {
        pendingRequest = null
        notifier.cancel(NOTIFY_REQUEST)
        link.reject(id)
        changed()
    }

    fun hangUp() {
        if (!connected) {
            stopSearch()
            return
        }
        reconnectTo = null
        closedByUser = true
        link.disconnect()
        onDisconnected()
    }

    fun setTouchTalk(down: Boolean) {
        touchDown = down
        if (!down) latched = false
        applyTalk()
    }

    fun toggleVox() {
        audio.vox = !audio.vox
        changed()
    }

    fun sendText(text: String) {
        if (!connected) return
        link.sendText(text)
        messages.add("ME: $text")
        changed()
    }

    fun sendVideo(jpeg: ByteArray, rotation: Int): Boolean {
        return connected && secureCode.isNotEmpty() && link.sendVideo(jpeg, rotation)
    }

    private fun applyTalk() {
        audio.pushToTalk = touchDown || latched
    }

    private fun changed() {
        ui?.refresh()
        if (foreground) notifier.notify(NOTIFY_LINK, buildNotification())
    }

    private fun goForeground() {
        if (foreground) return
        try {
            when {
                Build.VERSION.SDK_INT >= 30 -> startForeground(
                    NOTIFY_LINK,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
                Build.VERSION.SDK_INT == 29 -> startForeground(
                    NOTIFY_LINK,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
                else -> startForeground(NOTIFY_LINK, buildNotification())
            }
            foreground = true
        } catch (e: RuntimeException) {
            foreground = false
        }
    }

    private fun settle() {
        if (searching || connected || reconnectTo != null) return
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun boostVolume() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) < max / 2) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, max * 3 / 4, 0)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtWalkie:radio").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onState(text: String) {
        status = text
        changed()
    }

    override fun onPeerFound(id: String, name: String) {
        peers.removeAll { it.id == id }
        peers.add(Peer(id, name))
        status = "Found ${peers.size} radio(s). Tap one to connect."
        changed()
        if (name == reconnectTo && callsign < name) link.connect(id)
    }

    override fun onPeerLost(id: String) {
        peers.removeAll { it.id == id }
        changed()
    }

    override fun onConnectionRequest(id: String, name: String) {
        if (name == reconnectTo) {
            link.accept(id)
            return
        }
        pendingRequest = Peer(id, name)
        val current = ui
        if (current != null) current.onIncoming(id, name) else notifyRequest(id, name)
    }

    override fun onConnected(name: String) {
        pendingRequest = null
        notifier.cancel(NOTIFY_REQUEST)
        searching = false
        connected = true
        peerName = name
        reconnectTo = null
        signalPercent = 100
        pingMs = 0
        secureCode = ""
        distanceText = "measuring..."
        messages.clear()
        boostVolume()
        acquireWakeLock()
        audio.start(
            onFrame = { link.sendAudio(it) },
            onTalk = { talking ->
                link.sendTalk(talking)
                handler.post {
                    transmitting = talking
                    changed()
                }
            }
        )
        status = "Connected to $name. Securing channel..."
        goForeground()
        changed()
    }

    override fun onSecure(code: String) {
        secureCode = code
        status = "Connected to $peerName. Channel encrypted."
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter != null && proximity == null) {
            proximity = Proximity(adapter) { meters ->
                distanceText = when {
                    meters == null -> "far / out of Bluetooth range"
                    meters < 1.0 -> "under 1 m (approx)"
                    else -> "~${meters.roundToInt()} m (approx)"
                }
                ui?.refresh()
            }.also {
                it.start()
                link.sendHello(it.token)
            }
        }
        changed()
    }

    override fun onDisconnected() {
        val lost = connected && !closedByUser
        val previous = peerName
        connected = false
        transmitting = false
        touchDown = false
        latched = false
        applyTalk()
        audio.stop()
        proximity?.stop()
        proximity = null
        releaseWakeLock()
        closedByUser = false
        peerName = ""
        secureCode = ""
        if (lost) {
            reconnectTo = previous
            search(callsign)
            status = "Link lost with $previous. Reconnecting..."
            changed()
        } else {
            status = "Disconnected. Press SEARCH to find radios."
            changed()
            settle()
        }
    }

    override fun onAudio(data: ByteArray, offset: Int, length: Int) {
        audio.receive(data, offset, length)
    }

    override fun onVideo(data: ByteArray, offset: Int, length: Int, rotation: Int) {
        val current = ui ?: return
        val decoded = BitmapFactory.decodeByteArray(data, offset, length) ?: return
        val frame = if (rotation == 0) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        current.onVideo(frame)
    }

    override fun onText(text: String) {
        messages.add("$peerName: $text")
        changed()
        if (ui == null) notifyMessage(text)
    }

    override fun onTalkState(talking: Boolean) {
        audio.remoteTalk(talking)
        status = if (talking) "$peerName is talking..." else "Connected to $peerName. Channel encrypted."
        changed()
    }

    override fun onHello(token: Int, name: String) {
        proximity?.peerToken = token
        if (name.isNotEmpty()) peerName = name
        changed()
    }

    override fun onQuality(rttMs: Int, percent: Int) {
        signalPercent = percent
        pingMs = rttMs
        changed()
    }

    @Suppress("DEPRECATION")
    private fun builder(channel: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return builder.setSmallIcon(R.drawable.ic_radio).setContentIntent(open).setShowWhen(false)
    }

    private fun action(label: String, action: String, code: Int, id: String? = null): Notification.Action {
        val intent = Intent(this, RadioService::class.java).setAction(action)
        if (id != null) intent.putExtra(EXTRA_ID, id)
        val pending = PendingIntent.getService(
            this,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_radio), label, pending).build()
    }

    private fun buildNotification(): Notification {
        val builder = builder(CHANNEL_LINK).setOngoing(true).setOnlyAlertOnce(true)
        if (connected) {
            val mode = if (transmitting) "TRANSMITTING" else "Listening"
            builder.setContentTitle("On channel with $peerName")
                .setContentText("$mode · LINK $signalPercent% · $distanceText")
                .addAction(action(if (latched) "OVER" else "TALK", ACTION_TALK, 1))
                .addAction(action(if (audio.vox) "VOX OFF" else "VOX ON", ACTION_VOX, 2))
                .addAction(action("HANG UP", ACTION_HANG_UP, 3))
        } else {
            val title = reconnectTo?.let { "Reconnecting to $it" } ?: "Searching for radios"
            builder.setContentTitle(title)
                .setContentText(status)
                .addAction(action("STOP", ACTION_STOP, 4))
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun notifyRequest(id: String, name: String) {
        val notification = builder(CHANNEL_ALERT)
            .setContentTitle("Incoming radio")
            .setContentText("$name wants to connect")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(action("ACCEPT", ACTION_ACCEPT, 5, id))
            .addAction(action("REJECT", ACTION_REJECT, 6, id))
            .build()
        notifier.notify(NOTIFY_REQUEST, notification)
    }

    @Suppress("DEPRECATION")
    private fun notifyMessage(text: String) {
        val notification = builder(CHANNEL_ALERT)
            .setContentTitle(peerName)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifier.notify(NOTIFY_MESSAGE, notification)
    }

    companion object {
        private const val CHANNEL_LINK = "radio_link"
        private const val CHANNEL_ALERT = "radio_alert"
        private const val NOTIFY_LINK = 1
        private const val NOTIFY_REQUEST = 2
        private const val NOTIFY_MESSAGE = 3
        private const val EXTRA_ID = "endpoint"
        private const val ACTION_TALK = "com.fmc.walkie.TALK"
        private const val ACTION_VOX = "com.fmc.walkie.VOX"
        private const val ACTION_HANG_UP = "com.fmc.walkie.HANG_UP"
        private const val ACTION_STOP = "com.fmc.walkie.STOP"
        private const val ACTION_ACCEPT = "com.fmc.walkie.ACCEPT"
        private const val ACTION_REJECT = "com.fmc.walkie.REJECT"
    }
}
