package com.parin.aircam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Gesture map (hold ~1 second):
 *  Open_Palm   -> photo (3 s countdown)
 *  Victory     -> start / stop video
 *  Thumb_Up    -> switch front/back camera
 *  Pointing_Up -> zoom in (keeps zooming while held)
 *  Closed_Fist -> zoom out (keeps zooming while held)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var zoomLinear = 0f
    private var counting = false

    private var recognizer: GestureRecognizer? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentGesture = "None"
    private var gestureSince = 0L
    private var lastAction = 0L

    private val holdMs = 1000L
    private val cooldownMs = 2500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        countdownText = findViewById(R.id.countdownText)

        findViewById<Button>(R.id.btnPhoto).setOnClickListener { startPhotoCountdown() }
        findViewById<Button>(R.id.btnVideo).setOnClickListener { toggleVideo() }
        findViewById<Button>(R.id.btnFlip).setOnClickListener { flipCamera() }

        try {
            val base = BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
            val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build()
            recognizer = GestureRecognizer.createFromOptions(this, opts)
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
            statusText.text = "Gesture model missing - buttons only"
        }

        if (hasPermissions()) startCamera()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 10
        )
    }

    private fun hasPermissions() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasPermissions()) startCamera()
        else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindUseCases(videoMode = false)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Photo mode: Preview + ImageCapture + Analysis. Video mode: Preview + VideoCapture + Analysis. */
    private fun bindUseCases(videoMode: Boolean) {
        val p = provider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().also { it.setAnalyzer(executor, ::analyze) }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        p.unbindAll()
        camera = if (videoMode) {
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            imageCapture = null
            p.bindToLifecycle(this, selector, preview, analysis, videoCapture!!)
        } else {
            imageCapture = ImageCapture.Builder().build()
            videoCapture = null
            p.bindToLifecycle(this, selector, preview, analysis, imageCapture!!)
        }
        camera?.cameraControl?.setLinearZoom(zoomLinear)
    }

    // ---------- Gesture recognition ----------

    private fun analyze(proxy: ImageProxy) {
        try {
            val rec = recognizer ?: return
            val bmp = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val result = rec.recognize(BitmapImageBuilder(rotated).build())
            val top = result.gestures().firstOrNull()?.firstOrNull()
            val name = if (top != null && top.score() > 0.6f) top.categoryName() else "None"
            handleGesture(name)
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
        } finally {
            proxy.close()
        }
    }

    private fun handleGesture(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (name != currentGesture) {
            currentGesture = name
            gestureSince = now
            runOnUiThread { statusText.text = if (name == "None") "Show a gesture" else "Seeing: $name" }
            return
        }
        // Continuous zoom
        if (name == "Pointing_Up" || name == "Closed_Fist") {
            val step = if (name == "Pointing_Up") 0.03f else -0.03f
            zoomLinear = (zoomLinear + step).coerceIn(0f, 1f)
            runOnUiThread { camera?.cameraControl?.setLinearZoom(zoomLinear) }
            return
        }
        // One-shot actions need a hold + cooldown
        if (now - gestureSince < holdMs || now - lastAction < cooldownMs) return
        when (name) {
            "Open_Palm" -> { lastAction = now; runOnUiThread { startPhotoCountdown() } }
            "Victory" -> { lastAction = now; runOnUiThread { toggleVideo() } }
            "Thumb_Up" -> { lastAction = now; runOnUiThread { flipCamera() } }
        }
    }

    // ---------- Camera actions ----------

    private fun startPhotoCountdown() {
        if (counting || recording != null) return
        counting = true
        object : CountDownTimer(3000, 1000) {
            override fun onTick(ms: Long) { countdownText.text = ((ms / 1000) + 1).toString() }
            override fun onFinish() { countdownText.text = ""; counting = false; takePhoto() }
        }.start()
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "AirCam_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AirCam")
        }
        val out = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        ic.takePicture(out, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    statusText.text = "Photo saved"
                }
                override fun onError(e: ImageCaptureException) {
                    statusText.text = "Photo failed: ${e.message}"
                }
            })
    }

    private fun toggleVideo() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        bindUseCases(videoMode = true)
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "AirCam_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/AirCam")
        }
        val opts = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, opts)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> statusText.text = "Recording... (Victory to stop)"
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    statusText.text = if (event.hasError()) "Video error" else "Video saved"
                    bindUseCases(videoMode = false)
                }
            }
        }
    }

    private fun flipCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        zoomLinear = 0f
        bindUseCases(videoMode = false)
    }


    // ---------- S Pen (button + hover/touch) ----------
    // Click = photo, double click = video, long press = flip,
    // hold button + move pen up/down = zoom. Pen must be near the screen.

    private val penHandler = Handler(Looper.getMainLooper())
    private var penButtonDown = false
    private var penDownAt = 0L
    private var penLastY = 0f
    private var penTravel = 0f
    private var lastPenClickAt = 0L
    private var pendingSingle: Runnable? = null

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        handlePen(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handlePen(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handlePen(ev: MotionEvent) {
        if (ev.pointerCount == 0 || ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        val pressed = (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val now = SystemClock.elapsedRealtime()

        if (pressed && !penButtonDown) {
            penButtonDown = true
            penDownAt = now
            penLastY = ev.y
            penTravel = 0f
        } else if (pressed) {
            val dy = penLastY - ev.y          // pen moving up = zoom in
            penLastY = ev.y
            penTravel += kotlin.math.abs(dy)
            if (penTravel > 40f) {
                zoomLinear = (zoomLinear + dy / 1500f).coerceIn(0f, 1f)
                camera?.cameraControl?.setLinearZoom(zoomLinear)
                statusText.text = "S Pen zoom ${(zoomLinear * 100).toInt()}%"
            }
        } else if (!pressed && penButtonDown) {
            penButtonDown = false
            val held = now - penDownAt
            when {
                penTravel > 40f -> {}                         // was zooming
                held > 700 -> { statusText.text = "S Pen: flip"; flipCamera() }
                now - lastPenClickAt < 400 -> {               // double click
                    pendingSingle?.let { penHandler.removeCallbacks(it) }
                    lastPenClickAt = 0
                    statusText.text = "S Pen: video"
                    toggleVideo()
                }
                else -> {                                     // single click (wait for a 2nd)
                    lastPenClickAt = now
                    val r = Runnable { statusText.text = "S Pen: photo"; startPhotoCountdown() }
                    pendingSingle = r
                    penHandler.postDelayed(r, 400)
                }
            }
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.close()
        executor.shutdown()
    }

    companion object { private const val TAG = "AirCam" }
}
