package com.edocreader.app.jp2

/**
 * 按**文件签名**判断图像格式。
 *
 * 为什么不按格式名判断：格式名可能来自芯片，也可能是我们自己写的说明文字。
 * v1.0.14 就踩过这个坑——落盘格式写成「JPEG（由 JPEG2000 转码）」，
 * 字符串里同时含 "JPEG" 和 "JPEG2000"，按名字匹配就会拿 JPEG 2000 解码器
 * 去解一个真正的 JPEG，报 "SOC marker segment not found"。
 *
 * **签名永远比名字可靠。**
 *
 * 这里刻意不引用任何 Android 类，方便在 JVM 单元测试里直接验证。
 */
object ImageSignature {

    const val JPEG2000 = "JPEG2000"
    const val JPEG = "JPEG"

    /** JPEG 2000 裸码流：SOC (FF4F) + SIZ (FF51)。 */
    private val JPEG2000_SOC_SIZ = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

    /** JPEG：SOI (FFD8) + 任意标记 (FF)。 */
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** JPEG 2000 的 JP2 文件格式签名盒。 */
    private val JP2_BOX = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A
    )

    /**
     * 识别格式；签名不认识时返回 null（交由调用方按格式名兜底）。
     */
    fun sniff(data: ByteArray): String? = when {
        data.startsWith(JPEG2000_SOC_SIZ) -> JPEG2000
        data.startsWith(JP2_BOX) -> JPEG2000
        data.startsWith(JPEG_SOI) -> JPEG
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
