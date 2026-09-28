package com.edocreader.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.edocreader.app.App
import com.edocreader.app.R
import com.edocreader.app.data.DocRecord
import com.edocreader.app.data.ExportManager
import com.edocreader.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页：记录列表 + 批量导出入口。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecordAdapter

    /** 待导出的产物，SAF 返回 Uri 后再写入。 */
    private var pendingExport: ExportManager.ExportFile? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
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
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "已导出：${pending.description}" else "导出失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = RecordAdapter { record ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, record.id))
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_export -> {
            showExportDialog()
            true
        }

        R.id.action_delete_all -> {
            confirmDeleteAll()
            true
        }

        R.id.action_about -> {
            showAbout()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val records = App.instance.repository.listAll()
            adapter.submitList(records)
            binding.emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recycler.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- 导出

    private fun showExportDialog() {
        val options = arrayOf(
            "JSON（完整结构化数据）",
            "CSV（可用 Excel / WPS 打开）",
            "ZIP 打包（JSON + CSV + 人脸图像）"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_export)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val count = App.instance.repository.listAll().size
                    if (count == 0) {
                        Toast.makeText(this@MainActivity, "当前没有记录可导出", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val export = when (which) {
                        0 -> App.instance.exportManager.exportJson()
                        1 -> App.instance.exportManager.exportCsv()
                        else -> App.instance.exportManager.exportBundle()
                    }
                    pendingExport = export
                    createDocumentLauncher.launch(export.file.name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_all)
            .setMessage("将删除本机保存的全部证件记录与人脸图像，且不可恢复。确定继续吗？")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    App.instance.repository.deleteAll()
                    refresh()
                    Toast.makeText(this@MainActivity, "已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 关于

    private fun showAbout() {
        val text = """
            本应用演示"先 OCR 识别 MRZ、再 NFC 读取芯片"的完整离线读证流程。

            · 不依赖任何商业 SDK，也无需授权文件
            · OCR 与芯片读取全部在本机完成，不联网
            · 支持 ICAO 9303 TD1 / TD2 / TD3 机读区
            · 支持 BAC + 3DES 安全报文，读取 DG1/DG2/DG11/DG14/DG15/EF.SOD
            · 用 EF.SOD 做被动认证（数据组摘要比对 + CMS 验签）

            合规提示
            读取证件芯片涉及敏感个人信息。请仅对本人证件或已取得明确授权的证件
            使用本应用，并遵守《中华人民共和国个人信息保护法》等法律法规。
            读取到的数据保存在应用私有目录，卸载即清除；导出的文件请自行妥善保管。
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(R.string.action_about)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("查看项目主页") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/"))
                    )
                }
            }
            .show()
    }
}
