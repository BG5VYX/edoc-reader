package com.edocreader.app.nfc.dg

import com.edocreader.app.nfc.Tlv
import com.edocreader.app.util.Hex

/**
 * 各类数据组（Data Group）的解析。
 *
 * ICAO 9303 Part 10 定义的数据组：
 *  - DG1  机读区（MRZ）
 *  - DG2  面部图像
 *  - DG3  指纹
 *  - DG11 附加个人资料（含持证人母语姓名）
 *  - DG12 附加证件资料
 *  - DG13 可选资料
 *  - DG14 安全选项（PACE / 芯片认证 / 主动认证参数）
 *  - DG15 主动认证公钥
 *  - DG16 联系人
 */
object DgParsers {

    // ------------------------------------------------------------------ DG1

    /** 提取 DG1 中的 MRZ 文本（可能包含换行或直接连排）。 */
    fun parseDg1(dg1: ByteArray): String? {
        val root = Tlv.parse(dg1).firstOrNull() ?: return null
        val mrzNode = root.find(0x5F1F) ?: return null
        return String(mrzNode.value, Charsets.US_ASCII)
    }

    // ------------------------------------------------------------------ DG2

    /** DG2 解析结果。 */
    data class FaceImage(
        val bytes: ByteArray,
        val format: String,
        /** CBEFF / 生物特征模板的顶层 tag，便于排查。 */
        val outerTag: String
    )

    /**
     * 从 DG2 中提取面部图像。
     *
     * DG2 的封装层次是 `75 → 7F61 → 7F60 → ... → 生物特征数据块`，块内为
     * 标准 JPEG（FFD8FF）或 JPEG2000（JP2 签名箱）。这里采用"签名扫描"的方式
     * 定位图像数据，兼容不同厂商的封装差异。
     */
    fun parseDg2(dg2: ByteArray): FaceImage? {
        val outerTag = Tlv.parse(dg2).firstOrNull()?.tagHex ?: "??"

        // JPEG：SOI(FFD8FF) ... EOI(FFD9)
        val jpegStart = indexOf(dg2, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        if (jpegStart >= 0) {
            val jpegEnd = lastIndexOf(dg2, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            val end = if (jpegEnd > jpegStart) jpegEnd + 2 else dg2.size
            return FaceImage(dg2.copyOfRange(jpegStart, end), "JPEG", outerTag)
        }

        // JPEG2000：签名箱 00 00 00 0C 6A 50 20 20 0D 0A 87 0A
        val jp2Sig = Hex.decode("0000000C6A5020200D0A870A")
        val jp2Start = indexOf(dg2, jp2Sig)
        if (jp2Start >= 0) {
            return FaceImage(dg2.copyOfRange(jp2Start, dg2.size), "JPEG2000", outerTag)
        }

        return null
    }

    // ----------------------------------------------------------------- DG11

    /** DG11 中的一个信息项。 */
    data class Dg11Item(val tag: Int, val label: String, val value: String)

    private val DG11_LABELS = mapOf(
        0x5F0E to "姓名（母语/全名）",
        0x5F0F to "出生地",
        0x5F10 to "个人编号",
        0x5F11 to "姓名（本国文字）",
        0x5F12 to "预留",
        0x5F13 to "完整出生日期",
        0x5F42 to "住址",
        0x5F43 to "电话",
        0x5F44 to "职业",
        0x5F45 to "职务",
        0x5F46 to "个人简历",
        0x5F47 to "国籍证明",
        0x5F48 to "其他有效证件号",
        0x5F49 to "监护信息"
    )

    /** 解析 DG11，返回其中所有信息项。 */
    fun parseDg11(dg11: ByteArray): List<Dg11Item> {
        val root = Tlv.parse(dg11).firstOrNull() ?: return emptyList()
        val items = mutableListOf<Dg11Item>()
        for (node in root.children) {
            val text = decodeText(node.value)
            items.add(
                Dg11Item(
                    tag = node.tag,
                    label = DG11_LABELS[node.tag] ?: "未知项 0x${node.tagHex}",
                    value = text
                )
            )
        }
        return items
    }

    /** DG11 中的"本国文字姓名"（中国证件即中文姓名）。 */
    fun extractNativeName(items: List<Dg11Item>): String? =
        items.firstOrNull { it.tag == 0x5F0E }?.value?.takeIf { it.isNotBlank() }
            ?: items.firstOrNull { it.tag == 0x5F11 }?.value?.takeIf { it.isNotBlank() }

    private fun decodeText(bytes: ByteArray): String {
        // 先按 UTF-8 解，失败则退回 Latin-1
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    // ------------------------------------------------------------ DG14 / DG15

    /** DG14 中出现的 SecurityInfo 类型（以 tag 概述）。 */
    fun describeDg14(dg14: ByteArray): List<String> {
        val root = Tlv.parse(dg14).firstOrNull() ?: return emptyList()
        return root.children.map { child ->
            val oid = child.child(0x06)?.value?.let { decodeOid(it) }
            "SecurityInfo ${child.tagHex}" + if (oid != null) "（$oid）" else ""
        }
    }

    /** DG15：主动认证（AA）公钥，返回可读摘要。 */
    fun describeDg15(dg15: ByteArray): String? {
        val root = Tlv.parse(dg15).firstOrNull() ?: return null
        val subjectPublicKey = root.find(0x03)?.value ?: return null
        return "主动认证公钥 ${subjectPublicKey.size} 字节"
    }

    // -------------------------------------------------------------- EF.COM

    /** EF.COM 中的 tag 列表（0x5F 开头），用于判断芯片里有哪些数据组。 */
    fun parseEfCom(efCom: ByteArray): List<String> {
        val root = Tlv.parse(efCom).firstOrNull() ?: return emptyList()
        val lds = root.find(0x5C) ?: return emptyList()
        val tags = mutableListOf<String>()
        // 0x5C 的值是若干 BER tag 的串联
        var pos = 0
        while (pos < lds.value.size) {
            val node = Tlv.parseOne(lds.value, pos, lds.value.size) ?: break
            tags.add(String.format("%02X", node.tag))
            pos += node.rawLength
        }
        return tags
    }

    // ---------------------------------------------------------------- 工具

    private fun decodeOid(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder()
        var first = bytes[0].toInt() and 0xFF
        sb.append(first / 40).append('.').append(first % 40)
        var value = 0L
        for (i in 1 until bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            if ((b and 0x80) == 0) {
                sb.append('.').append(value)
                value = 0
            }
        }
        return sb.toString()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in (haystack.size - needle.size) downTo 0) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
