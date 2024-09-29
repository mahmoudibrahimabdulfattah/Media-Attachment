package com.mif.mediaattachment

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.single.PermissionListener
import com.mif.mediaattachment.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var context: Context

    private lateinit var attachmentAdapter: AttachmentAdapter
    private val files = mutableListOf<FileItem>()

    private lateinit var player: SimpleExoPlayer
    private var mediaPlayer: MediaPlayer? = null

    private val VIDEO_PICK_CODE = 100
    private val AUDIO_PICK_CODE = 101
    private val IMAGE_PICK_CODE = 102

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        context = this

        // Set up attachment upload and preview
        setupRecyclerView()
        setupAttachmentUpload()
    }

    private fun setupRecyclerView() {
        attachmentAdapter = AttachmentAdapter(
            files,
            onItemClick = { fileItem -> previewFile(fileItem) },
            onDeleteClick = { id -> removeAttachmentFile(id) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = attachmentAdapter
        }
    }

    private fun setupAttachmentUpload() {
        binding.btnSelectAttachment.setOnClickListener {
            showAttachmentTypeDialog()
        }
    }

    private fun showAttachmentTypeDialog() {
        val items = arrayOf("Photo", "Video", "Audio")
        AlertDialog.Builder(this)
            .setTitle("Choose file type")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> checkPermissionsAndOpenFilePicker(FileType.IMAGE)
                    1 -> checkPermissionsAndOpenFilePicker(FileType.VIDEO)
                    2 -> checkPermissionsAndOpenFilePicker(FileType.AUDIO)
                }
            }
            .show()
    }

    private fun checkPermissionsAndOpenFilePicker(fileType: FileType) {
        Dexter.withContext(this)
            .withPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            .withListener(object : PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse) {
                    openFilePicker(fileType)
                }

                override fun onPermissionDenied(response: PermissionDeniedResponse) {
                    Toast.makeText(this@MainActivity, "Permission Denied", Toast.LENGTH_SHORT).show()
                }

                override fun onPermissionRationaleShouldBeShown(
                    permission: com.karumi.dexter.listener.PermissionRequest,
                    token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            }).check()
    }

    private fun openFilePicker(fileType: FileType) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = when (fileType) {
            FileType.IMAGE -> "image/*"
            FileType.VIDEO -> "video/*"
            FileType.AUDIO -> "audio/*"
        }
        startActivityForResult(
            Intent.createChooser(intent, "Select ${fileType.name.toLowerCase()} file"),
            when (fileType) {
                FileType.IMAGE -> IMAGE_PICK_CODE
                FileType.VIDEO -> VIDEO_PICK_CODE
                FileType.AUDIO -> AUDIO_PICK_CODE
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                when (requestCode) {
                    IMAGE_PICK_CODE -> addFile(uri, FileType.IMAGE)
                    VIDEO_PICK_CODE -> compressAndAddVideo(uri)
                    AUDIO_PICK_CODE -> addFile(uri, FileType.AUDIO)
                }
            }
        }
    }

    private fun addFile(uri: Uri, fileType: FileType) {
        val fileName = getFileName(uri)
        val fileItem = FileItem(uri = uri, name = fileName, type = fileType)
        attachmentAdapter.addFile(fileItem)
    }

    private fun compressAndAddVideo(uri: Uri) {
        lifecycleScope.launch {
            binding.compressionProgress.visibility = View.VISIBLE
            binding.compressionText.visibility = View.VISIBLE

            val compressedUri = withContext(Dispatchers.IO) {
                compressVideo(uri)
            }

            binding.compressionProgress.visibility = View.GONE
            binding.compressionText.visibility = View.GONE

            compressedUri?.let {
                addFile(it, FileType.VIDEO)
            } ?: run {
                Toast.makeText(this@MainActivity, "Video compression failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun compressVideo(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("Compression", "Failed to open input stream")
                return@withContext null
            }

            val outputDir = getExternalFilesDir(null)
            val outputFile = File.createTempFile("compressed_", ".mp4", outputDir)
            val outputPath = outputFile.absolutePath

            val tempInputFile = File.createTempFile("input_", ".mp4", outputDir)
            tempInputFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            val command = "-y -i \"${tempInputFile.absolutePath}\" -c:v libx264 -preset ultrafast -crf 23 \"$outputPath\""

            val session = FFmpegKit.execute(command)

            tempInputFile.delete()

            if (ReturnCode.isSuccess(session.returnCode)) {
                Uri.fromFile(outputFile)
            } else {
                Log.e("FFmpeg", "FFmpeg failed with state: ${session.state} and rc: ${session.returnCode}. Error: ${session.failStackTrace}")
                null
            }
        } catch (e: Exception) {
            Log.e("Compression", "Error during compression: ${e.message}")
            null
        }
    }

    private fun removeAttachmentFile(id: String) {
        attachmentAdapter.removeFile(id)
    }

    private fun previewFile(fileItem: FileItem) {
        when (fileItem.type) {
            FileType.IMAGE -> showPhotoPreviewDialog(fileItem.uri)
            FileType.VIDEO -> showVideoPreviewDialog(fileItem.uri)
            FileType.AUDIO -> showAudioPreviewDialog(fileItem.uri)
        }
    }

    //PreviewVideo
    private fun showPhotoPreviewDialog(photoUri: Uri) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_photo_preview, null)
        val imageView = dialogView.findViewById<SubsamplingScaleImageView>(R.id.imageView)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnDone)

        imageView.setImage(ImageSource.uri(photoUri))

        val dialog = AlertDialog.Builder(this, R.style.RoundedCornersDialog)
            .setView(dialogView)
            .create()

        btnDone.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showVideoPreviewDialog(videoUri: Uri) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_video_preview, null)
        val playerView = dialogView.findViewById<PlayerView>(R.id.playerView)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnDone)

        val dialog = AlertDialog.Builder(this, R.style.RoundedCornersDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        player = SimpleExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(videoUri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        btnDone.setOnClickListener {
            player.stop()
            player.release()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            player.stop()
            player.release()
        }

        dialog.show()
    }

    //PreviewAudio
    @SuppressLint("ClickableViewAccessibility")
    private fun showAudioPreviewDialog(audioUri: Uri) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_audio_preview, null)
        val btnPlay = dialogView.findViewById<ImageButton>(R.id.btnPlay)
        val btnPause = dialogView.findViewById<ImageButton>(R.id.btnPause)
        val audioWaveform = dialogView.findViewById<AudioWaveformView>(R.id.audioWaveform)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnDone)

        var job: Job? = null

        tvFileName.text = getFileName(audioUri)
        mediaPlayer = MediaPlayer.create(this, audioUri)

        val fixedAmplitudes = generateFixedWaveform(120) // Increase number of spikes
        audioWaveform.setWaveform(fixedAmplitudes)

        audioWaveform.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val touchProgress = event.x / audioWaveform.width
                    val newPosition = (touchProgress * (mediaPlayer?.duration ?: 0)).toInt()
                    mediaPlayer?.seekTo(newPosition)
                    audioWaveform.setProgress(newPosition.toLong(), (mediaPlayer?.duration ?: 1).toLong())
                    true
                }
                else -> false
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.RoundedCornersDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        mediaPlayer = MediaPlayer.create(this, audioUri)

        btnPlay.setOnClickListener {
            mediaPlayer?.start()
            btnPlay.visibility = View.GONE
            btnPause.visibility = View.VISIBLE

            job = lifecycleScope.launch {
                while (isActive && mediaPlayer?.isPlaying == true) {
                    val currentPosition = mediaPlayer?.currentPosition ?: 0
                    val duration = mediaPlayer?.duration ?: 1
                    audioWaveform.setProgress(currentPosition.toLong(), duration.toLong())
                    delay(16) // تحديث ~60 مرة في الثانية للحصول على حركة سلسة
                }
            }
        }

        btnPause.setOnClickListener {
            mediaPlayer?.pause()
            btnPause.visibility = View.GONE
            btnPlay.visibility = View.VISIBLE
            job?.cancel()
        }

        btnDone.setOnClickListener {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            mediaPlayer?.release()
            mediaPlayer = null
        }

        // Update Waveform every second
        lifecycleScope.launch {
            while (mediaPlayer != null) {
                if (mediaPlayer?.isPlaying == true) {
                    val currentPosition = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    val duration = mediaPlayer?.duration?.toLong() ?: 1L
                    audioWaveform.setProgress(currentPosition, duration)
                }
                delay(16) // تحديث ~60 مرة في الثانية للحصول على حركة أكثر سلاسة
            }
        }

        dialog.show()
    }

    private fun generateFixedWaveform(size: Int): FloatArray {
        val random = Random(System.currentTimeMillis())
        return FloatArray(size) { index ->
            val baseHeight = sin(index * (6 * Math.PI / size)).absoluteValue.toFloat()
            val randomFactor = random.nextFloat() * 0.5f + 0.5f
            val height = (baseHeight * randomFactor).coerceIn(0.1f, 1f)

            if (random.nextFloat() > 0.85f) {
                (height * 1.3f).coerceAtMost(1f)
            } else {
                height
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result ?: "Unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        mediaPlayer?.release()
    }
}