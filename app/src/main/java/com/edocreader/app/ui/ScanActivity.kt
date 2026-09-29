package com.edocreader.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.edocreader.app.R
import com.edocreader.app.databinding.ActivityScanBinding
import com.edocreader.app.databinding.DialogManualMrzBinding
import com.edocreader.app.mrz.MrzInfo
import com.edocreader.app.mrz.MrzParser
import com.edocreader.app.ocr.MrzOcrAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 第一步：OCR 识别证件 MRZ。
 *
 * 使用 CameraX 预览 + ImageAnalysis，逐帧交给 ML Kit（Latin，离线模型）做文本识别，
 * 再经 [MrzParser] 做行组提取与校验位校验/纠错。校验位全部通过后自动进入读卡页。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: MrzOcrAnalyzer? = null
    private var executor: ExecutorService? = null
    private var torchEnabled = false
    private var camera: androidx.camera.core.Camera? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "需要摄像头权限才能识别 MRZ", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnManual.setOnClickListener { showManualInput() }
        binding.btnFlash.setOnClickListener { toggleTorch() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()

                val exec = Executors.newSingleThreadExecutor()
                executor = exec

                val mrzAnalyzer = MrzOcrAnalyzer(
                    onMrzDetected = { info -> runOnUiThread { onMrzReady(info) } },
                    onCandidate = { info -> runOnUiThread { showCandidate(info) } },
                    onRawLines = { lines -> runOnUiThread { showRawLines(lines) } }
                )
                analyzer = mrzAnalyzer
                analysis.setAnalyzer(exec, mrzAnalyzer)

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "相机启动失败", e)
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchEnabled = !torchEnabled
        cam.cameraControl.enableTorch(torchEnabled)
        binding.btnFlash.setImageResource(
            if (torchEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    // ------------------------------------------------------------ OCR 回调

    private var lastPreviewText = ""

    private fun showRawLines(lines: List<String>) {
        val text = lines.takeLast(4).joinToString("\n")
        if (text != lastPreviewText) {
            lastPreviewText = text
            binding.tvMrzPreview.text = text
        }
    }

    private fun showCandidate(info: MrzInfo) {
        binding.tvStatus.text = "识别中：${info.format.label} · 校验位待修正"
    }

    private fun onMrzReady(info: MrzInfo) {
        binding.tvStatus.text = getString(R.string.scan_recognized)
        binding.tvMrzPreview.text = info.rawLines.joinToString("\n")
        analyzer?.release()

        // 稍作停留让用户看到结果，再进入读卡页
        binding.root.postDelayed({
            goToNfc(info)
        }, 350)
    }

    private fun goToNfc(info: MrzInfo) {
        val intent = Intent(this, NfcReadActivity::class.java).apply {
            putExtra(NfcReadActivity.EXTRA_MRZ_LINES, info.rawLines.toTypedArray())
            putExtra(NfcReadActivity.EXTRA_SOURCE, NfcReadActivity.SOURCE_OCR)
        }
        startActivity(intent)
        finish()
    }

    // ---------------------------------------------------------- 手动输入

    private fun showManualInput() {
        val dialogBinding = DialogManualMrzBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.scan_manual)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lines = listOf(
                    dialogBinding.etLine1.text?.toString().orEmpty(),
                    dialogBinding.etLine2.text?.toString().orEmpty(),
                    dialogBinding.etLine3.text?.toString().orEmpty()
                ).filter { it.isNotBlank() }.map { MrzParser.sanitizeLine(it) }

                val info = MrzParser.parseFromLines(lines)
                if (info == null) {
                    // 把实际检测到的行长度打出来，便于定位是行数不对还是每行位数不对
                    val detected = lines.joinToString("、") { "${it.length} 字符" }
                    dialogBinding.tvParseResult.text = buildString {
                        append("无法解析。\n")
                        append("实际输入：${lines.size} 行（$detected）\n")
                        append("各格式要求：\n")
                        append("· 护照 TD3 —— 2 行 × 44 字符\n")
                        append("· 往来港澳 / 往来台湾通行证 TD1 —— 3 行 × 30 字符\n")
                        append("· 其他证件卡 TD2 —— 2 行 × 36 字符\n")
                        append("三种格式的证件号均为 9 位、出生日期与有效期均为 6 位。")
                    }
                    return@setOnClickListener
                }

                dialogBinding.tvParseResult.text = buildString {
                    append("已解析：${info.format.label}\n")
                    append("证件号 ${info.documentNumber}（校验位 ${if (MrzParser.isCheckDigitValid(info.documentNumber, info.documentNumberCheckDigit)) "通过" else "不通过"}）\n")
                    append("出生 ${info.birthDateIso} / 有效期 ${info.expiryDateIso}\n")
                    append(if (info.allCheckDigitsValid) "校验位全部通过" else "部分校验位未通过：${info.notes.joinToString("；")}")
                }

                if (info.allCheckDigitsValid) {
                    dialog.dismiss()
                    goToNfc(info)
                } else {
                    // 允许用户坚持继续（BAC 仍可能成功）
                    AlertDialog.Builder(this)
                        .setTitle("校验位未全部通过")
                        .setMessage("MRZ 的校验位未全部通过，但仍可以尝试读取芯片。是否继续？")
                        .setPositiveButton("继续读卡") { _, _ ->
                            dialog.dismiss()
                            goToNfc(info)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.release()
        executor?.shutdown()
        cameraProvider?.unbindAll()
        binding.previewView.visibility = View.INVISIBLE
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}
