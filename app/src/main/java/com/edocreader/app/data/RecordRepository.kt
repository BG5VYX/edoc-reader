package com.edocreader.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于本地 JSON 文件的记录仓库。
 *
 * 选型说明：本应用的数据量级是"一次读取一条、几百条封顶"，用 JSON 文件即可满足，
 * 且便于用户直接取出查看与二次处理；相比引入 Room + 注解处理器，构建更轻、可审计性更强。
 *
 * 目录结构（均位于应用私有目录，卸载即清除，无需存储权限）：
 *   files/records/index.json       —— 全部记录（结构化字段）
 *   files/records/faces/<id>.jpg   —— 面部图像
 *   files/records/exports/         —— 导出产物
 */
class RecordRepository(context: Context) {

    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    private val recordsDir: File get() = File(appContext.filesDir, "records").apply { mkdirs() }
    private val facesDir: File get() = File(recordsDir, "faces").apply { mkdirs() }
    val exportsDir: File get() = File(recordsDir, "exports").apply { mkdirs() }

    private val indexFile: File get() = File(recordsDir, "index.json")

    private val lock = Any()

    // ---------------------------------------------------------------- 读取

    suspend fun listAll(): List<DocRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) { readIndex() }.sortedByDescending { it.createdAt }
    }

    suspend fun get(id: String): DocRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) { readIndex().firstOrNull { it.id == id } }
    }

    // ---------------------------------------------------------------- 写入

    suspend fun save(record: DocRecord, faceImage: ByteArray?): DocRecord = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val all = readIndex().toMutableList()
            var stored = record

            if (faceImage != null && faceImage.isNotEmpty()) {
                val fileName = "face_${record.id}.jpg"
                File(facesDir, fileName).writeBytes(faceImage)
                stored = record.copy(
                    faceImageFileName = fileName,
                    faceImageBytes = faceImage.size
                )
            }

            all.removeAll { it.id == stored.id }
            all.add(0, stored)
            writeIndex(all)
            Log.i(TAG, "save: id=${stored.id}, total=${all.size}")
            stored
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val all = readIndex().toMutableList()
            val target = all.firstOrNull { it.id == id }
            if (target != null && target.faceImageFileName.isNotBlank()) {
                File(facesDir, target.faceImageFileName).delete()
            }
            all.removeAll { it.id == id }
            writeIndex(all)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            facesDir.listFiles()?.forEach { it.delete() }
            writeIndex(emptyList())
        }
    }

    /** 读取面部图像文件。 */
    fun faceImageFile(record: DocRecord): File? {
        if (record.faceImageFileName.isBlank()) return null
        val f = File(facesDir, record.faceImageFileName)
        return if (f.exists()) f else null
    }

    // ---------------------------------------------------------------- 内部

    private fun readIndex(): List<DocRecord> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<DocRecord>>() {}.type
            val list: List<DocRecord>? = gson.fromJson(indexFile.readText(Charsets.UTF_8), type)
            list ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "readIndex failed", e)
            // 损坏时不静默丢弃：备份后返回空列表
            runCatching { indexFile.renameTo(File(recordsDir, "index.corrupt.${System.currentTimeMillis()}.json")) }
            emptyList()
        }
    }

    private fun writeIndex(records: List<DocRecord>) {
        val tmp = File(recordsDir, "index.json.tmp")
        tmp.writeText(gson.toJson(records), Charsets.UTF_8)
        if (indexFile.exists()) indexFile.delete()
        tmp.renameTo(indexFile)
    }

    companion object {
        private const val TAG = "RecordRepository"
    }
}
