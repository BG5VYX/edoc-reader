package com.edocreader.app.ocr

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.edocreader.app.mrz.MrzInfo
import com.edocreader.app.mrz.MrzParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 ML Kit 的 MRZ 实时识别。
 *
 * 工作方式：
 *  - CameraX 的 ImageAnalysis 持续送帧；
 *  - 每帧交给 ML Kit Latin 文本识别（模型随 APK 打包，**完全离线**，无需网络与授权文件）；
 *  - 把识别出的文本行交给 [MrzParser] 做行组提取、格式判定与校验位校验/纠错；
 *  - 校验位全部通过时立即回调，避免反复识别造成闪烁。
 */
class MrzOcrAnalyzer(
    private val onMrzDetected: (MrzInfo) -> Unit,
    /** 校验位尚未全部通过时的中间结果，可用于 UI 提示"正在识别"。 */
    private val onCandidate: (MrzInfo) -> Unit = {},
    private val onRawLines: (List<String>) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (finished.get() || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }

        val mediaImage = image.image
        if (mediaImage == null) {
            busy.set(false)
            image.close()
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)

        recognizer.process(input)
            .addOnSuccessListener { visionText -> handleText(visionText) }
            .addOnFailureListener { e -> Log.w(TAG, "ML Kit 识别失败", e) }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }

    private fun handleText(visionText: Text) {
        if (finished.get()) return

        val lines = visionText.textBlocks
            .flatMap { it.lines }
            .map { it.text }

        if (lines.isEmpty()) return
        onRawLines(lines)

        val info = MrzParser.parseFromLines(lines) ?: return

        if (info.allCheckDigitsValid) {
            if (finished.compareAndSet(false, true)) {
                Log.i(TAG, "MRZ 识别成功：${info.rawLines}")
                onMrzDetected(info)
            }
        } else {
            onCandidate(info)
        }
    }

    fun release() {
        finished.set(true)
        runCatching { recognizer.close() }
    }

    companion object {
        private const val TAG = "MrzOcrAnalyzer"
    }
}
