package com.edocreader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.edocreader.app.App
import com.edocreader.app.R
import com.edocreader.app.data.DocRecord
import com.edocreader.app.databinding.ItemRecordBinding
import com.edocreader.app.jp2.FaceImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // 芯片里的照片可能是 JPEG 2000，系统解码器打不开，必须走 FaceImageDecoder。
            // 解码在后台线程做并缓存，避免列表滚动时卡顿。
            binding.ivFace.tag = record.id
            val faceFile = App.instance.repository.faceImageFile(record)
            if (faceFile == null || !faceFile.exists()) {
                binding.ivFace.setImageResource(R.drawable.ic_face_placeholder)
            } else {
                val cached = FaceThumbCache.get(record.id)
                if (cached != null) {
                    binding.ivFace.setImageBitmap(cached)
                } else {
                    binding.ivFace.setImageResource(R.drawable.ic_face_placeholder)
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            val bytes = runCatching { faceFile.readBytes() }.getOrNull() ?: return@withContext null
                            when (val o = FaceImageDecoder.decode(bytes, record.faceImageFormat)) {
                                is FaceImageDecoder.Outcome.Success -> o.bitmap
                                else -> null
                            }
                        }
                        if (bmp != null) {
                            FaceThumbCache.put(record.id, bmp)
                            // 视图可能已被复用给别的记录，确认后再贴图
                            if (binding.ivFace.tag == record.id) {
                                binding.ivFace.setImageBitmap(bmp)
                            }
                        }
                    }
                }
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

/**
 * 列表头像解码用的作用域与内存缓存。
 *
 * JPEG 2000 解码一次约需数百毫秒，列表滚动时反复解会明显卡顿，
 * 所以解码结果按记录 ID 缓存；缓存上限按条目数控制。
 */
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private object FaceThumbCache {
    private const val MAX_ENTRIES = 32

    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun get(id: String): Bitmap? = cache.get(id)

    fun put(id: String, bitmap: Bitmap) {
        cache.put(id, bitmap)
    }
}
