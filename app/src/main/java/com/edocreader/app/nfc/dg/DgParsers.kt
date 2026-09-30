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
     * 标准 JPEG、JPEG2000（JP2 包装格式）或 JPEG2000 裸码流。这里采用"签名扫描"
     * 的方式定位图像数据，兼容不同厂商的封装差异。
     *
     * 三种图像格式的签名：
     *  - JPEG           `FF D8 FF`（SOI）
     *  - JPEG2000 / JP2 `00 00 00 0C 6A 50 20 20 0D 0A 87 0A`（JP2 签名箱）
     *  - JPEG2000 裸码流 `FF 4F FF 51`（SOC + SIZ）——不带 JP2 包装箱，
     *    往来港澳/台湾通行证等证件使用这种形式，早期版本漏检导致头像无法显示
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

        // JPEG2000：JP2 文件格式（签名箱）
        val jp2Sig = Hex.decode("0000000C6A5020200D0A870A")
        val jp2Start = indexOf(dg2, jp2Sig)
        if (jp2Start >= 0) {
            return FaceImage(dg2.copyOfRange(jp2Start, dg2.size), "JPEG2000", outerTag)
        }

        // JPEG2000：裸码流（SOC `FF4F` + SIZ `FF51`）
        val j2kStart = indexOf(
            dg2,
            byteArrayOf(0xFF.toByte(), 0x4F.toByte(), 0xFF.toByte(), 0x51.toByte())
        )
        if (j2kStart >= 0) {
            return FaceImage(dg2.copyOfRange(j2kStart, dg2.size), "JPEG2000", outerTag)
        }

        // 兜底：认不出图像格式时也把整个数据组原样保留下来。
        // 宁可存一份「打不开但完整」的数据，也不要静默丢弃证件照片。
        if (dg2.size > 256) {
            return FaceImage(dg2, "未知格式（原始数据组）", outerTag)
        }

        return null
    }

    // ----------------------------------------------------------------- DG11

    /** DG11 中的一个信息项。 */
    data class Dg11Item(val tag: Int, val label: String, val value: String)

    private val DG11_LABELS = mapOf(
        // 注意：中国签发的通行证对 5F0F / 5F10 的用法与 ICAO 9303-10 的默认含义不同，
        // 实测往来港澳/台湾通行证中 5F0F 存的是英文姓名（MRZ 格式）、5F10 存的是公民身份号码。
        0x5F0E to "姓名（母语）",
        0x5F0F to "姓名（英文，MRZ 格式）/ 出生地",
        0x5F10 to "公民身份号码 / 个人编号",
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

    /**
     * DG11 中的**英文姓名**（MRZ 格式，如 `HUANG<<QUANMIAO`）。
     *
     * 中国签发的往来港澳/台湾通行证把英文姓名放在 0x5F0F
     * （ICAO 9303-10 中该 tag 的默认含义是"出生地"）。
     * 这里用「是否含 `<<` 分隔符」来区分：MRZ 姓名一定含 `<<`，出生地不会。
     */
    fun extractEnglishName(items: List<Dg11Item>): String? =
        items.firstOrNull { it.tag == 0x5F0F }?.value
            ?.replace('\u0000', ' ')
            ?.trim()
            ?.takeIf { it.contains("<<") }

    /** DG11 中的**公民身份号码**（18 位，中国证件）。 */
    fun extractIdNumber(items: List<Dg11Item>): String? =
        items.firstOrNull { it.tag == 0x5F10 }?.value
            ?.replace("\u0000", "")
            ?.replace("\u0001", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * 从 18 位公民身份号码推断性别。
     *
     * 中国公民身份号码第 17 位为顺序码，奇数为男、偶数为女。
     * 往来港澳/台湾通行证的 MRZ 中没有独立的性别字段，DG11 提供了身份号码，
     * 因此用它来补全性别。
     */
    fun genderFromIdNumber(idNumber: String?): String? {
        val id = idNumber?.trim().orEmpty()
        if (id.length != 18 || !id.all { it.isDigit() }) return null
        val seq = id[16] - '0'
        return if (seq % 2 == 1) "男 / M" else "女 / F"
    }

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

    /** DG15 中提取出的主动认证公钥。 */
    data class Dg15Key(
        /** "RSA" 或 "EC"。 */
        val keyType: String,
        /** SubjectPublicKeyInfo 中的算法 OID。 */
        val algorithmOid: String,
        val publicKey: java.security.PublicKey,
        /** 密钥位数（RSA）或曲线名（EC），用于界面展示。 */
        val detail: String
    )

    /**
     * 从 DG15 中提取主动认证公钥。
     *
     * DG15 结构（ICAO 9303-10）：
     * ```
     * 6F <len> SEQUENCE { AlgorithmIdentifier, BIT STRING }
     * ```
     * 内层 SEQUENCE 就是标准的 SubjectPublicKeyInfo，可直接交给 KeyFactory 解析。
     */
    fun parseDg15PublicKey(dg15: ByteArray): Dg15Key? {
        val root = Tlv.parse(dg15).firstOrNull() ?: return null
        val spki = root.find(0x30) ?: return null
        val algSeq = spki.child(0x30) ?: return null
        val oidBytes = algSeq.child(0x06)?.value ?: return null
        val oid = decodeOid(oidBytes)

        val keyType = when {
            oid.startsWith("1.2.840.113549.1.1") -> "RSA"
            oid == "1.2.840.10045.2.1" -> "EC"
            else -> return null
        }

        val spkiDer = dg15.copyOfRange(spki.rawStart, spki.rawStart + spki.rawLength)
        val key = try {
            java.security.KeyFactory.getInstance(keyType)
                .generatePublic(java.security.spec.X509EncodedKeySpec(spkiDer))
        } catch (_: Exception) {
            return null
        }

        val detail = when (key) {
            is java.security.interfaces.RSAPublicKey -> "RSA ${key.modulus.bitLength()} 位"
            is java.security.interfaces.ECPublicKey ->
                "EC ${key.params.curve.field.fieldSize} 位"
            else -> keyType
        }
        return Dg15Key(keyType, oid, key, detail)
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
