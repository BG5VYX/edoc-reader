package com.edocreader.app.util

/**
 * 字节 / 十六进制 转换工具。
 */
object Hex {

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX_CHARS[v ushr 4]
            out[i++] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    fun encode(bytes: ByteArray, offset: Int, length: Int): String =
        encode(bytes.copyOfRange(offset, offset + length))

    /** 每两个十六进制字符一组，中间用空格分隔，便于阅读 APDU 日志。 */
    fun encodeSpaced(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    fun decode(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "十六进制字符串长度必须为偶数：${clean.length}" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "非法的十六进制字符，位置 $i" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun toInt(bytes: ByteArray): Int {
        var v = 0
        for (b in bytes) v = (v shl 8) or (b.toInt() and 0xFF)
        return v
    }

    fun fromInt(value: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    fun fromShort(value: Int): ByteArray = byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "异或运算的两个数组长度必须一致：${a.size} != ${b.size}" }
        val out = ByteArray(a.size)
        for (i in a.indices) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    /** 大端序 8 字节计数器 +1（用于安全报文 SSC）。 */
    fun increment(counter: ByteArray) {
        for (i in counter.indices.reversed()) {
            counter[i] = (counter[i] + 1).toByte()
            if (counter[i] != 0.toByte()) break
        }
    }

    fun decrement(counter: ByteArray) {
        for (i in counter.indices.reversed()) {
            counter[i] = (counter[i] - 1).toByte()
            if (counter[i] != 0xFF.toByte()) break
        }
    }
}
