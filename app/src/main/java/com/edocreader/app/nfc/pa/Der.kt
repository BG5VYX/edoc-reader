package com.edocreader.app.nfc.pa

/**
 * 最小可用的 DER 解析器。
 *
 * 之所以自己实现而不引入 ASN.1 库：一是避免为 APK 引入数 MB 的依赖，二是签名校验
 * 需要**原始字节**（signedAttrs 必须按原始 DER 编码参与验签，任何重新编码都可能
 * 改变结果），因此每个节点都保留完整的 TLV 原始字节。
 */
class DerNode(
    /** 首字节（含 class / constructed / tag number 高位）。 */
    val tag: Int,
    /** 完整 TLV（tag + length + value）的原始字节。 */
    val raw: ByteArray,
    /** 仅 value 部分。 */
    val value: ByteArray,
    val children: List<DerNode> = emptyList()
) {
    val isConstructed: Boolean get() = (tag and 0x20) != 0
    val tagClass: Int get() = tag and 0xC0

    fun childAt(index: Int): DerNode? = children.getOrNull(index)

    fun firstChildWithTag(tag: Int): DerNode? = children.firstOrNull { it.tag == tag }

    fun findAll(tag: Int): List<DerNode> {
        val out = mutableListOf<DerNode>()
        if (this.tag == tag) out.add(this)
        children.forEach { out.addAll(it.findAll(tag)) }
        return out
    }

    fun asInt(): Int {
        var v = 0
        for (b in value) v = (v shl 8) or (b.toInt() and 0xFF)
        return v
    }

    override fun toString(): String = "Der(tag=%02X, len=%d)".format(tag, value.size)
}

object Der {

    class DerException(message: String) : Exception(message)

    /** 解析一段 DER，返回顶层节点（通常只有一个）。 */
    fun parse(data: ByteArray): List<DerNode> {
        val nodes = mutableListOf<DerNode>()
        var pos = 0
        while (pos < data.size) {
            val node = parseAt(data, pos) ?: break
            nodes.add(node)
            pos += node.raw.size
        }
        return nodes
    }

    /** 解析单个节点；失败返回 null。 */
    fun parseAt(data: ByteArray, start: Int): DerNode? {
        if (start >= data.size) return null
        var pos = start
        val tag = data[pos].toInt() and 0xFF
        pos++

        // 高 tag number 形式（本工程涉及的 tag 都是单字节，保留处理以增强健壮性）
        if ((tag and 0x1F) == 0x1F) {
            while (pos < data.size && (data[pos].toInt() and 0x80) != 0) pos++
            pos++
        }

        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        pos++
        val length: Int
        if (first < 0x80) {
            length = first
        } else if (first == 0x80) {
            throw DerException("不支持不定长编码")
        } else {
            val count = first and 0x7F
            if (count > 4 || pos + count > data.size) return null
            var v = 0
            repeat(count) {
                v = (v shl 8) or (data[pos].toInt() and 0xFF)
                pos++
            }
            length = v
        }

        if (pos + length > data.size) return null
        val value = data.copyOfRange(pos, pos + length)
        val raw = data.copyOfRange(start, pos + length)

        val constructed = (tag and 0x20) != 0
        val children = if (constructed) parse(value) else emptyList()

        return DerNode(tag, raw, value, children)
    }

    // ------------------------------------------------------------- OID 工具

    /** 把 OID 的 DER 内容编码转为点分字符串。 */
    fun decodeOid(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder()
        val first = bytes[0].toInt() and 0xFF
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

    /** 摘要算法 OID → JCE 名称。 */
    fun hashAlgorithmFor(oid: String): String? = when (oid) {
        "1.3.14.3.2.26" -> "SHA-1"
        "2.16.840.1.101.3.4.2.4" -> "SHA-224"
        "2.16.840.1.101.3.4.2.1" -> "SHA-256"
        "2.16.840.1.101.3.4.2.2" -> "SHA-384"
        "2.16.840.1.101.3.4.2.3" -> "SHA-512"
        "2.16.840.1.101.3.4.2.5" -> "SHA-512/224"
        "2.16.840.1.101.3.4.2.6" -> "SHA-512/256"
        else -> null
    }

    /** 签名算法 OID → JCE 名称（与摘要算法组合后使用）。 */
    fun signatureAlgorithmFor(oid: String, digest: String): String? = when (oid) {
        "1.2.840.113549.1.1.5" -> "SHA1withRSA"
        "1.2.840.113549.1.1.11" -> "SHA256withRSA"
        "1.2.840.113549.1.1.12" -> "SHA384withRSA"
        "1.2.840.113549.1.1.13" -> "SHA512withRSA"
        "1.2.840.113549.1.1.10" -> "SHA256withRSA" // RSASSA-PSS，JDK 需显式参数，这里退化处理
        "1.2.840.10045.4.1" -> "SHA1withECDSA"
        "1.2.840.10045.4.3.2" -> "SHA256withECDSA"
        "1.2.840.10045.4.3.3" -> "SHA384withECDSA"
        "1.2.840.10045.4.3.4" -> "SHA512withECDSA"
        else -> when (digest) {
            "SHA-1" -> "SHA1withRSA"
            "SHA-256" -> "SHA256withRSA"
            "SHA-384" -> "SHA384withRSA"
            "SHA-512" -> "SHA512withRSA"
            else -> null
        }
    }
}
