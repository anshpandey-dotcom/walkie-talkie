package com.fmc.walkie

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import android.widget.Toast

class ScanActivity : Activity(), TextureView.SurfaceTextureListener {

    private var rig: CameraRig? = null
    private var lastDecode = 0L

    @Volatile
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        findViewById<TextureView>(R.id.preview).surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val engine = CameraRig { data, w, h, _, _, _ -> scan(data, w, h) }
        engine.previewSurface = surface
        engine.start()
        rig = engine
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun scan(data: ByteArray, width: Int, height: Int) {
        if (done) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDecode < 200) return
        lastDecode = now
        val text = Qr.decode(data, width, height) ?: return
        if (!text.startsWith("SENTRY:")) return
        val parts = text.split(":")
        val code = parts.getOrNull(1) ?: return
        if (code.length != 6) return
        done = true
        val name = parts.getOrNull(2) ?: ""
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra("code", code).putExtra("name", name))
            Toast.makeText(this, "Camera found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        rig?.stop()
        rig = null
    }
}
