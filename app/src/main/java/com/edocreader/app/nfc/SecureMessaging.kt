package com.edocreader.app.nfc

import com.edocreader.app.crypto.DesCrypto
import com.edocreader.app.util.Hex

/**
 * ICAO 9303 Part 11 §9.8 安全报文（Secure Messaging，3DES 版本）。
 *
 * 报文结构（ISO 7816-4）：
 *  - 命令 APDU：`[DO'85' 或 DO'87'] [DO'97'] DO'8E`
 *  - 响应 APDU：`[DO'85' 或 DO'87'] [DO'99'] DO'8E`
 *  - INS 为偶数用 DO'87'，为奇数用 DO'85'
 *  - 计算 MAC 时命令头必须参与，因此 CLA 固定为 0x0C
 *
 * 说明：3DES 模式下 CBC 的 IV 恒为全 0（AES 才需要 IV = K(SSC)），
 * 这是 ICAO 9303 Part 11 与 ISO 7816-4 的既有约定。
 */
class SecureMessaging(
    private val ksEnc: ByteArray,
    private val ksMac: ByteArray,
    ssc: ByteArray
) {

    private val sscCounter: ByteArray = ssc.copyOf()

    /** 供调试展示当前 SSC。 */
    val ssc: ByteArray get() = sscCounter.copyOf()

    private val zeroIv = ByteArray(DesCrypto.BLOCK_SIZE)

    // ------------------------------------------------------------ 命令加封

    fun wrap(command: CommandApdu): CommandApdu {
        Hex.increment(sscCounter)

        val nodes = mutableListOf<ByteArray>()

        // DO'87' / DO'85'
        if (command.haveData) {
            val tag = if (command.ins % 2 == 0) 0x87 else 0x85
            val encrypted = DesCrypto.tdesCbcEncrypt(
                ksEnc, zeroIv, DesCrypto.padMethod2(command.data)
            )
            // 值以 0x01 开头表示"已填充的明文"
            nodes.add(Tlv.encode(tag, byteArrayOf(0x01) + encrypted))
        }

        // DO'97'
        if (command.haveLe) {
            nodes.add(Tlv.encode(0x97, command.encodeLe()))
        }

        // DO'8E'
        val headerPadded = DesCrypto.padMethod2(command.encodeHeader())
        headerPadded[0] = CLA_MASK.toByte()
        val macInput = Hex.concat(sscCounter, headerPadded, *nodes.toTypedArray())
        val mac = DesCrypto.retailMac(ksMac, DesCrypto.padMethod2(macInput))
        nodes.add(Tlv.encode(0x8E, mac))

        val body = Hex.concat(*nodes.toTypedArray())

        // 安全报文下 Le 恒为 256
        return CommandApdu(CLA_MASK, command.ins, command.p1, command.p2, body, 256)
    }

    // ------------------------------------------------------------ 响应解封

    class SmDecodeException(message: String) : Exception(message)

    /**
     * 解封一条响应 APDU。
     *
     * @param rawBytes 芯片返回的原始字节（含末尾 SW1SW2）
     */
    fun unwrap(rawBytes: ByteArray): ResponseApdu {
        val plain = ResponseApdu.parse(rawBytes)

        if (plain.data.isEmpty()) {
            // 裸响应：芯片很可能没有接受我们的 SM 命令，回退 SSC 以保持与芯片同步
            Hex.decrement(sscCounter)
            return plain
        }

        Hex.increment(sscCounter)

        val nodes = Tlv.parse(plain.data)
        val tag85 = nodes.firstOrNull { it.tag == 0x85 }
        val tag87 = nodes.firstOrNull { it.tag == 0x87 }
        val tag99 = nodes.firstOrNull { it.tag == 0x99 }
        val tag8e = nodes.firstOrNull { it.tag == 0x8E }
            ?: throw SmDecodeException("安全报文响应缺少 DO'8E'（MAC）")

        // 校验 MAC：SSC ‖ DO'85' ‖ DO'87' ‖ DO'99'
        val macInput = Hex.concat(
            sscCounter,
            tag85?.let { plain.data.copyOfRange(it.rawStart, it.rawStart + it.rawLength) } ?: ByteArray(0),
            tag87?.let { plain.data.copyOfRange(it.rawStart, it.rawStart + it.rawLength) } ?: ByteArray(0),
            tag99?.let { plain.data.copyOfRange(it.rawStart, it.rawStart + it.rawLength) } ?: ByteArray(0)
        )
        val expectedMac = DesCrypto.retailMac(ksMac, DesCrypto.padMethod2(macInput))
        if (!expectedMac.contentEquals(tag8e.value)) {
            throw SmDecodeException(
                "安全报文 MAC 校验失败（期望 ${Hex.encode(expectedMac)}，实际 ${Hex.encode(tag8e.value)}）"
            )
        }

        val protectedSw = tag99?.value
            ?: throw SmDecodeException("安全报文响应缺少 DO'99'（状态字）")
        if (protectedSw.size != 2) {
            throw SmDecodeException("DO'99' 长度必须为 2，实际 ${protectedSw.size}")
        }
        val sw = ((protectedSw[0].toInt() and 0xFF) shl 8) or (protectedSw[1].toInt() and 0xFF)
        if (sw != plain.status) {
            throw SmDecodeException(
                "外层状态字(${plain.statusHex})与安全报文内状态字(${String.format("%04X", sw)})不一致"
            )
        }

        val dataNode = tag87 ?: tag85
        val data = if (dataNode != null) decryptDataObject(dataNode.value) else ByteArray(0)

        return ResponseApdu(data, plain.sw1, plain.sw2)
    }

    private fun decryptDataObject(value: ByteArray): ByteArray {
        if (value.isEmpty() || value[0] != 0x01.toByte()) {
            throw SmDecodeException("DO'87'/'85' 的首字节必须为 0x01")
        }
        val cipherText = value.copyOfRange(1, value.size)
        if (cipherText.size % DesCrypto.BLOCK_SIZE != 0) {
            throw SmDecodeException("DO'87'/'85' 密文长度必须是 8 的倍数，实际 ${cipherText.size}")
        }
        val decrypted = DesCrypto.tdesCbcDecrypt(ksEnc, zeroIv, cipherText)
        return DesCrypto.unpadMethod2(decrypted)
    }

    companion object {
        const val CLA_MASK: Int = 0x0C
    }
}
