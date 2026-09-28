package com.edocreader.app.nfc

/**
 * 极简 BER-TLV 解析器（ICAO 9303 Part 10 / ISO 7816-4 所用子集）。
 *
 * 支持多字节 tag 与定长/不定长之外的常规长度编码（短形式与 0x81/0x82/0x83 长形式），
 * 保留每个节点在原始缓冲区中的字节范围，便于原样输出用于 MAC 计算与签名校验。
 */
class TlvNode(
    val tag: Int,
    val value: ByteArray,
    /** 整个 TLV（tag + length + value）在原始数据中的起点。 */
    val rawStart: Int,
    /** 整个 TLV 的字节长度。 */
    val rawLength: Int,
    val children: List<TlvNode> = emptyList()
) {
    val tagHex: String get() = String.format("%02X", tag)

    fun child(tag: Int): TlvNode? = children.firstOrNull { it.tag == tag }

    fun find(tag: Int): TlvNode? {
        if (this.tag == tag) return this
        for (c in children) {
            val r = c.find(tag)
            if (r != null) return r
        }
        return null
    }

    /** 深度优先展开所有节点（含自身）。 */
    fun flatten(): List<TlvNode> {
        val out = mutableListOf<TlvNode>()
        out.add(this)
        children.forEach { out.addAll(it.flatten()) }
        return out
    }

    fun valueAsString(): String = String(value, Charsets.UTF_8)

    override fun toString(): String = "$tagHex(${value.size}B)"
}

object Tlv {

    /**
     * 解析一段 BER-TLV 数据，返回顶层节点列表。
     * 遇到无法解析的尾部字节时停止（宽容处理，避免因填充字节导致整体失败）。
     */
    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<TlvNode> {
        val nodes = mutableListOf<TlvNode>()
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val node = parseOne(data, pos, end) ?: break
            nodes.add(node)
            pos += node.rawLength
            if (node.rawLength <= 0) break
        }
        return nodes
    }

    /** 解析单个 TLV，失败返回 null。 */
    fun parseOne(data: ByteArray, start: Int, end: Int = data.size): TlvNode? {
        if (start >= end) return null
        var pos = start

        // ---- tag ----
        var tag = data[pos].toInt() and 0xFF
        pos++
        if ((tag and 0x1F) == 0x1F) {
            // 多字节 tag
            while (pos < end) {
                val b = data[pos].toInt() and 0xFF
                tag = (tag shl 8) or b
                pos++
                if ((b and 0x80) == 0) break
            }
        }

        // ---- length ----
        if (pos >= end) return null
        val first = data[pos].toInt() and 0xFF
        pos++
        var valueLength: Int
        if (first < 0x80) {
            valueLength = first
        } else if (first == 0x80) {
            // 不定长：本工程用不到，直接判定失败
            return null
        } else {
            val count = first and 0x7F
            if (count > 4 || pos + count > end) return null
            valueLength = 0
            repeat(count) {
                valueLength = (valueLength shl 8) or (data[pos].toInt() and 0xFF)
                pos++
            }
        }

        if (pos + valueLength > end) return null
        val valueStart = pos
        val value = data.copyOfRange(valueStart, valueStart + valueLength)
        val rawLength = (valueStart - start) + valueLength

        // ---- 递归解析子节点（构造类型 tag：bit6 = 1）----
        // 注意：必须在**原缓冲区**上按绝对偏移继续解析，而不是在 value 切片上。
        // 否则子节点的 rawStart 会变成相对父节点 value 的偏移，
        // 导致调用方无法用 rawStart 从原始数据中切出该节点的完整 TLV。
        val constructed = (tag and 0x20) != 0
        val children = if (constructed) parse(data, valueStart, valueLength) else emptyList()

        return TlvNode(tag, value, start, rawLength, children)
    }

    /** 编码一个 TLV。tag 使用数值形式（如 0x87、0x5F0E）。 */
    fun encode(tag: Int, value: ByteArray): ByteArray {
        val tagBytes = encodeTag(tag)
        val lenBytes = encodeLength(value.size)
        return tagBytes + lenBytes + value
    }

    fun encodeTag(tag: Int): ByteArray {
        if (tag <= 0xFF) return byteArrayOf(tag.toByte())
        val bytes = mutableListOf<Byte>()
        var t = tag
        while (t > 0) {
            bytes.add(0, (t and 0xFF).toByte())
            t = t ushr 8
        }
        // 首字节低 5 位若为 0x1F 则表示后续还有字节，这里按最长 2 字节处理即可
        if (bytes.size == 2) bytes[0] = (bytes[0].toInt() or 0x1F).toByte()
        return bytes.toByteArray()
    }

    fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        length <= 0xFFFF -> byteArrayOf(0x82.toByte(), ((length ushr 8) and 0xFF).toByte(), (length and 0xFF).toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            ((length ushr 16) and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
    }
}
