package com.edocreader.app.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.edocreader.app.App
import com.edocreader.app.R
import com.edocreader.app.data.DocRecord
import com.edocreader.app.databinding.ActivityNfcBinding
import com.edocreader.app.mrz.MrzInfo
import com.edocreader.app.mrz.MrzParser
import com.edocreader.app.nfc.PassportReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 第二步：NFC 读取芯片。
 *
 * 使用 Reader Mode 直接拿到 [IsoDep]，随后交给 [PassportReader] 完成
 * BAC 认证、安全报文建立与各数据组读取。
 */
class NfcReadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfcBinding
    private var nfcAdapter: NfcAdapter? = null
    private var mrz: MrzInfo? = null

    private val reading = AtomicBoolean(false)
    private val handled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = mutableListOf<String>()

    private val readerCallback = NfcAdapter.ReaderCallback { tag -> onTagDiscovered(tag) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 两种来源：完整 MRZ 行组，或手动输入的三要素
        val docNo = intent.getStringExtra(EXTRA_DOC_NO)
        mrz = if (!docNo.isNullOrBlank()) {
            MrzParser.fromThreeElements(
                docNo,
                intent.getStringExtra(EXTRA_DOB).orEmpty(),
                intent.getStringExtra(EXTRA_EXPIRY).orEmpty()
            )
        } else {
            MrzParser.parseFromLines(intent.getStringArrayExtra(EXTRA_MRZ_LINES)?.toList().orEmpty())
        }
        if (mrz == null) {
            Toast.makeText(this, "MRZ 数据无效，请重新识别", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        renderMrz(mrz!!)

        binding.btnRetry.setOnClickListener {
            handled.set(false)
            reading.set(false)
            logLines.clear()
            binding.tvLog.text = ""
            binding.btnRetry.visibility = android.view.View.GONE
            setState(R.string.nfc_waiting, R.drawable.ic_nfc_phone, showProgress = true)
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setState(R.string.nfc_no_support, R.drawable.ic_error, showProgress = false)
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            setState(R.string.nfc_disabled, R.drawable.ic_error, showProgress = false)
            AlertDialog.Builder(this)
                .setTitle(R.string.nfc_disabled)
                .setMessage("读取电子证件芯片需要开启 NFC。是否前往系统设置开启？")
                .setPositiveButton("前往设置") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        setState(R.string.nfc_waiting, R.drawable.ic_nfc_phone, showProgress = true)
    }

    private fun renderMrz(info: MrzInfo) {
        binding.tvMrz.text = info.rawLines.joinToString("\n")
        binding.tvMrzMeta.text = buildString {
            append(info.format.label)
            append(" · ")
            append(if (info.allCheckDigitsValid) "校验位全部通过" else "校验位部分未通过")
            append(" · ")
            append(info.documentTypeLabel)
            if (info.repairedFields.isNotEmpty()) {
                append("\nOCR 纠错：")
                append(info.repairedFields.joinToString("；"))
            }
        }
    }

    // ------------------------------------------------------------ Reader Mode

    override fun onResume() {
        super.onResume()
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        runCatching { nfcAdapter?.enableReaderMode(this, readerCallback, flags, extras) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun onTagDiscovered(tag: Tag) {
        if (handled.get() || !reading.compareAndSet(false, true)) return

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            appendLog("该标签不是 ISO-DEP（ISO 14443-4）类型，无法读取证件芯片")
            reading.set(false)
            return
        }

        val currentMrz = mrz ?: return

        mainHandler.post {
            setState(R.string.nfc_reading, R.drawable.ic_nfc_phone, showProgress = true)
            appendLog("检测到证件芯片，开始读取…")
        }

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    PassportReader(isoDep) { message ->
                        mainHandler.post { appendLog(message) }
                    }.read(currentMrz)
                }
            }

            outcome.onSuccess { result ->
                handled.set(true)
                setState(R.string.nfc_success, R.drawable.ic_check_circle, showProgress = false)
                appendLog("读取成功，正在保存…")
                saveAndOpen(result)
            }.onFailure { e ->
                Log.e(TAG, "读卡失败", e)
                reading.set(false)
                setState(R.string.nfc_failed, R.drawable.ic_error, showProgress = false)
                appendLog("失败：${e.message}")
                binding.btnRetry.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun saveAndOpen(result: PassportReader.Result) {
        lifecycleScope.launch {
            val mrzInfo = result.chipMrz ?: result.ocrMrz
            val record = DocRecord.fromReadResult(
                result = result,
                certName = mrzInfo.documentTypeLabel,
                faceImageFileName = ""
            )
            val saved = App.instance.repository.save(record, result.faceImage)
            Toast.makeText(this@NfcReadActivity, "已保存到本地", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this@NfcReadActivity, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_ID, saved.id)
            )
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun setState(stringRes: Int, iconRes: Int, showProgress: Boolean) {
        binding.tvState.setText(stringRes)
        binding.ivState.setImageResource(iconRes)
        binding.progress.visibility = if (showProgress) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvStateHint.visibility = if (showProgress) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun appendLog(message: String) {
        logLines.add(message)
        binding.tvLog.text = logLines.joinToString("\n") { "· $it" }
    }

    companion object {
        private const val TAG = "NfcReadActivity"
        const val EXTRA_MRZ_LINES = "extra_mrz_lines"
        const val EXTRA_SOURCE = "extra_source"
        const val SOURCE_OCR = "ocr"

        /** 手动输入三要素时使用的附加数据（替代完整 MRZ）。 */
        const val EXTRA_DOC_NO = "extra_doc_no"
        const val EXTRA_DOB = "extra_dob"
        const val EXTRA_EXPIRY = "extra_expiry"
        const val SOURCE_MANUAL_FIELDS = "manual_fields"
    }
}
