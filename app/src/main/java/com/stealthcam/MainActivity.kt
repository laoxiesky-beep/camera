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

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var volumeKeyDownTime = 0L
    private val LONG_PRESS_MS = 700L
    private var recordingBlinkRunnable: Runnable? = null

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

        // 透明窗口
        window.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
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

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
            } catch (e: Exception) {
                showToast("相机启动失败")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── 前台服务（息屏保活）────────────────────────────────────────────────────

    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java)
        startForegroundService(intent)
    }

    // ── 音量键：长按开始录像，短按停止录像 ────────────────────────────────────

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
            // 安全移除长按回调，不用 ?: return
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null

            if (isRecording && held < LONG_PRESS_MS) {
                // 录像中短按 → 停止
                stopVideoRecording()
            }
            // 未录像时短按不触发任何动作（长按已在 DOWN 阶段处理）
            return true
        }
    }
    return super.dispatchKeyEvent(event)
}

    // ── 录像 ──────────────────────────────────────────────────────────────────

    private fun startVideoRecording() {
        val videoCapture = videoCapture ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast("需要麦克风权限")
            return
        }

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
                        else showToast("录像失败")
                    }
                    else -> {}
                }
            }
    }

    private fun stopVideoRecording() {
        recording?.stop()
        recording = null
    }

    // ── UI ────────────────────────────────────────────────────────────────────

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
