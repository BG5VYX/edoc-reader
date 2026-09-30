package com.edocreader.app.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.edocreader.app.App
import com.edocreader.app.R
import com.edocreader.app.data.DocRecord
import com.edocreader.app.data.ExportManager
import com.edocreader.app.databinding.ActivityDetailBinding
import com.edocreader.app.databinding.ItemDetailRowBinding
import com.edocreader.app.databinding.ItemDetailSectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记录详情页。 */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private var record: DocRecord? = null
    private var pendingExport: ExportManager.ExportFile? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val pending = pendingExport
            pendingExport = null
            if (uri == null || pending == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            pending.file.inputStream().use { it.copyTo(out) }
                        }
                        true
                    }.getOrDefault(false)
                }
                Toast.makeText(this@DetailActivity, if (ok) "已导出" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val loaded = App.instance.repository.get(id)
            if (loaded == null) {
                Toast.makeText(this@DetailActivity, "记录不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            record = loaded
            render(loaded)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export_one -> {
            exportOne()
            true
        }

        R.id.action_delete -> {
            confirmDelete()
            true
        }

        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(record: DocRecord) {
        binding.tvName.text = record.fullName.ifBlank { record.displayTitle }
        binding.tvNativeName.text = record.nativeName
        binding.tvNativeName.visibility = if (record.nativeName.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvDocNo.text = record.documentNumber
        binding.tvCertName.text = record.certName
        binding.tvCreatedAt.text = "读取时间：${record.createdAtText}"

        val faceFile = App.instance.repository.faceImageFile(record)
        if (faceFile != null && faceFile.exists()) {
            val bmp = BitmapFactory.decodeFile(faceFile.absolutePath)
            if (bmp != null) binding.ivFace.setImageBitmap(bmp)
        }

        binding.sectionContainer.removeAllViews()

        // ---- 证件信息 ----
        addSection(
            "证件信息",
            listOf(
                "证件类型" to record.certName,
                "证件号码" to record.documentNumber,
                "签发国家/地区" to record.issuingState,
                "国籍" to record.nationality,
                "姓名（英文）" to record.fullName,
                "姓名（本国文字）" to record.nativeName,
                "姓" to record.surname,
                "名" to record.givenNames,
                "性别" to record.gender,
                "出生日期" to record.dateOfBirth,
                "有效期至" to record.dateOfExpiry,
                "个人编号" to record.personalNumber,
                "公民身份号码" to record.idNumber,
                "MRZ 格式" to record.mrzFormat
            )
        )

        // ---- 芯片读取 ----
        if (record.chipRead) {
            addSection(
                "芯片读取",
                listOf(
                    "读取耗时" to "${record.chipReadElapsedMs} ms",
                    "已读数据组" to record.readDataGroups.joinToString(", ") { "DG$it" }.ifBlank { "—" },
                    "芯片声明数据组" to record.availableDgTags.joinToString(", ").ifBlank { "—" },
                    "芯片 MRZ 与 OCR 一致性" to when (record.chipMrzMatchesOcr) {
                        true -> "一致"
                        false -> "不一致（请核对证件）"
                        null -> "未比对"
                    },
                    "人脸图像" to if (record.faceImageBytes > 0) {
                        "${record.faceImageFormat}，${record.faceImageBytes} 字节"
                    } else "未读取到",
                    "DG14 安全信息" to record.dg14Infos.joinToString("\n").ifBlank { "—" },
                    "DG15 主动认证" to record.dg15Info.ifBlank { "—" }
                )
            )
        }

        // ---- DG11 附加资料 ----
        if (record.dg11Items.isNotEmpty()) {
            addSection(
                "附加资料（DG11）",
                record.dg11Items.map { "${it.label}（${it.tag}）" to it.value }
            )
        }

        // ---- 被动认证 ----
        if (record.passiveAuthHashAlgorithm.isNotBlank()) {
            val rows = mutableListOf<Pair<String, String>>()
            rows.add("摘要算法" to record.passiveAuthHashAlgorithm)
            rows.add(
                "数据组摘要" to when (record.passiveAuthAllDgMatch) {
                    true -> "全部一致"
                    false -> "存在不一致"
                    null -> "未校验"
                }
            )
            rows.add(
                "CMS 签名" to when (record.passiveAuthCmsSignatureValid) {
                    true -> "验证通过"
                    false -> "验证失败"
                    null -> "未校验"
                }
            )
            rows.add("签名证书主题" to record.passiveAuthSignerSubject.ifBlank { "—" })
            rows.add("签名证书签发者" to record.passiveAuthSignerIssuer.ifBlank { "—" })
            rows.add("签名证书有效期至" to record.passiveAuthSignerValidTo.ifBlank { "—" })

            // CSCA 信任链
            rows.add(
                "信任链" to if (record.cscaTrusted) {
                    "已验证 —— 链接到签发国根证书"
                } else {
                    "未通过"
                }
            )
            if (record.cscaSubject.isNotBlank()) {
                rows.add("签发国根证书" to record.cscaSubject)
            }
            if (record.cscaSerial.isNotBlank()) {
                rows.add("根证书序列号" to record.cscaSerial)
            }
            if (record.cscaNotAfter.isNotBlank()) {
                rows.add(
                    "根证书有效期至" to record.cscaNotAfter +
                        if (record.cscaCurrentlyExpired) "（已过期，历史证件仍可能合法）" else ""
                )
            }
            if (record.cscaChainDetail.isNotBlank()) {
                rows.add("信任链说明" to record.cscaChainDetail)
            }
            if (record.cscaStoreSize > 0) {
                rows.add("信任库规模" to "${record.cscaStoreSize} 张 CSCA 证书")
            }
            for (dg in record.passiveAuthDgDetails) {
                val status = when {
                    !dg.present -> "未读取"
                    dg.matches == true -> "摘要一致"
                    dg.matches == false -> "摘要不一致"
                    else -> "未校验"
                }
                rows.add("DG${dg.dgNumber}" to status)
            }
            if (record.passiveAuthNotes.isNotEmpty()) {
                rows.add("说明" to record.passiveAuthNotes.joinToString("\n"))
            }
            addSection("被动认证", rows)
        }

        // ---- 主动认证 ----
        if (record.activeAuthDetail.isNotBlank() || record.activeAuthPerformed) {
            val rows = mutableListOf<Pair<String, String>>()
            rows.add(
                "主动认证" to when (record.activeAuthVerified) {
                    true -> "通过 —— 可排除芯片克隆"
                    false -> "未通过 —— 该芯片可能是克隆芯片"
                    null -> if (record.activeAuthPerformed) "未启用" else "未执行"
                }
            )
            if (record.activeAuthKeyDetail.isNotBlank()) {
                rows.add("公钥" to record.activeAuthKeyDetail)
            }
            if (record.activeAuthAlgorithm.isNotBlank()) {
                rows.add("签名算法" to record.activeAuthAlgorithm)
            }
            if (record.activeAuthChallenge.isNotBlank()) {
                rows.add("挑战值" to record.activeAuthChallenge)
            }
            if (record.activeAuthSignatureLength > 0) {
                rows.add("签名长度" to "${record.activeAuthSignatureLength} 字节")
            }
            if (record.activeAuthDetail.isNotBlank()) {
                rows.add("说明" to record.activeAuthDetail)
            }
            addSection("主动认证", rows)
        }

        // ---- 原始 MRZ ----
        val mrzRows = mutableListOf<Pair<String, String>>()
        mrzRows.add("OCR 识别" to record.ocrMrzLines.joinToString("\n"))
        if (record.chipMrzLines.isNotEmpty()) {
            mrzRows.add("芯片 DG1" to record.chipMrzLines.joinToString("\n"))
        }
        if (record.ocrRepairs.isNotEmpty()) {
            mrzRows.add("OCR 纠错" to record.ocrRepairs.joinToString("\n"))
        }
        if (record.ocrNotes.isNotEmpty()) {
            mrzRows.add("备注" to record.ocrNotes.joinToString("\n"))
        }
        addSection("原始 MRZ", mrzRows)

        // ---- 过程日志 ----
        if (record.steps.isNotEmpty()) {
            addSection("读取过程", listOf("步骤" to record.steps.joinToString("\n")))
        }
    }

    private fun addSection(title: String, rows: List<Pair<String, String>>) {
        val section = ItemDetailSectionBinding.inflate(LayoutInflater.from(this), binding.sectionContainer, false)
        section.tvSectionTitle.text = title
        section.rowContainer.removeAllViews()
        for ((label, value) in rows) {
            if (value.isBlank()) continue
            val row = ItemDetailRowBinding.inflate(LayoutInflater.from(this), section.rowContainer, false)
            row.tvLabel.text = label
            row.tvValue.text = value
            section.rowContainer.addView(row.root)
        }
        if (section.rowContainer.childCount == 0) {
            val tv = TextView(this)
            tv.text = "—"
            tv.setTextColor(getColor(R.color.app_on_surface_variant))
            section.rowContainer.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        binding.sectionContainer.addView(section.root)
    }

    // ---------------------------------------------------------------- 操作

    private fun exportOne() {
        val current = record ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = App.instance.repository.exportsDir
                val f = java.io.File(dir, "record_${current.documentNumber.ifBlank { current.id }}.json")
                f.writeText(
                    com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create()
                        .toJson(current),
                    Charsets.UTF_8
                )
                f
            }
            pendingExport = ExportManager.ExportFile(file, "application/json", "单条记录 JSON")
            createDocumentLauncher.launch(file.name)
        }
    }

    private fun confirmDelete() {
        val current = record ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_delete)
            .setMessage("将删除该条记录及其人脸图像，且不可恢复。")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    App.instance.repository.delete(current.id)
                    Toast.makeText(this@DetailActivity, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "extra_record_id"
    }
}
