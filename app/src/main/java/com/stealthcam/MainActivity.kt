package com.stealthcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
    private var volumeKeyDownTime = 0L
    private val LONG_PRESS_THRESHOLD = 800L // 800ms = 长按

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var recordingBlinkRunnable: Runnable? = null

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }

        setupWebView()
    }

    // ── WebView Setup ──────────────────────────────────────────────────────────

    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
            loadUrl("https://www.baidu.com")  // 默认加载百度，可修改为任意网址
        }
    }

    // ── Camera Setup ───────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
            } catch (e: Exception) {
                showToast("相机启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Volume Key Handling ────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeKeyDownTime = System.currentTimeMillis()

                    // 启动长按检测
                    longPressRunnable = Runnable {
                        if (!isRecording) startVideoRecording()
                    }
                    handler.postDelayed(longPressRunnable!!, LONG_PRESS_THRESHOLD)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val holdDuration = System.currentTimeMillis() - volumeKeyDownTime
                handler.removeCallbacks(longPressRunnable ?: return true)

                if (isRecording) {
                    // 正在录像时按音量键 → 停止录像
                    stopVideoRecording()
                } else if (holdDuration < LONG_PRESS_THRESHOLD) {
                    // 短按 → 拍照
                    takePhoto()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Photo Capture ──────────────────────────────────────────────────────────

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/隐形相机")
            }
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

    // ── Video Recording ────────────────────────────────────────────────────────

    private fun startVideoRecording() {
        val videoCapture = videoCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/隐形相机")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast("需要麦克风权限")
            return
        }

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
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
                        if (!event.hasError()) {
                            showToast("🎬 视频已保存")
                        } else {
                            showToast("❌ 录像失败: ${event.error}")
                        }
                    }
                    else -> {}
                }
            }
    }

    private fun stopVideoRecording() {
        recording?.stop()
        recording = null
    }

    // ── UI Feedback ────────────────────────────────────────────────────────────

    private fun showFlash() {
        binding.flashOverlay.visibility = View.VISIBLE
        binding.flashOverlay.alpha = 1f
        val anim = AlphaAnimation(1f, 0f).apply { duration = 200 }
        binding.flashOverlay.startAnimation(anim)
        handler.postDelayed({ binding.flashOverlay.visibility = View.GONE }, 200)
    }

    private fun showRecordingUI(show: Boolean) {
        if (show) {
            binding.recordingIndicator.visibility = View.VISIBLE
            binding.statusText.text = "🔴 录像中 按音量停"
            // 红点闪烁
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
            binding.statusText.text = "📷 VOL拍照"
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            binding.toastText.text = message
            binding.toastText.visibility = View.VISIBLE
            handler.postDelayed({ binding.toastText.visibility = View.GONE }, 2500)
        }
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能使用", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ── WebView back button support ────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isRecording) stopVideoRecording()
    }
}
