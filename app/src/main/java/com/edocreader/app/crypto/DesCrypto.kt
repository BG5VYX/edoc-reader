package com.edocreader.app.crypto

import com.edocreader.app.util.Hex
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ICAO 9303 Part 11 所要求的对称密码原语实现。
 *
 * 这里刻意只使用 JDK/Android 自带的 JCE（DES / DESede / SHA-1），不引入任何第三方
 * 密码库，从而保证行为与规范一致、可审计。
 *
 * 实现内容：
 *  - 单块 DES 加解密（ECB / NoPadding）
 *  - 3DES-CBC 加解密（16 字节密钥按 K1|K2|K1 扩展）
 *  - ISO/IEC 9797-1 Padding Method 2（0x80 后补 0x00）
 *  - ISO/IEC 9797-1 MAC Algorithm 3（Retail MAC）
 *  - ICAO 9303 KDF（SHA-1(K ‖ counter) 取前 16 字节 + DES 奇偶校验位调整）
 */
object DesCrypto {

    const val BLOCK_SIZE = 8

    private val DES_ECB_ENC = "DES/ECB/NoPadding"
    private val DES_ECB_DEC = "DES/ECB/NoPadding"
    private val TDES_CBC_ENC = "DESede/CBC/NoPadding"
    private val TDES_CBC_DEC = "DESede/CBC/NoPadding"

    // ---------------------------------------------------------------- DES 单块

    fun desEncrypt(key8: ByteArray, block: ByteArray): ByteArray {
        require(key8.size == 8) { "DES 密钥必须为 8 字节" }
        require(block.size == BLOCK_SIZE) { "DES 数据块必须为 8 字节" }
        val c = Cipher.getInstance(DES_ECB_ENC)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key8, "DES"))
        return c.doFinal(block)
    }

    fun desDecrypt(key8: ByteArray, block: ByteArray): ByteArray {
        require(key8.size == 8) { "DES 密钥必须为 8 字节" }
        require(block.size == BLOCK_SIZE) { "DES 数据块必须为 8 字节" }
        val c = Cipher.getInstance(DES_ECB_DEC)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key8, "DES"))
        return c.doFinal(block)
    }

    // --------------------------------------------------------------- 3DES-CBC

    /** 3DES-CBC 加密。key 可为 16 或 24 字节；16 字节时按 K1|K2|K1 扩展。 */
    fun tdesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(data.size % BLOCK_SIZE == 0) { "3DES-CBC 数据长度必须是 8 的倍数：${data.size}" }
        val c = Cipher.getInstance(TDES_CBC_ENC)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(normalizeTdesKey(key), "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun tdesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(data.size % BLOCK_SIZE == 0) { "3DES-CBC 数据长度必须是 8 的倍数：${data.size}" }
        val c = Cipher.getInstance(TDES_CBC_DEC)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(normalizeTdesKey(key), "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun normalizeTdesKey(key: ByteArray): ByteArray = when (key.size) {
        16 -> key + key.copyOfRange(0, 8)
        24 -> key
        8 -> key + key + key
        else -> throw IllegalArgumentException("非法的 3DES 密钥长度：${key.size}")
    }

    // -------------------------------------------------------------- 填充 / MAC

    /**
     * ISO/IEC 9797-1 Padding Method 2：追加一个 0x80，随后补 0x00 至块边界。
     * 注意：**数据已经对齐时仍会追加一整个块**，这一点对 BAC 的 M.IFD 计算至关重要，
     * 已用 ICAO 9303-11 附录 D 的官方向量逐字节核对。
     */
    fun padMethod2(data: ByteArray, blockSize: Int = BLOCK_SIZE): ByteArray {
        val paddedLength = ((data.size + blockSize) / blockSize) * blockSize
        val out = ByteArray(paddedLength)
        System.arraycopy(data, 0, out, 0, data.size)
        out[data.size] = 0x80.toByte()
        return out
    }

    /** 去掉 Padding Method 2 填充。 */
    fun unpadMethod2(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        require(end > 0 && data[end - 1] == 0x80.toByte()) { "数据未按 ISO 9797-1 Method 2 填充" }
        return data.copyOfRange(0, end - 1)
    }

    /**
     * ISO/IEC 9797-1 MAC Algorithm 3（Retail MAC），使用 16 字节 3DES 密钥：
     *  1) 以 K1 做 DES-CBC-MAC（IV = 0），得到最后一块
     *  2) 用 K2 解密该块，再用 K1 加密
     * data 长度必须是 8 的倍数。
     */
    fun retailMac(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "Retail MAC 密钥必须为 16 字节：${key.size}" }
        require(data.size >= BLOCK_SIZE && data.size % BLOCK_SIZE == 0) {
            "Retail MAC 数据必须为 8 的整数倍且非空：${data.size}"
        }
        val k1 = key.copyOfRange(0, 8)
        val k2 = key.copyOfRange(8, 16)

        var chaining = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < data.size) {
            val block = Hex.xor(data.copyOfRange(offset, offset + BLOCK_SIZE), chaining)
            chaining = desEncrypt(k1, block)
            offset += BLOCK_SIZE
        }
        return desEncrypt(k1, desDecrypt(k2, chaining))
    }

    /** 带 Method 2 填充的 Retail MAC，便于直接调用。 */
    fun retailMacPadded(key: ByteArray, data: ByteArray): ByteArray =
        retailMac(key, padMethod2(data))

    // ------------------------------------------------------------------- KDF

    fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    /**
     * ICAO 9303 Part 11 §9.7.1 / 附录 D.1 的密钥派生函数：
     *   KDF(K, counter) = SHA-1(K ‖ counter_be32)[0..15]，再做 DES 奇偶校验位调整。
     * counter = 1 → Kenc，counter = 2 → Kmac。
     */
    fun kdf(k: ByteArray, counter: Int): ByteArray {
        val input = Hex.concat(k, Hex.fromInt(counter, 4))
        val digest = sha1(input).copyOfRange(0, 16)
        return adjustDesParity(digest)
    }

    /**
     * 把每个字节调整为奇校验（DES 密钥约定）。JCE 本身不强制奇偶位，
     * 但规范要求密钥经过调整，且这一步会影响最终密钥字节，必须保留。
     */
    fun adjustDesParity(key: ByteArray): ByteArray {
        val out = ByteArray(key.size)
        for (i in key.indices) {
            val v = key[i].toInt() and 0xFF
            val ones = Integer.bitCount(v)
            out[i] = if (ones % 2 == 0) (v xor 0x01).toByte() else v.toByte()
        }
        return out
    }

    /**
     * 由 MRZ 信息（documentNumber ‖ checkDigit ‖ dob ‖ checkDigit ‖ doe ‖ checkDigit）
     * 派生 BAC 的 Kenc / Kmac。
     */
    fun deriveBacKeys(mrzInformation: String): Pair<ByteArray, ByteArray> {
        val kSeed = sha1(mrzInformation.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 16)
        return kdf(kSeed, 1) to kdf(kSeed, 2)
    }

    /** 由 Kseed（K.IFD ⊕ K.IC）派生会话密钥。 */
    fun deriveSessionKeys(kXor: ByteArray): Pair<ByteArray, ByteArray> =
        kdf(kXor, 1) to kdf(kXor, 2)
}
