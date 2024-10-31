package com.example.eac3

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var playerView: PlayerView
    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var cameraExecutor: ExecutorService
    private var exoPlayer: ExoPlayer? = null
    private var activeRecording: Recording? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        playerView = findViewById(R.id.playerView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkPermissions()

        findViewById<Button>(R.id.btnCapturePhoto).setOnClickListener { capturePhoto() }
        findViewById<Button>(R.id.btnRecordVideo).setOnClickListener { recordVideo() }
    }

    private fun checkPermissions() {
        // Para Android 10 y superior, usar los nuevos permisos
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            setupCamera()
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest)
        }
    }


    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.forEach { (permission, isGranted) ->
                if (!isGranted) {
                    Toast.makeText(this, "Permission $permission denied.", Toast.LENGTH_SHORT).show()
                }
            }

            if (permissions.all { it.value }) {
                setupCamera()
            } else {
                Toast.makeText(this, "Permissions are required to use the app.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, videoCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to bind camera use cases", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to capture photo", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }


    private fun recordVideo() {
        try {
            if (isRecording) {
                activeRecording?.stop()
                isRecording = false
            } else {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                }

                val outputOptions = MediaStoreOutputOptions.Builder(
                    contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(contentValues).build()

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    activeRecording = videoCapture.output
                        .prepareRecording(this, outputOptions)
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                            when (recordEvent) {
                                is VideoRecordEvent.Start -> {
                                    Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                                }
                                is VideoRecordEvent.Finalize -> {
                                    if (!recordEvent.hasError()) {
                                        Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
                                        playVideo(recordEvent.outputResults.outputUri.toFile())
                                    } else {
                                        Toast.makeText(this, "Error recording video: ${recordEvent.error}", Toast.LENGTH_SHORT).show()
                                    }
                                    // Reiniciar el estado para permitir una nueva grabación
                                    activeRecording = null
                                    isRecording = false
                                }
                            }
                        }
                    isRecording = true
                } else {
                    Toast.makeText(this, "Audio permission is required to record video", Toast.LENGTH_SHORT).show()
                    requestPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            }
        } catch (e: Exception) {
            // Captura cualquier excepción inesperada
            Toast.makeText(this, "An error occurred: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
            isRecording = false
            activeRecording = null
        }
    }


    private fun playVideo(file: File) {
        if (file.exists()) {
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                val mediaItem = MediaItem.fromUri(file.toUri())
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
            playerView.player = exoPlayer
        } else {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        exoPlayer?.release()
    }
}
