package com.mif.mediaattachment

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatButton
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class AttachmentAdapter(
    private val files: MutableList<FileItem>,
    private val onItemClick: (FileItem) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<AttachmentAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val previewButton: AppCompatButton = view.findViewById(R.id.btn_preview)
        val deleteImage: ImageView = view.findViewById(R.id.iv_deleteImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val fileItem = files[position]

        holder.previewButton.text = fileItem.name

        // Set the appropriate icon based on file type
        val startDrawable = when (fileItem.type) {
            FileType.IMAGE -> R.drawable.gallery
            FileType.VIDEO -> R.drawable.video
            FileType.AUDIO -> R.drawable.record
        }
        holder.previewButton.setCompoundDrawablesWithIntrinsicBounds(startDrawable, 0, R.drawable.preview, 0)

        holder.previewButton.setOnClickListener { onItemClick(fileItem) }
        holder.deleteImage.setOnClickListener {
            onDeleteClick(fileItem.id)
        }
    }

    override fun getItemCount() = files.size

    fun addFile(file: FileItem) {
        files.add(file)
        notifyItemInserted(files.size - 1)
    }

    fun removeFile(id: String) {
        val index = files.indexOfFirst { it.id == id }
        if (index != -1) {
            files.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, files.size)
        }
    }
}

data class FileItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val type: FileType
)

enum class FileType {
    IMAGE, VIDEO, AUDIO
}