package com.stealthcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stealthcam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var volumeKeyDownTime = 0L
    private val LONG_PRESS_MS = 800L
    private var recordingBlinkRunnable: Runnable? = null
    private var wasJustStartedByLongPress = false  // 标记录像是否刚由长按启动

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
            startCameraService()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // 拍照：自动选最大分辨率（ResolutionSelector 优先最高像素）
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(9999, 9999), // 请求超大尺寸，系统会自动选传感器最大支持值
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // 最高画质模式
                .setResolutionSelector(resolutionSelector)
                .build()

            // 录像：优先 UHD(4K) → FHD(1080p) → HD(720p)
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (e: Exception) {
                showToast("相机启动失败")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── 前台服务（息屏保活）────────────────────────────────────────────────────

    private fun startCameraService() {
        startForegroundService(Intent(this, CameraService::class.java))
    }

    // ── 音量键：短按拍照，长按开始录像，录像中短按停止 ─────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeKeyDownTime = System.currentTimeMillis()
                    if (!isRecording) {
                        longPressRunnable = Runnable { startVideoRecording() }
                        handler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                    }
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - volumeKeyDownTime
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null

                when {
                    // 录像中，且不是刚由本次长按启动的，才停止录像
                    isRecording && !wasJustStartedByLongPress -> stopVideoRecording()
                    // 短按且未在录像中，拍照
                    held < LONG_PRESS_MS && !isRecording -> takePhoto()
                }
                // 重置标记
                wasJustStartedByLongPress = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── 拍照（最大像素）────────────────────────────────────────────────────────

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/隐形相机")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    showFlash()
                    showToast("✅ 照片已保存")
                }
                override fun onError(exception: ImageCaptureException) {
                    showToast("❌ 拍照失败: ${exception.message}")
                }
            }
        )
    }

    // ── 录像（最高清晰度）──────────────────────────────────────────────────────

    private fun startVideoRecording() {
        val videoCapture = videoCapture ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast("需要麦克风权限")
            return
        }

        wasJustStartedByLongPress = true  // 标记本次录像由长按启动

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/隐形相机")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        runOnUiThread { showRecordingUI(true) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        runOnUiThread { showRecordingUI(false) }
                        if (!event.hasError()) showToast("🎬 视频已保存")
                        else showToast("录像失败: ${event.error}")
                    }
                    else -> {}
                }
            }
    }

    private fun stopVideoRecording() {
        recording?.stop()
        recording = null
    }

    // ── UI 反馈 ────────────────────────────────────────────────────────────────

    private fun showFlash() {
        binding.previewView.alpha = 0f
        handler.postDelayed({ binding.previewView.alpha = 1f }, 150)
    }

    private fun showRecordingUI(show: Boolean) {
        if (show) {
            binding.recordingIndicator.visibility = View.VISIBLE
            showToast("🔴 录像中… 短按音量键停止")
            recordingBlinkRunnable = object : Runnable {
                override fun run() {
                    binding.recordingIndicator.visibility =
                        if (binding.recordingIndicator.visibility == View.VISIBLE)
                            View.INVISIBLE else View.VISIBLE
                    if (isRecording) handler.postDelayed(this, 600)
                }
            }
            handler.post(recordingBlinkRunnable!!)
        } else {
            handler.removeCallbacks(recordingBlinkRunnable ?: return)
            binding.recordingIndicator.visibility = View.GONE
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            binding.toastText.text = msg
            binding.toastText.visibility = View.VISIBLE
            handler.postDelayed({ binding.toastText.visibility = View.GONE }, 2500)
        }
    }

    // ── 权限 ──────────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && allPermissionsGranted()) {
            startCamera()
            startCameraService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isRecording) stopVideoRecording()
        stopService(Intent(this, CameraService::class.java))
    }
}
