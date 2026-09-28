package com.edocreader.app.nfc

import com.edocreader.app.util.Hex
import java.io.ByteArrayOutputStream

/**
 * eMRTD 芯片文件系统读取。
 *
 * 安全报文下单条响应 APDU 最长 256 字节，而 DO'87'（密文）、DO'99'（状态字）、
 * DO'8E'（MAC）都要占用空间，因此单次 READ BINARY 的明文上限取 224 字节：
 *   明文 224 → 填充后 232 → DO'87' = 3 + 1 + 232 = 236，再加 DO'99'(4) + DO'8E'(10) = 250 ≤ 256
 */
class EmrtdFileSystem(
    private val channel: ChipChannel,
    private val sm: SecureMessaging,
    private val log: (String) -> Unit = {}
) {

    class FileSystemException(message: String) : Exception(message)

    companion object {
        /** 单次 READ BINARY 的最大明文长度。 */
        const val READ_CHUNK = 224

        const val EF_COM = 0x011E
        const val EF_SOD = 0x011D
        const val EF_DG1 = 0x0101
        const val EF_DG2 = 0x0102
        const val EF_DG11 = 0x010B
        const val EF_DG12 = 0x010C
        const val EF_DG13 = 0x010D
        const val EF_DG14 = 0x010E
        const val EF_DG15 = 0x010F
        const val EF_DG16 = 0x0110

        /** 单个 EF 的长度上限，防止异常数据导致内存问题。 */
        private const val MAX_FILE_SIZE = 128 * 1024
    }

    /** 选择 eMRTD 应用（未受安全报文保护）。 */
    fun selectApplication(): Boolean {
        val cmd = CommandApdu(0x00, BacProtocol.INS_SELECT, 0x04, 0x0C, BacProtocol.AID_EMRTD, null)
        val rsp = ResponseApdu.parse(channel.transceive(cmd.encode()))
        log("SELECT AID → ${rsp.statusHex} ${rsp.statusDescription()}")
        if (rsp.isSuccess) return true

        // 少数证件使用 eID 应用标识
        val alt = CommandApdu(0x00, BacProtocol.INS_SELECT, 0x04, 0x0C, BacProtocol.AID_EID, null)
        val altRsp = ResponseApdu.parse(channel.transceive(alt.encode()))
        log("SELECT AID(eID) → ${altRsp.statusHex} ${altRsp.statusDescription()}")
        return altRsp.isSuccess
    }

    /** 选择 MF（部分证件在读 EF 前需要先回到主文件）。 */
    fun selectMasterFile(): Boolean {
        val cmd = CommandApdu(0x00, BacProtocol.INS_SELECT, 0x00, 0x0C)
        val rsp = send(cmd)
        return rsp.isSuccess
    }

    fun selectEf(fileId: Int): Boolean {
        val cmd = CommandApdu(0x00, BacProtocol.INS_SELECT, 0x02, 0x0C, Hex.fromShort(fileId), null)
        val rsp = send(cmd)
        log("SELECT EF ${String.format("%04X", fileId)} → ${rsp.statusHex}")
        return rsp.isSuccess
    }

    /** 读取整个 EF；文件不存在返回 null。 */
    fun readFile(fileId: Int): ByteArray? {
        if (!selectEf(fileId)) return null

        val header = readBinary(0, 4)
        if (header.size < 2) {
            log("EF ${String.format("%04X", fileId)} 头部读取不足：${header.size} 字节")
            return null
        }
        val total = computeTotalLength(header)
        if (total <= 0 || total > MAX_FILE_SIZE) {
            log("EF ${String.format("%04X", fileId)} 长度异常：$total")
            return null
        }

        val out = ByteArrayOutputStream(total)
        out.write(header)

        while (out.size() < total) {
            val remaining = total - out.size()
            val want = minOf(remaining, READ_CHUNK)
            val chunk = readBinary(out.size(), want)
            if (chunk.isEmpty()) break
            out.write(chunk)
        }

        val data = out.toByteArray()
        log("EF ${String.format("%04X", fileId)} 读取 ${data.size}/$total 字节")
        return if (data.size >= total) data.copyOfRange(0, total) else data
    }

    /**
     * 由文件起始的 4 个字节推算文件总长度（TLV 头 + 内容）。
     */
    private fun computeTotalLength(header: ByteArray): Int {
        var pos = 0
        // tag
        if ((header[0].toInt() and 0x1F) == 0x1F) {
            while (pos < header.size && (header[pos].toInt() and 0x80) != 0) pos++
        }
        pos++
        if (pos >= header.size) return -1
        val first = header[pos].toInt() and 0xFF
        pos++
        val valueLength: Int = when {
            first < 0x80 -> first
            first == 0x80 -> return -1
            else -> {
                val count = first and 0x7F
                if (count > 3 || pos + count > header.size) return -1
                var v = 0
                repeat(count) {
                    v = (v shl 8) or (header[pos].toInt() and 0xFF)
                    pos++
                }
                v
            }
        }
        return pos + valueLength
    }

    private fun readBinary(offset: Int, length: Int): ByteArray {
        val cmd = CommandApdu(
            0x00, BacProtocol.INS_READ_BINARY,
            (offset ushr 8) and 0xFF, offset and 0xFF,
            ByteArray(0), length
        )
        val rsp = send(cmd)
        if (!rsp.isSuccess && !rsp.isEndOfFile) {
            log("READ BINARY(off=$offset,len=$length) → ${rsp.statusHex}")
            return ByteArray(0)
        }
        return rsp.data
    }

    /** 经安全报文通道发送一条命令。 */
    private fun send(cmd: CommandApdu): ResponseApdu {
        val wrapped = sm.wrap(cmd)
        val raw = channel.transceive(wrapped.encode())
        return sm.unwrap(raw)
    }

    /**
     * 经安全报文通道发送一条任意命令。
     * 供主动认证（INTERNAL AUTHENTICATE）等不涉及文件读写的操作使用。
     */
    fun sendCommand(cmd: CommandApdu): ResponseApdu = send(cmd)
}
