package com.edocreader.app.nfc.aa

import com.edocreader.app.nfc.Tlv
import com.edocreader.app.nfc.dg.DgParsers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 主动认证（AA）与 DG15 公钥解析测试。
 *
 * AA 的价值在于排除**芯片克隆**：攻击者可以复制真芯片的全部数据，
 * 使被动认证与 CSCA 信任链全部自洽，但没有 DG15 对应的私钥就无法通过 AA。
 * 因此这里重点验证「正确签名必须通过、任何篡改或错配必须失败」。
 */
class ActiveAuthTest {

    private fun ecKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private fun rsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        return kpg.generateKeyPair()
    }

    private fun sign(kp: KeyPair, algorithm: String, data: ByteArray): ByteArray {
        val s = Signature.getInstance(algorithm)
        s.initSign(kp.private)
        s.update(data)
        return s.sign()
    }

    /** 把 SubjectPublicKeyInfo 包成 DG15 结构：6F <len> SEQUENCE { ... }。 */
    private fun buildDg15(kp: KeyPair): ByteArray =
        Tlv.encode(0x6F, kp.public.encoded)

    // ------------------------------------------------------------ 挑战值

    @Test
    fun `挑战值长度固定为 8 字节`() {
        val c = ActiveAuth.newChallenge()
        assertEquals(8, c.size)
        assertEquals(ActiveAuth.CHALLENGE_LENGTH, c.size)
    }

    @Test
    fun `挑战值应具备随机性`() {
        val random = SecureRandom()
        val a = ActiveAuth.newChallenge(random)
        val b = ActiveAuth.newChallenge(random)
        // 8 字节随机值碰撞概率极低；若相等说明没有真正随机
        assertFalse("两次挑战值不应相同", a.contentEquals(b))
    }

    // ------------------------------------------------------------ 验签

    @Test
    fun `EC 正确签名应通过验证`() {
        val kp = ecKeyPair()
        val challenge = ActiveAuth.newChallenge()
        val signature = sign(kp, "SHA256withECDSA", challenge)

        val attempt = ActiveAuth.verify(challenge, signature, kp.public)

        assertTrue("正确签名应通过，实际尝试 ${attempt.tried} 种算法", attempt.ok)
        assertEquals("SHA256withECDSA", attempt.algorithm)
    }

    @Test
    fun `RSA 正确签名应通过验证`() {
        val kp = rsaKeyPair()
        val challenge = ActiveAuth.newChallenge()
        val signature = sign(kp, "SHA1withRSA", challenge)

        val attempt = ActiveAuth.verify(challenge, signature, kp.public)

        assertTrue("SHA-1 签名也应能命中（芯片可能使用旧算法）", attempt.ok)
        assertEquals("SHA1withRSA", attempt.algorithm)
    }

    @Test
    fun `挑战值被篡改时验证必须失败`() {
        val kp = ecKeyPair()
        val challenge = ActiveAuth.newChallenge()
        val signature = sign(kp, "SHA256withECDSA", challenge)

        val tampered = challenge.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val attempt = ActiveAuth.verify(tampered, signature, kp.public)

        assertFalse("挑战值变了，签名不应再成立", attempt.ok)
    }

    @Test
    fun `克隆芯片的错配公钥必须被识破`() {
        // 模拟克隆芯片：数据全部复制，但私钥是另一对
        val real = ecKeyPair()
        val clone = ecKeyPair()
        val challenge = ActiveAuth.newChallenge()
        // 克隆芯片只能用自己（错误）的私钥签名
        val cloneSignature = sign(clone, "SHA256withECDSA", challenge)

        val attempt = ActiveAuth.verify(challenge, cloneSignature, real.public)

        assertFalse("克隆芯片的签名不应通过真公钥验证", attempt.ok)
    }

    @Test
    fun `签名被截断时验证失败而非抛异常`() {
        val kp = ecKeyPair()
        val challenge = ActiveAuth.newChallenge()
        val signature = sign(kp, "SHA256withECDSA", challenge)

        val attempt = ActiveAuth.verify(challenge, signature.copyOf(signature.size / 2), kp.public)

        assertFalse(attempt.ok)
    }

    @Test
    fun `空签名与空挑战不会导致异常`() {
        val kp = ecKeyPair()
        assertFalse(ActiveAuth.verify(ByteArray(0), ByteArray(0), kp.public).ok)
    }

    // ------------------------------------------------------------ 算法候选

    @Test
    fun `候选算法按公钥类型返回且顺序合理`() {
        val rsa = ActiveAuth.candidatesFor(rsaKeyPair().public)
        assertTrue("RSA 应返回 4 个候选", rsa.size == 4)
        assertEquals("SHA256withRSA", rsa.first())

        val ec = ActiveAuth.candidatesFor(ecKeyPair().public)
        assertTrue("EC 应返回 4 个候选", ec.size == 4)
        assertEquals("SHA256withECDSA", ec.first())
    }

    // ------------------------------------------------------------ DG15 解析

    @Test
    fun `可从 DG15 中解析出 EC 公钥`() {
        val kp = ecKeyPair()
        val key = DgParsers.parseDg15PublicKey(buildDg15(kp))

        assertNotNull("应能解析出公钥", key)
        assertEquals("EC", key!!.keyType)
        assertEquals("1.2.840.10045.2.1", key.algorithmOid)
        assertArrayEquals("公钥内容应与原始一致", kp.public.encoded, key.publicKey.encoded)
    }

    @Test
    fun `可从 DG15 中解析出 RSA 公钥`() {
        val kp = rsaKeyPair()
        val key = DgParsers.parseDg15PublicKey(buildDg15(kp))

        assertNotNull(key)
        assertEquals("RSA", key!!.keyType)
        assertTrue("应识别为 RSA 1024 位，实际 ${key.detail}", key.detail.contains("1024"))
    }

    @Test
    fun `解析出的 DG15 公钥可直接用于验签`() {
        // 端到端：DG15 → 公钥 → 验签
        val kp = ecKeyPair()
        val key = DgParsers.parseDg15PublicKey(buildDg15(kp))!!
        val challenge = ActiveAuth.newChallenge()
        val signature = sign(kp, "SHA256withECDSA", challenge)

        val attempt = ActiveAuth.verify(challenge, signature, key.publicKey)

        assertTrue("从 DG15 还原的公钥应能验签通过", attempt.ok)
    }

    @Test
    fun `非法 DG15 返回 null 而非抛异常`() {
        assertNull(DgParsers.parseDg15PublicKey(ByteArray(0)))
        assertNull(DgParsers.parseDg15PublicKey(byteArrayOf(0x6F, 0x00)))
        assertNull(DgParsers.parseDg15PublicKey(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `不支持的算法 OID 返回 null`() {
        // 构造一个算法 OID 为 DSA 的 SPKI 外壳，应被识别为不支持
        val fakeSpki = byteArrayOf(
            0x30, 0x0A,
            0x30, 0x05, 0x06, 0x03, 0x2A, 0x03, 0x04, // DSA OID 1.2.840.10040.4
            0x03, 0x01, 0x00
        )
        assertNull(DgParsers.parseDg15PublicKey(Tlv.encode(0x6F, fakeSpki)))
    }
}
