package com.fmc.walkie

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

@Suppress("DEPRECATION")
class CameraRig(private val onFrame: (ByteArray, Int, Int, Int, Int, Int) -> Unit) {

    private val dummy = SurfaceTexture(10)
    private val grid = 8
    var previewSurface: SurfaceTexture? = null
    private var previous: IntArray? = null
    private var camera: Camera? = null
    private var facing = Camera.CameraInfo.CAMERA_FACING_BACK
    private var rotation = 0
    private var width = 0
    private var height = 0
    private var torch = false
    private var night = false

    val hasMultipleCameras: Boolean
        get() = Camera.getNumberOfCameras() > 1

    fun start() {
        if (camera != null) open()
        else open()
    }

    fun stop() {
        camera?.let {
            it.setPreviewCallback(null)
            it.stopPreview()
            it.release()
        }
        camera = null
        previous = null
    }

    fun switchCamera() {
        facing = if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        } else {
            Camera.CameraInfo.CAMERA_FACING_BACK
        }
        stop()
        open()
    }

    fun setTorch(on: Boolean) {
        torch = on
        val current = camera ?: return
        try {
            val params = current.parameters
            val modes = params.supportedFlashModes ?: return
            val mode = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            if (modes.contains(mode)) {
                params.flashMode = mode
                current.parameters = params
            }
        } catch (e: RuntimeException) {
            torch = on
        }
    }

    fun setNight(on: Boolean) {
        if (night == on) return
        night = on
        applyNight()
    }

    fun encode(nv21: ByteArray, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, output)
        return output.toByteArray()
    }

    private fun open() {
        if (Camera.getNumberOfCameras() == 0) return
        val info = Camera.CameraInfo()
        val index = (0 until Camera.getNumberOfCameras()).firstOrNull {
            Camera.getCameraInfo(it, info)
            info.facing == facing
        } ?: 0
        Camera.getCameraInfo(index, info)
        val opened = try {
            Camera.open(index)
        } catch (e: RuntimeException) {
            return
        }
        try {
            val params = opened.parameters
            params.supportedPreviewSizes.minByOrNull { abs(it.width * it.height - 480 * 360) }?.let {
                params.setPreviewSize(it.width, it.height)
            }
            params.previewFormat = ImageFormat.NV21
            opened.parameters = params
            width = opened.parameters.previewSize.width
            height = opened.parameters.previewSize.height
            rotation = info.orientation
            val surface = previewSurface
            if (surface != null) {
                opened.setDisplayOrientation(if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - info.orientation) % 360 else info.orientation)
                opened.setPreviewTexture(surface)
            } else {
                opened.setPreviewTexture(dummy)
            }
            opened.setPreviewCallback { data, _ -> frame(data) }
            opened.startPreview()
            camera = opened
            applyNight()
            if (torch) setTorch(true)
        } catch (e: IOException) {
            opened.release()
        } catch (e: RuntimeException) {
            opened.release()
        }
    }

    private fun applyNight() {
        val current = camera ?: return
        try {
            val params = current.parameters
            if (night) {
                params.supportedSceneModes?.let {
                    if (it.contains(Camera.Parameters.SCENE_MODE_NIGHT)) params.sceneMode = Camera.Parameters.SCENE_MODE_NIGHT
                }
                if (params.maxExposureCompensation > 0) params.exposureCompensation = params.maxExposureCompensation
            } else {
                params.supportedSceneModes?.let {
                    if (it.contains(Camera.Parameters.SCENE_MODE_AUTO)) params.sceneMode = Camera.Parameters.SCENE_MODE_AUTO
                }
                params.exposureCompensation = 0
            }
            current.parameters = params
        } catch (e: RuntimeException) {
            night = night
        }
    }

    private fun frame(data: ByteArray?) {
        if (data == null || data.size < width * height) return
        val cells = IntArray(grid * grid)
        val cellW = width / grid
        val cellH = height / grid
        var luma = 0L
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                var sum = 0
                val baseX = gx * cellW
                val baseY = gy * cellH
                var samples = 0
                var y = baseY
                while (y < baseY + cellH) {
                    var x = baseX
                    while (x < baseX + cellW) {
                        sum += data[y * width + x].toInt() and 0xFF
                        samples++
                        x += 4
                    }
                    y += 4
                }
                val avg = if (samples == 0) 0 else sum / samples
                cells[gy * grid + gx] = avg
                luma += avg
            }
        }
        var motion = 0
        previous?.let { old ->
            for (i in cells.indices) if (abs(cells[i] - old[i]) > 14) motion++
        }
        previous = cells
        onFrame(data, width, height, rotation, luma.toInt() / (grid * grid), motion)
    }
}
