package com.edocreader.app.data

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 批量导出。
 *
 * 支持三种产物：
 *  1. `records_<时间戳>.json` —— 全量结构化记录（含被动认证明细、DG11 明细、过程日志）
 *  2. `records_<时间戳>.csv`  —— 适合直接导入 Excel / WPS 的扁平表
 *  3. `edoc_export_<时间戳>.zip` —— 打包 JSON + CSV + 全部面部图像 + 说明文件
 *
 * 文件先写入应用私有目录，再通过系统文件选择器（SAF）由用户决定最终保存位置，
 * 全程不需要存储权限。
 */
class ExportManager(
    private val context: Context,
    private val repository: RecordRepository
) {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    data class ExportFile(val file: File, val mimeType: String, val description: String)

    // ---------------------------------------------------------------- JSON

    suspend fun exportJson(): ExportFile = withContext(Dispatchers.IO) {
        val records = repository.listAll()
        val file = File(repository.exportsDir, "records_${timestamp()}.json")
        file.writeText(gson.toJson(records), Charsets.UTF_8)
        Log.i(TAG, "exportJson: ${records.size} 条 → ${file.absolutePath}")
        ExportFile(file, "application/json", "JSON（${records.size} 条记录）")
    }

    // ----------------------------------------------------------------- CSV

    suspend fun exportCsv(): ExportFile = withContext(Dispatchers.IO) {
        val records = repository.listAll()
        val file = File(repository.exportsDir, "records_${timestamp()}.csv")
        file.writeText(buildCsv(records), Charsets.UTF_8)
        Log.i(TAG, "exportCsv: ${records.size} 条 → ${file.absolutePath}")
        ExportFile(file, "text/csv", "CSV（${records.size} 条记录）")
    }

    // ----------------------------------------------------------------- ZIP

    suspend fun exportBundle(): ExportFile = withContext(Dispatchers.IO) {
        val records = repository.listAll()
        val file = File(repository.exportsDir, "edoc_export_${timestamp()}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            // 1) JSON
            zip.putNextEntry(ZipEntry("records.json"))
            zip.write(gson.toJson(records).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2) CSV
            zip.putNextEntry(ZipEntry("records.csv"))
            zip.write(buildCsv(records).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 3) 面部图像
            for (record in records) {
                val face = repository.faceImageFile(record) ?: continue
                zip.putNextEntry(ZipEntry("faces/${face.name}"))
                zip.write(face.readBytes())
                zip.closeEntry()
            }

            // 4) 说明
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(buildReadme(records.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        Log.i(TAG, "exportBundle: ${records.size} 条 → ${file.absolutePath}")
        ExportFile(file, "application/zip", "ZIP 打包（JSON + CSV + 人脸图像，共 ${records.size} 条）")
    }

    // ---------------------------------------------------------------- 内容

    private val csvHeader = listOf(
        "记录ID", "读取时间", "证件类型", "证件号码", "签发国/地区", "姓名(英文)", "姓名(本国文字)",
        "姓", "名", "性别", "国籍", "出生日期", "有效期至", "MRZ格式", "个人编号",
        "是否读取芯片", "芯片读取耗时(ms)", "芯片MRZ与OCR一致", "已读数据组", "可用数据组",
        "被动认证算法", "摘要全部匹配", "CMS签名有效", "签名证书主题", "签名证书签发者", "签名证书有效期至",
        "信任链已验证", "签发国根证书", "根证书序列号", "根证书有效期至", "信任库规模",
        "人脸图像文件", "人脸图像格式", "人脸图像字节数", "OCR纠错记录", "备注"
    )

    private fun buildCsv(records: List<DocRecord>): String {
        val sb = StringBuilder()
        // 带 BOM，保证 Excel 正确识别 UTF-8
        sb.append('\uFEFF')
        sb.append(csvHeader.joinToString(",")).append("\r\n")
        for (r in records) {
            val row = listOf(
                r.id, r.createdAtText, r.certName, r.documentNumber, r.issuingState,
                r.fullName, r.nativeName, r.surname, r.givenNames, r.gender, r.nationality,
                r.dateOfBirth, r.dateOfExpiry, r.mrzFormat, r.personalNumber,
                if (r.chipRead) "是" else "否", r.chipReadElapsedMs.toString(),
                when (r.chipMrzMatchesOcr) { true -> "是"; false -> "否"; null -> "未比对" },
                r.readDataGroups.joinToString("|") { "DG$it" },
                r.availableDgTags.joinToString("|"),
                r.passiveAuthHashAlgorithm,
                when (r.passiveAuthAllDgMatch) { true -> "是"; false -> "否"; null -> "未校验" },
                when (r.passiveAuthCmsSignatureValid) { true -> "是"; false -> "否"; null -> "未校验" },
                r.passiveAuthSignerSubject, r.passiveAuthSignerIssuer, r.passiveAuthSignerValidTo,
                if (r.cscaTrusted) "是" else "否",
                r.cscaSubject, r.cscaSerial, r.cscaNotAfter,
                if (r.cscaStoreSize > 0) r.cscaStoreSize.toString() else "",
                r.faceImageFileName, r.faceImageFormat, r.faceImageBytes.toString(),
                r.ocrRepairs.joinToString("；"),
                (r.ocrNotes + r.passiveAuthNotes).joinToString("；")
            )
            sb.append(row.joinToString(",") { csvEscape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    private fun buildReadme(count: Int): String = """
        |出入境证件电子信息识读导出包
        |=================================
        |
        |导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
        |记录数量：$count
        |
        |包内文件：
        |  records.json  —— 全量结构化记录（含被动认证明细、DG11 明细、读取过程日志）
        |  records.csv   —— 扁平表，可直接用 Excel / WPS 打开（UTF-8 with BOM）
        |  faces/        —— 芯片中读取到的持证人面部图像（通常为 JPEG）
        |  README.txt    —— 本说明
        |
        |字段说明（节选）：
        |  是否读取芯片          记录是否为"NFC 芯片读取"流程产生
        |  芯片MRZ与OCR一致      用 OCR 得到的 MRZ 三要素与芯片内 DG1 的 MRZ 交叉比对结果
        |  被动认证算法          EF.SOD 中 LDS Security Object 指定的摘要算法
        |  摘要全部匹配          本地重新计算的各数据组摘要是否与 EF.SOD 中记录的一致
        |  CMS签名有效           EF.SOD 的 CMS 签名能否用内嵌的文档签名证书（DSC）验证通过
        |
        |安全提示：
        |  1. 本导出包包含持证人敏感个人信息与生物特征图像，请妥善保管、按需删除；
        |  2. 处理他人证件信息应取得持证人明确授权，并遵守《个人信息保护法》等相关法律法规；
        |  3. "CMS签名有效"仅表示 EF.SOD 自身未被篡改；要确认签发国身份，还需使用
        |     签发国 CSCA 证书主列表做链路校验（本应用不内置该主列表）。
        |
    """.trimMargin()

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    companion object {
        private const val TAG = "ExportManager"
    }
}
