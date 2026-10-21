package com.fmc.walkie

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.SystemClock
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

@Suppress("DEPRECATION")
class VideoSender(
    private val view: TextureView,
    private val send: (ByteArray, Int) -> Boolean
) : TextureView.SurfaceTextureListener {

    private var camera: Camera? = null
    private var rotation = 0
    private var width = 0
    private var height = 0
    private var lastFrame = 0L

    fun start() {
        val texture = view.surfaceTexture
        if (view.isAvailable && texture != null) open(texture) else view.surfaceTextureListener = this
    }

    fun stop() {
        view.surfaceTextureListener = null
        camera?.let {
            it.setPreviewCallback(null)
            it.stopPreview()
            it.release()
        }
        camera = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = open(surface)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stop()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun open(texture: SurfaceTexture) {
        if (camera != null || Camera.getNumberOfCameras() == 0) return
        val info = Camera.CameraInfo()
        val index = (0 until Camera.getNumberOfCameras()).firstOrNull {
            Camera.getCameraInfo(it, info)
            info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        } ?: 0
        Camera.getCameraInfo(index, info)
        val opened = try {
            Camera.open(index)
        } catch (e: RuntimeException) {
            return
        }
        try {
            val params = opened.parameters
            params.supportedPreviewSizes.minByOrNull { abs(it.width * it.height - 320 * 240) }?.let {
                params.setPreviewSize(it.width, it.height)
            }
            params.previewFormat = ImageFormat.NV21
            opened.parameters = params
            width = opened.parameters.previewSize.width
            height = opened.parameters.previewSize.height
            val front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            opened.setDisplayOrientation(if (front) (360 - info.orientation) % 360 else info.orientation)
            rotation = info.orientation
            opened.setPreviewTexture(texture)
            opened.setPreviewCallback { data, _ -> frame(data) }
            opened.startPreview()
            camera = opened
        } catch (e: IOException) {
            opened.release()
        } catch (e: RuntimeException) {
            opened.release()
        }
    }

    private fun frame(data: ByteArray?) {
        if (data == null || data.size < width * height * 3 / 2) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrame < 100) return
        val output = ByteArrayOutputStream()
        YuvImage(data, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 50, output)
        if (send(output.toByteArray(), rotation)) lastFrame = now
    }
}
