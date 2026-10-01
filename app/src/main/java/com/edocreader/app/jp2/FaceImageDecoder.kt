package com.edocreader.app.jp2

import android.graphics.Bitmap
import android.util.Log

/**
 * 证件照片解码。
 *
 * eMRTD 的 DG2 面部图像**不一定是 JPEG**——中国签发的往来港澳/台湾通行证
 * 用的是 **JPEG 2000 裸码流**（以 `FF4F FF51` 即 SOC+SIZ 开头）。
 * Android 平台不内置 JPEG 2000 解码器，`BitmapFactory` 对它无能为力，
 * 所以这里自己解：JPEG 交给系统，JPEG 2000 走 [Jp2MemoryDecoder]。
 */
object FaceImageDecoder {

    private const val TAG = "FaceImageDecoder"

    /** 解码失败时的原因，便于在界面上如实说明。 */
    sealed class Outcome {
        /** 解码成功。 */
        data class Success(val bitmap: Bitmap) : Outcome()

        /** 认识格式但本机解不了。 */
        data class Unsupported(val reason: String) : Outcome()

        /** 数据为空或无法识别。 */
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * 把 DG2 中的图像数据解成位图。
     *
     * @param data 图像字节
     * @param format [com.edocreader.app.nfc.dg.DgParsers.FaceImage.format] 给出的格式名
     */
    fun decode(data: ByteArray, format: String): Outcome {
        if (data.isEmpty()) return Outcome.Failed("图像数据为空")

        // ---- 先按格式名走 ----
        when {
            format.contains("JPEG2000", ignoreCase = true) -> return decodeJpeg2000(data)
            format.startsWith("JPEG") -> {
                decodeByBitmapFactory(data)?.let { return Outcome.Success(it) }
            }
        }

        // ---- 格式名不可靠时按签名兜底 ----
        if (startsWith(data, 0xFF, 0x4F, 0xFF, 0x51)) return decodeJpeg2000(data)
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            decodeByBitmapFactory(data)?.let { return Outcome.Success(it) }
        }

        decodeByBitmapFactory(data)?.let { return Outcome.Success(it) }

        return Outcome.Unsupported("无法识别的图像格式（$format）")
    }

    /** 用 jj2000 解码 JPEG 2000 裸码流。 */
    private fun decodeJpeg2000(data: ByteArray): Outcome = try {
        val result = Jp2MemoryDecoder.decode(data)
        // 解码器给出的是 0xRRGGBB，补上不透明通道
        val argb = IntArray(result.argb.size) { i -> result.argb[i] or (0xFF shl 24) }
        val bmp = Bitmap.createBitmap(argb, result.width, result.height, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "JPEG 2000 解码成功：${result.width}x${result.height}")
        Outcome.Success(bmp)
    } catch (e: Throwable) {
        // jj2000 在数据异常时会抛 Error（而不只是 Exception），这里一并兜住
        Log.w(TAG, "JPEG 2000 解码失败", e)
        Outcome.Unsupported("JPEG 2000 解码失败：${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * 把证件照片转成**通用的 JPEG 字节**，好让任何看图软件都能打开。
     *
     * DG2 里的图像可能是 JPEG 2000，直接把它命名为 `.jpg` 存盘虽然扩展名对，
     * 但内容不是 JPEG，导出后用看图软件打不开。这里统一转码：
     * - 本来就是 JPEG 的，原样返回（不重新编码，避免画质损失）
     * - JPEG 2000 的先解码再编码为 JPEG
     *
     * @return 可被看图软件打开的 JPEG 字节；无法转换时返回 null
     */
    fun toStandardJpeg(data: ByteArray, format: String, quality: Int = 90): ByteArray? {
        if (data.isEmpty()) return null

        // 本来就是 JPEG，直接用
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) return data

        val bitmap = when (val outcome = decode(data, format)) {
            is Outcome.Success -> outcome.bitmap
            else -> {
                Log.w(TAG, "无法转为 JPEG：$outcome")
                return null
            }
        }
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "JPEG 编码失败", e)
            null
        }
    }

    /** 交给系统解码器（JPEG / PNG 等）。 */
    private fun decodeByBitmapFactory(data: ByteArray): Bitmap? = try {
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
    } catch (e: Throwable) {
        Log.w(TAG, "系统解码器失败", e)
        null
    }

    private fun startsWith(data: ByteArray, vararg prefix: Int): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if ((data[i].toInt() and 0xFF) != prefix[i]) return false
        }
        return true
    }
}
