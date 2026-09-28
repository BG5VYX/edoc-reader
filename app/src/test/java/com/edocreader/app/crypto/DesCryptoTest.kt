package com.edocreader.app.crypto

import com.edocreader.app.util.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BAC 密码学实现的一致性测试。
 *
 * 全部期望值取自 **ICAO Doc 9303 Part 11 附录 D**（"Worked example"）的官方数据，
 * 输入为：
 *   MRZ_information = "L898902C<369080619406236"
 *   RND.IC  = 4608F91988702212
 *   RND.IFD = 781723860C06C226
 *   K.IFD   = 0B795240CB7049B01C19B33E32804F0B
 */
class DesCryptoTest {

    private val mrzInformation = "L898902C<369080619406236"

    @Test
    fun `Kseed 与 ICAO 官方向量一致`() {
        val kseed = DesCrypto.sha1(mrzInformation.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 16)
        assertEquals("239AB9CB282DAF66231DC5A4DF6BFBAE", Hex.encode(kseed))
    }

    @Test
    fun `BAC 密钥派生与 ICAO 官方向量一致`() {
        val (kEnc, kMac) = DesCrypto.deriveBacKeys(mrzInformation)
        assertEquals("AB94FDECF2674FDFB9B391F85D7F76F2", Hex.encode(kEnc))
        assertEquals("7962D9ECE03D1ACD4C76089DCE131543", Hex.encode(kMac))
    }

    @Test
    fun `E_IFD 计算与 ICAO 官方向量一致`() {
        val (kEnc, _) = DesCrypto.deriveBacKeys(mrzInformation)
        val s = Hex.decode("781723860C06C226" + "4608F91988702212" + "0B795240CB7049B01C19B33E32804F0B")
        val eIfd = DesCrypto.tdesCbcEncrypt(kEnc, ByteArray(8), s)
        assertEquals(
            "72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2",
            Hex.encode(eIfd)
        )
    }

    @Test
    fun `M_IFD 的 Retail MAC 与 ICAO 官方向量一致`() {
        val (_, kMac) = DesCrypto.deriveBacKeys(mrzInformation)
        val eIfd = Hex.decode("72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2")
        // 注意：Padding Method 2 在数据已对齐时仍会追加一整个块（32 → 40 字节）
        val mIfd = DesCrypto.retailMac(kMac, DesCrypto.padMethod2(eIfd))
        assertEquals("5F1448EEA8AD90A7", Hex.encode(mIfd))
    }

    @Test
    fun `M_ICC 校验与 K_ICC 解密结果与 ICAO 官方向量一致`() {
        val (kEnc, kMac) = DesCrypto.deriveBacKeys(mrzInformation)
        val eIcc = Hex.decode("46B9342A41396CD7386BF5803104D7CEDC122B9132139BAF2EEDC94EE178534F")
        val mIcc = Hex.decode("2F2D235D074D7449")

        assertArrayEquals(mIcc, DesCrypto.retailMac(kMac, DesCrypto.padMethod2(eIcc)))

        val plain = DesCrypto.tdesCbcDecrypt(kEnc, ByteArray(8), eIcc)
        assertEquals("4608F91988702212", Hex.encode(plain.copyOfRange(0, 8)))  // RND.IC 回显
        assertEquals("781723860C06C226", Hex.encode(plain.copyOfRange(8, 16))) // RND.IFD 回显
        assertEquals("0B4F80323EB3191CB04970CB4052790B", Hex.encode(plain.copyOfRange(16, 32)))
    }

    @Test
    fun `会话密钥派生与 ICAO 官方向量一致`() {
        val kIfd = Hex.decode("0B795240CB7049B01C19B33E32804F0B")
        val kIcc = Hex.decode("0B4F80323EB3191CB04970CB4052790B")
        val (ksEnc, ksMac) = DesCrypto.deriveSessionKeys(Hex.xor(kIfd, kIcc))
        assertEquals("979EC13B1CBFE9DCD01AB0FED307EAE5", Hex.encode(ksEnc))
        assertEquals("F1CB1F1FB5ADF208806B89DC579DC1F8", Hex.encode(ksMac))
    }

    @Test
    fun `Padding Method 2 在数据已对齐时补整块`() {
        val aligned = ByteArray(8) { 0x11 }
        assertEquals(16, DesCrypto.padMethod2(aligned).size)
        assertEquals(8, DesCrypto.padMethod2(ByteArray(7)).size)
        assertEquals(16, DesCrypto.padMethod2(ByteArray(15)).size)
    }

    @Test
    fun `Padding Method 2 可以无损还原`() {
        for (size in 1..40) {
            val data = ByteArray(size) { (it and 0xFF).toByte() }
            val padded = DesCrypto.padMethod2(data)
            assertArrayEquals(data, DesCrypto.unpadMethod2(padded))
        }
    }

    @Test
    fun `DES 奇偶校验位调整结果均为奇校验`() {
        val input = ByteArray(16) { it.toByte() }
        val adjusted = DesCrypto.adjustDesParity(input)
        for (b in adjusted) {
            assertEquals(1, Integer.bitCount(b.toInt() and 0xFF) % 2)
        }
    }
}
