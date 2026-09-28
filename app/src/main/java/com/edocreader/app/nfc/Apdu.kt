package com.edocreader.app.nfc

import com.edocreader.app.util.Hex

/**
 * APDU 编解码（ISO 7816-4 短 APDU 子集，足够覆盖 eMRTD 芯片的全部操作）。
 */

/** 命令 APDU。 */
data class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val data: ByteArray = ByteArray(0),
    /** null 表示不带 Le；0 表示 Le = 256（短 APDU 的"最大长度"）。 */
    val le: Int? = null
) {
    val haveData: Boolean get() = data.isNotEmpty()
    val haveLe: Boolean get() = le != null
    val isExtended: Boolean get() = data.size > 255

    fun encodeHeader(): ByteArray = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())

    fun encodeLe(): ByteArray {
        val v = le ?: return ByteArray(0)
        return if (v >= 256) byteArrayOf(0x00) else byteArrayOf(v.toByte())
    }

    fun encode(): ByteArray {
        val header = encodeHeader()
        val leBytes = encodeLe()
        return if (data.isEmpty()) {
            header + leBytes
        } else if (data.size <= 255) {
            header + byteArrayOf(data.size.toByte()) + data + leBytes
        } else {
            // 扩展长度（本工程一般不会用到，但保留以增强兼容性）
            val lc = byteArrayOf(
                0x00,
                ((data.size ushr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte()
            )
            val extLe = if (leBytes.isEmpty()) ByteArray(0)
            else if (leBytes.size == 1) byteArrayOf(0x00, 0x00)
            else leBytes
            header + lc + data + extLe
        }
    }

    override fun toString(): String = Hex.encodeSpaced(encode())

    override fun equals(other: Any?): Boolean =
        other is CommandApdu && encode().contentEquals(other.encode())

    override fun hashCode(): Int = encode().contentHashCode()
}

/** 响应 APDU。 */
class ResponseApdu(
    val data: ByteArray,
    val sw1: Int,
    val sw2: Int
) {
    val status: Int get() = (sw1 shl 8) or sw2
    val statusHex: String get() = String.format("%04X", status)

    val isSuccess: Boolean get() = status == 0x9000
    val isWarning: Boolean get() = sw1 == 0x62 || sw1 == 0x63

    /** 6282：文件结束（读越界）。 */
    val isEndOfFile: Boolean get() = status == 0x6282

    /** 6A82：文件未找到。 */
    val isFileNotFound: Boolean get() = status == 0x6A82

    /** 6982：安全状态不满足（通常表示 BAC 未通过或安全报文错误）。 */
    val isSecurityStatusNotSatisfied: Boolean get() = status == 0x6982

    fun statusDescription(): String = when (status) {
        0x9000 -> "成功"
        0x6282 -> "已到文件末尾"
        0x6A82 -> "文件未找到"
        0x6982 -> "安全状态不满足（安全报文或认证失败）"
        0x6A86 -> "P1/P2 参数不正确"
        0x6B00 -> "参数越界"
        0x6D00 -> "指令不支持"
        0x6E00 -> "类别不支持"
        0x6300 -> "认证失败"
        0x6985 -> "使用条件不满足"
        else -> "未知状态字"
    }

    override fun toString(): String = "$statusHex (${statusDescription()}) data=${Hex.encode(data)}"

    companion object {
        /**
         * 解析响应字节流。注意：由于使用了安全报文，SW1SW2 也可能出现在数据域中
         * （tag 0x99），这里仅解析"外层"的裸 SW。
         */
        fun parse(bytes: ByteArray): ResponseApdu {
            require(bytes.size >= 2) { "响应 APDU 至少需要 2 字节状态字，实际 ${bytes.size} 字节" }
            val data = bytes.copyOfRange(0, bytes.size - 2)
            val sw1 = bytes[bytes.size - 2].toInt() and 0xFF
            val sw2 = bytes[bytes.size - 1].toInt() and 0xFF
            return ResponseApdu(data, sw1, sw2)
        }
    }
}

/** 芯片交互接口，便于在单元测试中注入模拟实现。 */
interface ChipChannel {
    /** 发送一条（未加安全报文的）命令，返回响应。 */
    fun transceive(command: ByteArray): ByteArray
}

/** 基于 NFC IsoDep 的通道实现。 */
class IsoDepChannel(private val isoDep: android.nfc.tech.IsoDep) : ChipChannel {

    init {
        isoDep.timeout = 12_000
    }

    override fun transceive(command: ByteArray): ByteArray {
        if (!isoDep.isConnected) isoDep.connect()
        return isoDep.transceive(command)
    }

    fun close() {
        runCatching { isoDep.close() }
    }
}
