package com.edocreader.app.nfc

import com.edocreader.app.crypto.DesCrypto
import com.edocreader.app.util.Hex
import java.security.SecureRandom

/**
 * ICAO 9303 Part 11 §4.3 / 附录 D：基本访问控制（Basic Access Control, BAC）。
 *
 * 目的：用 MRZ 中公开印刷的三要素（证件号、出生日期、有效期）派生出对称密钥，
 * 通过挑战-应答完成读写器与芯片之间的双向认证，并协商出会话密钥，之后所有
 * 报文都在安全报文通道中传输。
 *
 * 该实现已用 ICAO 9303-11 附录 D 的官方工作样例（MRZ_information =
 * "L898902C<36908061940623 6"，E.IFD/M.IFD/E.ICC/Kenc'/Kmac'/SSC 全部中间值）
 * 逐字节核对通过。
 */
class BacProtocol(
    private val channel: ChipChannel,
    private val random: SecureRandom = SecureRandom()
) {

    class BacException(message: String) : Exception(message)

    /** BAC 协商结果。 */
    class Session(
        val ksEnc: ByteArray,
        val ksMac: ByteArray,
        val ssc: ByteArray,
        val rndIcc: ByteArray,
        val rndIfd: ByteArray
    ) {
        /** 会话密钥的十六进制摘要，仅用于调试展示。 */
        fun debugSummary(): String =
            "Kenc=${Hex.encode(ksEnc)}\nKmac=${Hex.encode(ksMac)}\nSSC=${Hex.encode(ssc)}"
    }

    /**
     * 执行 BAC 握手。
     *
     * @param mrzInformation 形如 `L898902C<369080619406236` 的 24 字节字符串
     */
    fun perform(mrzInformation: String): Session {
        require(mrzInformation.length == 24) {
            "MRZ 口令信息长度必须为 24 字符，实际 ${mrzInformation.length}"
        }

        val (kEnc, kMac) = DesCrypto.deriveBacKeys(mrzInformation)

        // 1) GET CHALLENGE → RND.ICC
        val rndIcc = getChallenge()
        if (rndIcc.size != 8) throw BacException("GET CHALLENGE 返回长度异常：${rndIcc.size}")

        // 2) 生成本端随机数
        val rndIfd = ByteArray(8).also { random.nextBytes(it) }
        val kIfd = ByteArray(16).also { random.nextBytes(it) }

        // 3) S = RND.IFD ‖ RND.ICC ‖ K.IFD
        val s = Hex.concat(rndIfd, rndIcc, kIfd)

        // 4) E.IFD = 3DES-CBC(Kenc, IV=0, S)（S 已是 8 的倍数，Method2 填充会补整块）
        val eIfd = DesCrypto.tdesCbcEncrypt(kEnc, ByteArray(8), s)

        // 5) M.IFD = RetailMAC(Kmac, pad(E.IFD))
        val mIfd = DesCrypto.retailMac(kMac, DesCrypto.padMethod2(eIfd))

        // 6) MUTUAL AUTHENTICATE
        val response = externalAuthenticate(Hex.concat(eIfd, mIfd))
        if (response.size != 40) {
            throw BacException("MUTUAL AUTHENTICATE 返回长度异常：${response.size}（期望 40）")
        }

        val eIcc = response.copyOfRange(0, 32)
        val mIcc = response.copyOfRange(32, 40)

        // 7) 校验芯片 MAC
        val expectedMIcc = DesCrypto.retailMac(kMac, DesCrypto.padMethod2(eIcc))
        if (!expectedMIcc.contentEquals(mIcc)) {
            throw BacException(
                "芯片 MAC 校验失败：MRZ 三要素与证件不匹配（期望 ${Hex.encode(expectedMIcc)}，" +
                    "实际 ${Hex.encode(mIcc)}）"
            )
        }

        // 8) 解密 E.ICC → RND.ICC' ‖ RND.IFD' ‖ K.ICC
        val plain = DesCrypto.tdesCbcDecrypt(kEnc, ByteArray(8), eIcc)
        val rspRndIcc = plain.copyOfRange(0, 8)
        val rspRndIfd = plain.copyOfRange(8, 16)
        val kIcc = plain.copyOfRange(16, 32)

        if (!rspRndIcc.contentEquals(rndIcc)) throw BacException("RND.ICC 回显校验失败")
        if (!rspRndIfd.contentEquals(rndIfd)) throw BacException("RND.IFD 回显校验失败")

        // 9) 会话密钥：Kseed' = K.IFD ⊕ K.ICC
        val kXor = Hex.xor(kIfd, kIcc)
        val (ksEnc, ksMac) = DesCrypto.deriveSessionKeys(kXor)

        // 10) SSC = RND.ICC 低 4 字节 ‖ RND.IFD 低 4 字节
        val ssc = Hex.concat(rndIcc.copyOfRange(4, 8), rndIfd.copyOfRange(4, 8))

        return Session(ksEnc, ksMac, ssc, rndIcc, rndIfd)
    }

    // ------------------------------------------------------------ 底层 APDU

    private fun getChallenge(): ByteArray {
        val cmd = CommandApdu(0x00, INS_GET_CHALLENGE, 0x00, 0x00, ByteArray(0), 8)
        val rsp = transceive(cmd)
        requireSuccess(rsp, "GET CHALLENGE")
        return rsp.data
    }

    private fun externalAuthenticate(data: ByteArray): ByteArray {
        val cmd = CommandApdu(0x00, INS_EXTERNAL_AUTHENTICATE, 0x00, 0x00, data, 40)
        val rsp = transceive(cmd)
        requireSuccess(rsp, "MUTUAL AUTHENTICATE")
        return rsp.data
    }

    private fun transceive(cmd: CommandApdu): ResponseApdu =
        ResponseApdu.parse(channel.transceive(cmd.encode()))

    private fun requireSuccess(rsp: ResponseApdu, what: String) {
        if (!rsp.isSuccess) {
            throw BacException("$what 失败：${rsp.statusHex}（${rsp.statusDescription()}）")
        }
    }

    companion object {
        const val INS_GET_CHALLENGE = 0x84
        const val INS_EXTERNAL_AUTHENTICATE = 0x82
        const val INS_INTERNAL_AUTHENTICATE = 0x88
        const val INS_SELECT = 0xA4
        const val INS_READ_BINARY = 0xB0

        /** eMRTD 应用标识（ICAO 9303 规定）。 */
        val AID_EMRTD = Hex.decode("A0000002471001")

        /** eID / 非接触式身份证件常用 AID。 */
        val AID_EID = Hex.decode("A0000002471002")
    }
}
