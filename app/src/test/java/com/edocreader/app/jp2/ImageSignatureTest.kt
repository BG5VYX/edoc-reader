package com.edocreader.app.jp2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图像格式识别。
 *
 * 这个测试是为了锁住 v1.0.14 的一个真实 bug：当时落盘格式写成了
 * 「JPEG（由 JPEG2000 转码）」，字符串里同时含 "JPEG" 和 "JPEG2000"，
 * 而解码分发是按格式名匹配的，于是拿 JPEG 2000 解码器去解一个真正的 JPEG，
 * 界面上报 "SOC marker segment not found at the beginning of the codestream"。
 *
 * 修复方式：**一律先看文件签名**，格式名只在签名不认识时才参考。
 *
 * 注意：本文件中的字节序列都是**构造出来的**，不含任何真实证件数据。
 */
class ImageSignatureTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `识别 JPEG 2000 裸码流`() {
        // SOC (FF4F) + SIZ (FF51)，后面跟任意内容
        val d = bytes(0xFF, 0x4F, 0xFF, 0x51, 0x00, 0x2F, 0x00, 0x00)
        assertEquals(ImageSignature.JPEG2000, ImageSignature.sniff(d))
    }

    @Test
    fun `识别 JP2 文件格式`() {
        // 长度 12 的签名盒 + 类型 'jp2 ' + 魔数
        val d = bytes(
            0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A,
            0x00, 0x00
        )
        assertEquals(ImageSignature.JPEG2000, ImageSignature.sniff(d))
    }

    @Test
    fun `识别 JPEG`() {
        // SOI (FFD8) + APP0 (FFE0) —— 真正的 JPEG 一定是这个开头
        val d = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46)
        assertEquals(ImageSignature.JPEG, ImageSignature.sniff(d))
    }

    @Test
    fun `JPEG 与 JPEG 2000 的签名互不混淆`() {
        // 这正是那个 bug 的核心：一个真正的 JPEG 绝不能被认成 JPEG 2000
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)
        assertEquals(ImageSignature.JPEG, ImageSignature.sniff(jpeg))

        val jp2k = bytes(0xFF, 0x4F, 0xFF, 0x51, 0x00, 0x2F)
        assertEquals(ImageSignature.JPEG2000, ImageSignature.sniff(jp2k))
    }

    @Test
    fun `签名不认识时返回 null 以便按格式名兜底`() {
        assertNull(ImageSignature.sniff(bytes(0x89, 0x50, 0x4E, 0x47)))  // PNG
        assertNull(ImageSignature.sniff(bytes()))
        assertNull(ImageSignature.sniff(bytes(0xFF)))
        assertNull(ImageSignature.sniff(bytes(0xFF, 0xD8)))              // 截断的 JPEG
    }

    @Test
    fun `前两位相同但第三位不同不应误判`() {
        // FF4F 是 JPEG 2000 的 SOC；FFD8 是 JPEG 的 SOI。
        // 只比较前两位就下结论是危险的，必须比完整签名。
        assertNull(ImageSignature.sniff(bytes(0xFF, 0xD8, 0x00)))
        assertNull(ImageSignature.sniff(bytes(0xFF, 0x4F, 0x00)))
    }
}
