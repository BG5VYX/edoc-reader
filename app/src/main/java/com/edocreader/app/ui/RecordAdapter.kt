package com.edocreader.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.edocreader.app.App
import com.edocreader.app.R
import com.edocreader.app.data.DocRecord
import com.edocreader.app.databinding.ItemRecordBinding

/** 记录列表适配器。 */
class RecordAdapter(
    private val onClick: (DocRecord) -> Unit
) : ListAdapter<DocRecord, RecordAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DocRecord) {
            binding.tvTitle.text = record.displayTitle
            binding.tvSubtitle.text = record.displaySubtitle
            binding.tvTime.text = record.createdAtText

            binding.tvChipBadge.text = if (record.chipRead) "芯片已读" else "仅 OCR"
            binding.tvChipBadge.setBackgroundResource(
                if (record.chipRead) R.drawable.bg_badge_ok else R.drawable.bg_badge_neutral
            )

            val paOk = record.passiveAuthAllDgMatch
            binding.tvPaBadge.text = when (paOk) {
                true -> "摘要一致"
                false -> "摘要异常"
                null -> "未做被动认证"
            }
            binding.tvPaBadge.setBackgroundResource(
                when (paOk) {
                    true -> R.drawable.bg_badge_ok
                    false -> R.drawable.bg_badge_warn
                    null -> R.drawable.bg_badge_neutral
                }
            )

            // 面部图像
            val faceFile = App.instance.repository.faceImageFile(record)
            if (faceFile != null && faceFile.exists()) {
                val bmp = BitmapFactory.decodeFile(faceFile.absolutePath)
                if (bmp != null) {
                    binding.ivFace.setImageBitmap(bmp)
                } else {
                    binding.ivFace.setImageResource(R.drawable.ic_face_placeholder)
                }
            } else {
                binding.ivFace.setImageResource(R.drawable.ic_face_placeholder)
            }

            binding.root.setOnClickListener { onClick(record) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DocRecord>() {
            override fun areItemsTheSame(oldItem: DocRecord, newItem: DocRecord) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DocRecord, newItem: DocRecord) = oldItem == newItem
        }
    }
}
