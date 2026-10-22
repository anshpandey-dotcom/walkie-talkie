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
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class LinkService : Service(), Link.Listener {

    interface Ui {
        fun refresh() {}
        fun onRemoteFrame(frame: Bitmap) {}
        fun onScreen(black: Boolean) {}
        fun onIncoming() {}
    }

    inner class LocalBinder : Binder() {
        val service: LinkService
            get() = this@LinkService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = AudioIO()
    private lateinit var notifier: NotificationManager
    private lateinit var link: Link
    private var proximity: Proximity? = null
    private var rig: CameraRig? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sirenActive = false
    private var foreground = false
    private var camera = false
    private var started = false
    private var stopping = false
    private var cameraName = ""

    private var baseline = 1200.0
    private var triggerUntil = 0L
    private var lastVideo = 0L
    private var cameraOn = false
    private var clipDir: File? = null
    private var gainFactor = 2f

    var ui: Ui? = null
    var code = ""
        private set

    var status = "Idle"
        private set
    var connected = false
        private set
    var secureCode = ""
        private set
    var signalPercent = 100
        private set
    var pingMs = 0
        private set
    var distanceText = "--"
        private set
    var detecting = true
        private set
    var streaming = false
        private set
    var flashOn = false
        private set
    var listening = false
        private set
    var lastAlert = ""
        private set
    var lastAlertAt = 0L
        private set
    var hasRequest = false
        private set
    private var pendingId: String? = null
    var clipCount = 0
        private set

    override fun onCreate() {
        super.onCreate()
        notifier = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifier.createNotificationChannel(NotificationChannel(CHANNEL, "Secure link", NotificationManager.IMPORTANCE_LOW))
            notifier.createNotificationChannel(NotificationChannel(ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SOS -> toggleSos()
            ACTION_ACCEPT -> acceptRequest()
            ACTION_REJECT -> rejectRequest()
            ACTION_PANIC -> if (this::link.isInitialized) link.send(Op.COMMAND, byteArrayOf(Cmd.SOS, 1))
            else -> {
                camera = intent?.getBooleanExtra(EXTRA_CAMERA, false) ?: camera
                code = intent?.getStringExtra(EXTRA_CODE) ?: code
                cameraName = intent?.getStringExtra(EXTRA_NAME) ?: cameraName
                goForeground()
                if (code.length == 6) begin(camera, code, cameraName)
                return START_NOT_STICKY
            }
        }
        goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        rig?.stop()
        proximity?.stop()
        tts?.shutdown()
        if (this::link.isInitialized) link.shutdown()
        releaseWake()
        super.onDestroy()
    }

    fun begin(asCamera: Boolean, pairingCode: String, name: String = "") {
        if (started) return
        started = true
        stopping = false
        camera = asCamera
        code = pairingCode
        cameraName = name
        if (!this::link.isInitialized) link = Link(this, asCamera, this)
        clipDir = File(getExternalFilesDir(null), "clips").apply { mkdirs() }
        clipCount = clipDir?.listFiles()?.count { it.isDirectory } ?: 0
        goForeground()
        link.start(pairingCode, name)
        status = if (asCamera) "Camera live. Code $pairingCode. Waiting for monitor." else "Searching for camera with code $pairingCode..."
        if (asCamera) {
            audio.transmit = false
            audio.start(onFrame = { link.sendMedia(Op.AUDIO, it) }, onLevel = { onSound(it) })
            raiseVolume()
            acquireWake()
        }
        changed()
    }

    fun shutdown() {
        stopping = true
        audio.stop()
        rig?.stop()
        rig = null
        cameraOn = false
        proximity?.stop()
        proximity = null
        if (this::link.isInitialized) link.shutdown()
        releaseWake()
        connected = false
        started = false
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    fun command(cmd: Byte, arg: Int = 0, text: String = "") {
        val body = when {
            text.isNotEmpty() -> byteArrayOf(cmd) + text.toByteArray(Charsets.UTF_8)
            else -> byteArrayOf(cmd, arg.toByte())
        }
        link.send(Op.COMMAND, body)
    }

    fun setDetecting(on: Boolean) {
        detecting = on
        changed()
    }

    fun setListening(on: Boolean) {
        listening = on
        command(Cmd.MIC, if (on) 1 else 0)
        changed()
    }

    fun setStreaming(on: Boolean) {
        streaming = on
        command(Cmd.STREAM, if (on) 1 else 0)
        changed()
    }

    fun setAudioGain(value: Float) {
        gainFactor = value
        audio.gain = value
    }

    fun toggleMute() {
        audio.muted = !audio.muted
        changed()
    }

    val muted: Boolean
        get() = audio.muted

    fun talk(down: Boolean) {
        audio.transmit = down
        command(Cmd.TALK, if (down) 1 else 0)
    }

    fun setCameraScreen(black: Boolean) {
        command(Cmd.SCREEN, if (black) 1 else 0)
    }

    fun startRecording() {
        openClip("manual")
    }

    private fun onSound(level: Double) {
        baseline = baseline * 0.995 + level * 0.005
        if (detecting && level > baseline * 3 && level > 2500 && SystemClock.elapsedRealtime() - triggerUntil > 4000) {
            trigger(1, "Loud sound at the camera")
        }
    }

    private fun trigger(kind: Int, text: String) {
        triggerUntil = SystemClock.elapsedRealtime() + 15000
        ensureCamera(true)
        link.send(Op.ALERT, byteArrayOf(kind.toByte()) + text.toByteArray(Charsets.UTF_8))
        lastAlert = text
        changed()
    }

    private fun ensureCamera(on: Boolean) {
        if (on && !cameraOn) {
            val engine = rig ?: CameraRig { data, _, _, rotation, luma, motion -> onCameraFrame(data, rotation, luma, motion) }.also { rig = it }
            engine.start()
            cameraOn = true
        } else if (!on && cameraOn && !streaming && SystemClock.elapsedRealtime() > triggerUntil) {
            rig?.stop()
            cameraOn = false
        }
    }

    private fun onCameraFrame(data: ByteArray, rotation: Int, luma: Int, motion: Int) {
        val engine = rig ?: return
        engine.setNight(luma < 45)
        if (detecting && motion > 10 && SystemClock.elapsedRealtime() - triggerUntil > 4000) {
            handler.post { trigger(0, "Movement at the camera") }
        }
        val now = SystemClock.elapsedRealtime()
        val active = streaming || now < triggerUntil
        if (active && now - lastVideo > 110) {
            val jpeg = engine.encode(data, if (now < triggerUntil) 60 else 45)
            if (link.sendMedia(Op.VIDEO, byteArrayOf((rotation / 90).toByte()) + jpeg)) lastVideo = now
        }
        if (now > triggerUntil && !streaming && cameraOn) handler.post { ensureCamera(false) }
    }

    override fun onState(text: String) {
        status = text
        changed()
    }

    override fun onFound(id: String, name: String) {
        if (name.isNotEmpty()) {
            getSharedPreferences("mon", Context.MODE_PRIVATE).edit().putString("name_$code", name).apply()
        }
        status = if (name.isEmpty()) "Camera found. Connecting..." else "Found \"$name\". Connecting..."
        changed()
    }

    override fun onConnectRequest(id: String) {
        if (getSharedPreferences("svc", Context.MODE_PRIVATE).getBoolean("trust_$code", false)) {
            link.accept(id)
            return
        }
        pendingId = id
        hasRequest = true
        status = "A monitor wants to connect. Accept?"
        notifyRequest()
        changed()
        handler.post { ui?.onIncoming() }
    }

    fun acceptRequest() {
        val id = pendingId ?: return
        pendingId = null
        hasRequest = false
        notifier.cancel(NOTIFY_REQUEST)
        getSharedPreferences("svc", Context.MODE_PRIVATE).edit().putBoolean("trust_$code", true).apply()
        link.accept(id)
        changed()
    }

    fun rejectRequest() {
        val id = pendingId ?: return
        pendingId = null
        hasRequest = false
        notifier.cancel(NOTIFY_REQUEST)
        link.reject(id)
        status = "Request rejected."
        changed()
    }

    override fun onConnected() {
        connected = true
        hasRequest = false
        pendingId = null
        notifier.cancel(NOTIFY_REQUEST)
        secureCode = ""
        signalPercent = 100
        status = "Linked. Securing channel..."
        if (!camera) {
            audio.gain = gainFactor
            audio.start(onFrame = { link.sendMedia(Op.AUDIO, it) }, onLevel = {})
            audio.transmit = false
        }
        changed()
    }

    override fun onSecure(code: String) {
        secureCode = code
        status = if (camera) "Monitor connected. Channel encrypted." else "Connected to camera. Channel encrypted."
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter != null && proximity == null) {
            proximity = Proximity(adapter) { meters ->
                distanceText = when {
                    meters == null -> "out of Bluetooth range"
                    meters < 1.0 -> "under 1 m"
                    else -> "~${meters.roundToInt()} m"
                }
                ui?.refresh()
            }.also {
                it.start()
                link.send(Op.STATE, byteArrayOf(SUB_TOKEN) + ByteBuffer.allocate(4).putInt(it.token).array())
            }
        }
        changed()
    }

    override fun onDisconnected() {
        connected = false
        secureCode = ""
        proximity?.stop()
        proximity = null
        if (stopping) {
            changed()
            return
        }
        if (camera) {
            ensureCamera(false)
            status = "Monitor disconnected. Camera live, waiting."
            link.start(code, cameraName)
        } else {
            audio.stop()
            if (started) {
                status = "Link dropped. Reconnecting to code $code..."
                link.start(code)
            } else {
                status = "Disconnected. Send a new pair request."
            }
        }
        changed()
    }

    override fun onQuality(rttMs: Int, percent: Int) {
        pingMs = rttMs
        signalPercent = percent
        changed()
    }

    override fun onMessage(type: Byte, data: ByteArray, offset: Int, length: Int) {
        when (type) {
            Op.AUDIO -> audio.receive(data, offset, length)
            Op.VIDEO -> showFrame(data, offset, length)
            Op.PHOTO -> savePhoto(data, offset, length)
            Op.ALERT -> onAlert(data, offset, length)
            Op.STATE -> onStateMessage(data, offset, length)
            Op.COMMAND -> if (camera) onCommand(data, offset, length)
        }
    }

    private fun onCommand(data: ByteArray, offset: Int, length: Int) {
        if (length < 1) return
        val cmd = data[offset]
        val arg = if (length >= 2) data[offset + 1].toInt() else 0
        when (cmd) {
            Cmd.FLASH -> ensureCamera(true).also { rig?.setTorch(arg == 1); flashOn = arg == 1 }
            Cmd.SWITCH -> rig?.switchCamera()
            Cmd.STREAM -> {
                streaming = arg == 1
                ensureCamera(arg == 1)
            }
            Cmd.MIC -> audio.transmit = arg == 1
            Cmd.SOS -> if (arg == 1) startSiren() else stopSiren()
            Cmd.SPEAK -> speak(String(data, offset + 1, length - 1, Charsets.UTF_8))
            Cmd.SCREEN -> ui?.onScreen(arg == 1)
            Cmd.PHOTO -> takePhoto()
            Cmd.DETECT -> detecting = arg == 1
            Cmd.TALK -> if (arg == 1) raiseVolume()
            else -> Unit
        }
        changed()
    }

    private fun takePhoto() {
        ensureCamera(true)
        triggerUntil = maxOf(triggerUntil, SystemClock.elapsedRealtime() + 3000)
    }

    private fun onAlert(data: ByteArray, offset: Int, length: Int) {
        val text = String(data, offset + 1, length - 1, Charsets.UTF_8)
        lastAlert = text
        lastAlertAt = System.currentTimeMillis()
        openClip("alert")
        notifyAlert(text)
        changed()
    }

    private fun onStateMessage(data: ByteArray, offset: Int, length: Int) {
        if (length >= 5 && data[offset] == SUB_TOKEN) {
            proximity?.peerToken = ByteBuffer.wrap(data, offset + 1, 4).int
        }
    }

    private fun showFrame(data: ByteArray, offset: Int, length: Int) {
        if (length < 2) return
        val rotation = data[offset] * 90
        val decoded = BitmapFactory.decodeByteArray(data, offset + 1, length - 1) ?: return
        val frame = if (rotation == 0) decoded else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        ui?.onRemoteFrame(frame)
        clipDir?.let { base ->
            val target = recording ?: return@let
            val file = File(target, "f_${SystemClock.elapsedRealtime()}.jpg")
            file.outputStream().use { it.write(data, offset + 1, length - 1) }
        }
    }

    private var recording: File? = null
    private var recordStop = 0L

    private fun openClip(reason: String) {
        if (recording != null) {
            recordStop = SystemClock.elapsedRealtime() + 15000
            return
        }
        val base = clipDir ?: return
        val dir = File(base, "${System.currentTimeMillis()}_$reason").apply { mkdirs() }
        recording = dir
        recordStop = SystemClock.elapsedRealtime() + 15000
        clipCount = base.listFiles()?.count { it.isDirectory } ?: clipCount
        handler.post(object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() >= recordStop) {
                    recording = null
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }

    private fun savePhoto(data: ByteArray, offset: Int, length: Int) {
        val base = clipDir ?: return
        val dir = recording ?: File(base, "${System.currentTimeMillis()}_photo").apply { mkdirs() }
        File(dir, "p_${SystemClock.elapsedRealtime()}.jpg").outputStream().use { it.write(data, offset, length) }
        clipCount = base.listFiles()?.count { it.isDirectory } ?: clipCount
    }

    private fun speak(text: String) {
        raiseVolume()
        val engine = tts ?: TextToSpeech(this) { }.also { tts = it }
        handler.postDelayed({
            engine.language = Locale.getDefault()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
        }, 300)
    }

    fun toggleSos() {
        if (sirenActive) stopSiren() else startSiren()
    }

    private fun startSiren() {
        if (sirenActive) return
        sirenActive = true
        raiseVolume()
        Thread {
            val generator = try {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            } catch (e: RuntimeException) {
                sirenActive = false
                return@Thread
            }
            while (sirenActive) {
                generator.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600)
                sleep(650)
                generator.startTone(ToneGenerator.TONE_CDMA_MED_L, 600)
                sleep(650)
            }
            generator.release()
        }.start()
        changed()
    }

    private fun stopSiren() {
        sirenActive = false
        changed()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            sirenActive = false
        }
    }

    private fun raiseVolume() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM).forEach {
            manager.setStreamVolume(it, manager.getStreamMaxVolume(it), 0)
        }
    }

    private fun changed() {
        handler.post { ui?.refresh() }
        if (foreground) notifier.notify(NOTIFY, buildNotification())
    }

    private fun goForeground() {
        if (foreground) return
        try {
            val type = if (camera) {
                var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= 30) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                t
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY, buildNotification(), type)
            else startForeground(NOTIFY, buildNotification())
            foreground = true
        } catch (e: RuntimeException) {
            foreground = false
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtWalkie:camera").apply { acquire() }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun activityClass() = if (camera) CameraActivity::class.java else MonitorActivity::class.java

    @Suppress("DEPRECATION")
    private fun newBuilder(channel: String): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
    }

    private fun buildNotification(): Notification {
        val builder = newBuilder(CHANNEL)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, activityClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (camera) "Camera active" else "Monitor active"
        val body = if (connected) {
            if (camera) "Recording when triggered · LINK $signalPercent%" else "$status · LINK $signalPercent%"
        } else {
            status
        }
        return builder.setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_radio), "STOP", stop).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun notifyRequest() {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, activityClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val accept = PendingIntent.getService(
            this,
            4,
            Intent(this, LinkService::class.java).setAction(ACTION_ACCEPT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val reject = PendingIntent.getService(
            this,
            5,
            Intent(this, LinkService::class.java).setAction(ACTION_REJECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_radio)
        notifier.notify(
            NOTIFY_REQUEST,
            newBuilder(ALERTS).setSmallIcon(R.drawable.ic_radio)
                .setContentTitle("Connection request")
                .setContentText("A monitor wants to view this camera")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(icon, "ACCEPT", accept).build())
                .addAction(Notification.Action.Builder(icon, "REJECT", reject).build())
                .build()
        )
    }

    @Suppress("DEPRECATION")
    private fun notifyAlert(text: String) {
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, activityClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = newBuilder(ALERTS)
        notifier.notify(
            NOTIFY_ALERT,
            builder.setSmallIcon(R.drawable.ic_radio)
                .setContentTitle("Camera alert")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    companion object {
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val ACTION_STOP = "com.fmc.walkie.STOP"
        const val ACTION_SOS = "com.fmc.walkie.SOS"
        const val ACTION_PANIC = "com.fmc.walkie.PANIC"
        const val ACTION_ACCEPT = "com.fmc.walkie.ACCEPT"
        const val ACTION_REJECT = "com.fmc.walkie.REJECT"
        private const val CHANNEL = "secure_link"
        private const val ALERTS = "secure_alerts"
        private const val NOTIFY = 1
        private const val NOTIFY_ALERT = 2
        private const val NOTIFY_REQUEST = 4
        private const val SUB_TOKEN: Byte = 1
    }
}
