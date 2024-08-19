package com.mif.mediaattachment

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var context: Context

    private lateinit var player: SimpleExoPlayer
    private var mediaPlayer: MediaPlayer? = null

    private val VIDEO_PICK_CODE = 100
    private val AUDIO_PICK_CODE = 101
    private val IMAGE_PICK_CODE = 102
    private val AUDIO_PERMISSION_REQUEST_CODE = 1001
    private val VIDEO_PERMISSION_REQUEST_CODE = 1002
    private val IMAGE_PERMISSION_REQUEST_CODE = 1003

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        context = this

        // Set up attachment upload and preview
        setupAttachmentUpload()
        setupPhotoPreview()
        setupVideoPreview()
        setupAudioPreview()
    }

    private fun setupAttachmentUpload() {
        binding.btnSelectAttachment.setOnClickListener {
            showAttachmentTypeDialog()
        }

        binding.ivDeleteImage.setOnClickListener {
            binding.llImageAttached.visibility = View.GONE
            deletePhotoUri()
        }

        binding.ivDeleteVideo.setOnClickListener {
            binding.llVideoAttached.visibility = View.GONE
            deleteVideoUri()
        }

        binding.ivDeleteRecord.setOnClickListener {
            binding.llRecordAttached.visibility = View.GONE
            deleteAudioUri()
        }
    }

    private fun showAttachmentTypeDialog() {
        val items = arrayOf("Photo", "Video", "Audio")
        AlertDialog.Builder(context)
            .setTitle("Choose file type")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> checkPermissionsAndOpenFilePicker(isAudio = false, isImage = true)
                    1 -> checkPermissionsAndOpenFilePicker(isAudio = false, isImage = false)
                    2 -> checkPermissionsAndOpenFilePicker(isAudio = true)
                }
            }
            .show()
    }

    private fun checkPermissionsAndOpenFilePicker(isAudio: Boolean, isImage: Boolean = false) {
        Dexter.withContext(context)
            .withPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            .withListener(object : PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse) {
                    when {
                        isAudio -> openAudioChooser()
                        isImage -> openImageChooser()
                        else -> openVideoChooser()
                    }
                }

                override fun onPermissionDenied(response: PermissionDeniedResponse) {
                    Toast.makeText(context, "Sorry, Permission Denied", Toast.LENGTH_SHORT).show()
                }

                override fun onPermissionRationaleShouldBeShown(
                    permission: com.karumi.dexter.listener.PermissionRequest,
                    token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            }).check()
    }

    private fun openImageChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(Intent.createChooser(intent, "اختار صورة"), IMAGE_PICK_CODE)
    }

    private fun openVideoChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
        }
        startActivityForResult(Intent.createChooser(intent, "اختار فيديو"), VIDEO_PICK_CODE)
    }

    private fun openAudioChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "audio/*"
        startActivityForResult(intent, AUDIO_PICK_CODE)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                IMAGE_PICK_CODE -> {
                    data?.data?.let { uri ->
                        saveImageUri(uri)
                        displayImageInfo(uri)
                    }
                }
                VIDEO_PICK_CODE -> {
                    data?.data?.let { uri ->
                        checkVideoPermission(uri)
                    }
                }
                AUDIO_PICK_CODE -> {
                    data?.data?.let { uri ->
                        saveAudioUri(uri)
                        displayAudioInfo(uri)
                    }
                }
            }
        }
    }

    private fun saveImageUri(uri: Uri){
        try {
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("photo_uri", uri.toString())
                apply()
            }
        } catch (e: Exception) {
            Log.e("SavePhotoError", "Error saving photo URI: ${e.message}")
        }
    }

    private fun displayImageInfo(uri: Uri) {
        binding.llImageAttached.visibility = View.VISIBLE
        binding.btnPreviewImage.text = getFileName(uri)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkPhotoPermission(photoUri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(context, permission)
            != PackageManager.PERMISSION_GRANTED) {
            // Save the photoUri temporarily
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("temp_photo_uri", photoUri.toString())
                apply()
            }

            requestPermissions(
                arrayOf(permission),
                IMAGE_PERMISSION_REQUEST_CODE
            )
        } else {
            // Permission is already granted, show the photo preview dialog
            showPhotoPreviewDialog(photoUri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkVideoPermission(videoUri: Uri) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            // نحفظ الـ videoUri مؤقتاً
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("temp_video_uri", videoUri.toString())
                apply()
            }

            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                VIDEO_PERMISSION_REQUEST_CODE
            )
        } else {
            // الإذن موجود، نعرض الفيديو على طول
            compressAndSaveVideo(videoUri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkAudioPermission(audioUri: Uri) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            // Save the audioUri temporarily
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("temp_audio_uri", audioUri.toString())
                apply()
            }

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST_CODE
            )
        } else {
            // Permission is already granted, show the audio preview dialog
            showAudioPreviewDialog(audioUri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            IMAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val sharedPref = getPreferences(Context.MODE_PRIVATE)
                    val photoUriString = sharedPref.getString("temp_photo_uri", null)
                    if (photoUriString != null) {
                        val photoUri = Uri.parse(photoUriString)
                        showPhotoPreviewDialog(photoUri)

                        with(sharedPref.edit()) {
                            remove("temp_photo_uri")
                            apply()
                        }
                    }
                } else {
                    showPermissionExplanationDialog()
                }
            }
            VIDEO_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val sharedPref = getPreferences(Context.MODE_PRIVATE)
                    val videoUriString = sharedPref.getString("temp_video_uri", null)
                    if (videoUriString != null) {
                        val videoUri = Uri.parse(videoUriString)
                        compressAndSaveVideo(videoUri)

                        with(sharedPref.edit()) {
                            remove("temp_video_uri")
                            apply()
                        }
                    }
                } else {
                    Toast.makeText(context, "مفيش إذن لقراية الفيديو", Toast.LENGTH_SHORT).show()
                }
            }
            AUDIO_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, retrieve the temporary URI and show the preview
                    val sharedPref = getPreferences(Context.MODE_PRIVATE)
                    val audioUriString = sharedPref.getString("temp_audio_uri", null)
                    if (audioUriString != null) {
                        val audioUri = Uri.parse(audioUriString)
                        showAudioPreviewDialog(audioUri)

                        // Clear the temporary URI
                        with(sharedPref.edit()) {
                            remove("temp_audio_uri")
                            apply()
                        }
                    }
                } else {
                    // Permission denied
                    Toast.makeText(context, "Permission denied to record audio", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(context)
            .setTitle("الإذن مطلوب")
            .setMessage("نحتاج إلى إذن الوصول إلى الصور لعرض الصورة التي اخترتها. هل تريد منح الإذن؟")
            .setPositiveButton("نعم") { _, _ ->
                // طلب الإذن مرة أخرى
                checkPhotoPermission(Uri.parse(getPreferences(Context.MODE_PRIVATE).getString("temp_photo_uri", null)))
            }
            .setNegativeButton("لا") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(context, "لا يمكن عرض الصورة بدون إذن", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    //Preview Photo
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupPhotoPreview() {
        binding.btnPreviewImage.setOnClickListener {
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            val photoUriString = sharedPref.getString("photo_uri", null)
            if (photoUriString != null) {
                val photoUri = Uri.parse(photoUriString)
                checkPhotoPermission(photoUri)
            } else {
                Toast.makeText(context, "No photo file selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPhotoPreviewDialog(photoUri: Uri) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.layout_photo_preview, null)
        val imageView = dialogView.findViewById<SubsamplingScaleImageView>(R.id.imageView)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnDone)

        // Load the image into the SubsamplingScaleImageView
        imageView.setImage(ImageSource.uri(photoUri))

        val dialog = AlertDialog.Builder(context, R.style.RoundedCornersDialog)
            .setView(dialogView)
            .create()

        btnDone.setOnClickListener {
            dialog.dismiss()
        }

        // Set the dialog window to be almost full screen
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun deletePhotoUri() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            remove("photo_uri")
            apply()
        }
    }

    //Preview Video
    private fun setupVideoPreview() {
        binding.btnPreviewVideo.setOnClickListener {
            playCompressedVideo()
        }
    }
    private fun playCompressedVideo() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val videoUriString = sharedPref.getString("video_uri", null)

        if (videoUriString != null) {
            val videoUri = Uri.parse(videoUriString)
            showVideoPreviewDialog(videoUri)
        } else {
            Toast.makeText(context, "No video available", Toast.LENGTH_SHORT).show()
        }
    }
    private fun showVideoPreviewDialog(videoUri: Uri) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.layout_video_preview, null)
        val playerView = dialogView.findViewById<PlayerView>(R.id.playerView)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnDone)

        val dialog = AlertDialog.Builder(context, R.style.RoundedCornersDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        player = SimpleExoPlayer.Builder(context).build()
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
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }

        dialog.show()
    }

    private fun compressAndSaveVideo(uri: Uri) {
        lifecycleScope.launch {
            binding.compressionProgress.visibility = View.VISIBLE
            binding.compressionText.visibility = View.VISIBLE
            binding.btnSelectAttachment.isEnabled = false

            val compressedUri = withContext(Dispatchers.IO) {
                try {
                    val inputStream = getInputStreamFromUri(uri)
                    if (inputStream == null) {
                        Log.e("CompressionError", "Failed to open input stream")
                        return@withContext null
                    }

                    val outputDir = context.getExternalFilesDir(null)
                    val outputFile = File.createTempFile("compressed_", ".mp4", outputDir)
                    val outputPath = outputFile.absolutePath

                    // كتابة الـ InputStream إلى ملف مؤقت
                    val tempInputFile = File.createTempFile("input_", ".mp4", outputDir)
                    tempInputFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }

                    val command = "-y -i \"${tempInputFile.absolutePath}\" -c:v libx264 -preset ultrafast -crf 23 \"$outputPath\""

                    Log.d("VideoCompression", "FFmpeg Command: $command")

                    val session = FFmpegKit.execute(command)

                    tempInputFile.delete() // حذف الملف المؤقت

                    if (ReturnCode.isSuccess(session.returnCode)) {
                        Uri.fromFile(outputFile)
                    } else {
                        Log.e("FFmpegError", "FFmpeg failed with state: ${session.state} and rc: ${session.returnCode}. Error: ${session.failStackTrace}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e("CompressionError", "Error during compression: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }

            binding.compressionProgress.visibility = View.GONE
            binding.compressionText.visibility = View.GONE
            binding.btnSelectAttachment.isEnabled = true

            compressedUri?.let {
                saveVideoUri(it)
                displayVideoInfo(it)
            } ?: run {
                Toast.makeText(context, "Compression failed. Check logs for details.", Toast.LENGTH_LONG).show()
                Log.e("CompressionError", "Compression failed. Uri: $uri")
            }
        }
    }

    private fun getInputStreamFromUri(uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e("GetInputStreamError", "Error opening input stream: ${e.message}")
            null
        }
    }

    private fun saveVideoUri(uri: Uri) {
        try {
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("video_uri", uri.toString())
                apply()
            }
        } catch (e: Exception) {
            Log.e("SaveVideoError", "Error saving video URI: ${e.message}")
        }
    }
    private fun deleteVideoUri() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            remove("video_uri")
            apply()
        }
    }
    private fun displayVideoInfo(uri: Uri) {
        binding.llVideoAttached.visibility = View.VISIBLE
        binding.btnPreviewVideo.text = getFileName(uri)
    }

    //Preview Audio
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupAudioPreview() {
        binding.btnPreviewRecord.setOnClickListener {
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            val audioUriString = sharedPref.getString("audio_uri", null)
            if (audioUriString != null) {
                val audioUri = Uri.parse(audioUriString)
                checkAudioPermission(audioUri)
            } else {
                Toast.makeText(context, "No audio file selected", Toast.LENGTH_SHORT).show()
            }
        }
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
    @SuppressLint("ClickableViewAccessibility")
    private fun showAudioPreviewDialog(audioUri: Uri) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.layout_audio_preview, null)
        val btnPlay = dialogView.findViewById<ImageButton>(R.id.btnPlay)
        val btnPause = dialogView.findViewById<ImageButton>(R.id.btnPause)
        val audioWaveform = dialogView.findViewById<AudioWaveformView>(R.id.audioWaveform)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnDone)

        tvFileName.text = getFileName(audioUri)
        mediaPlayer = MediaPlayer.create(context, audioUri)

        val fixedAmplitudes = generateFixedWaveform(120) // Increase number of spikes
        audioWaveform.setWaveform(fixedAmplitudes)

        audioWaveform.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val touchProgress = event.x / audioWaveform.width
                    audioWaveform.setProgress(touchProgress)
                    val newPosition = (touchProgress * (mediaPlayer?.duration ?: 0)).toInt()
                    mediaPlayer?.seekTo(newPosition)
                    true
                }
                else -> false
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.RoundedCornersDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        mediaPlayer = MediaPlayer.create(context, audioUri)

        btnPlay.setOnClickListener {
            mediaPlayer?.start()
            btnPlay.visibility = View.GONE
            btnPause.visibility = View.VISIBLE
        }

        btnPause.setOnClickListener {
            mediaPlayer?.pause()
            btnPause.visibility = View.GONE
            btnPlay.visibility = View.VISIBLE
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
            while (mediaPlayer != null && mediaPlayer?.isPlaying == true) {
                val progress = mediaPlayer?.currentPosition?.toFloat() ?: 0f
                val duration = mediaPlayer?.duration?.toFloat() ?: 1f
                audioWaveform.setProgress(progress / duration)
                delay(100) // تحديث كل 100 مللي ثانية للحصول على حركة أكثر سلاسة
            }
        }

        dialog.show()
    }
    private fun saveAudioUri(uri: Uri) {
        try {
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("audio_uri", uri.toString())
                apply()
            }
        } catch (e: Exception) {
            Log.e("SaveAudioError", "Error saving audio URI: ${e.message}")
        }
    }
    private fun deleteAudioUri() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            remove("audio_uri")
            apply()
        }
    }
    private fun displayAudioInfo(uri: Uri) {
        binding.llRecordAttached.visibility = View.VISIBLE
        binding.btnPreviewRecord.text = getFileName(uri)
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
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
}