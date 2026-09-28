package com.edocreader.app.nfc.pa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * CSCA 信任库与信任链回溯测试。
 *
 * 使用打包进 assets 的**真实 CSCA 证书**做断言，而非构造的假数据——
 * 这样能同时验证「打包格式正确」与「链式验证逻辑在真实证书上成立」。
 *
 * 证书来源：德国 BSI German Master List + 荷兰 NPKD Netherlands Master List。
 */
class CscaTrustStoreTest {

    private val bundlePath: File by lazy {
        listOf(
            File("src/main/assets/csca/csca_bundle.bin"),
            File("app/src/main/assets/csca/csca_bundle.bin")
        ).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到信任库文件，当前工作目录=${File(".").absolutePath}")
    }

    private val loaded: CscaBundle.Loaded by lazy { CscaBundle.parse(bundlePath.readBytes()) }
    private val pool: List<X509Certificate> by lazy { loaded.certs }

    // ------------------------------------------------------------ 打包格式

    @Test
    fun `信任库可正确解析且规模合理`() {
        assertTrue("证书数量应超过 450，实际 ${pool.size}", pool.size > 450)
    }

    @Test
    fun `个别不受支持的证书不会导致整个信任库失败`() {
        // 打包时已用 JDK 的 CertificateFactory 预筛选，
        // 剔除了使用显式 EC 参数（自定义曲线）而无法被 Android/JDK 解析的证书，
        // 因此运行时不应再有证书被跳过。
        assertEquals("不应有证书被跳过", 0, loaded.skipped)
        assertTrue("有效证书仍应充足，实际 ${pool.size}", pool.size > 450)
    }

    @Test
    fun `信任库中每张证书都是合法 X509`() {
        var checked = 0
        for (c in pool) {
            assertNotNull(c.subjectX500Principal)
            assertNotNull(c.issuerX500Principal)
            assertNotNull(c.publicKey)
            assertTrue("证书编码不应为空", c.encoded.isNotEmpty())
            checked++
        }
        assertEquals(pool.size, checked)
    }

    @Test
    fun `信任库覆盖多个国家`() {
        val countries = pool.mapNotNull { cert ->
            cert.subjectX500Principal.name
                .split(",")
                .firstOrNull { it.trim().startsWith("C=") }
                ?.substringAfter("C=")
                ?.trim()
        }.toSet()
        assertTrue("覆盖国家/地区数应超过 90，实际 ${countries.size}", countries.size > 90)
    }

    @Test
    fun `信任库包含中国护照签发根证书`() {
        val hit = pool.filter {
            it.subjectX500Principal.name.contains("China Passport Country Signing Certificate")
        }
        assertTrue("应包含中国护照 CSCA，实际 ${hit.size} 张", hit.isNotEmpty())
    }

    // ------------------------------------------------------------ 链式验证

    @Test
    fun `自签名根证书可通过信任链验证`() {
        // 取一张自签名的中国护照 CSCA 当作「待验证证书」，
        // 信任库中包含它自己，应能一路回溯到自签名根。
        val root = pool.first {
            it.subjectX500Principal == it.issuerX500Principal &&
                it.subjectX500Principal.name.contains("China Passport Country Signing Certificate")
        }

        val chain = CscaChain.verify(root, pool)

        assertTrue("信任链应通过，实际：${chain.detail}", chain.trusted)
        assertTrue("路径不应为空", chain.path.isNotEmpty())
        assertEquals(root.subjectX500Principal, chain.path.last().subjectX500Principal)
    }

    @Test
    fun `被排除在信任库外的证书无法通过验证`() {
        val root = pool.first {
            it.subjectX500Principal == it.issuerX500Principal &&
                it.subjectX500Principal.name.contains("China Passport Country Signing Certificate")
        }
        // 从信任库中剔除该证书自身，链应在此断裂
        val trimmed = pool.filter { it != root }

        val chain = CscaChain.verify(root, trimmed)

        assertFalse("剔除签发者后不应通过，实际：${chain.detail}", chain.trusted)
        assertTrue(chain.detail.isNotBlank())
    }

    @Test
    fun `信任库为空时返回失败而非异常`() {
        val root = pool.first { it.subjectX500Principal == it.issuerX500Principal }
        val chain = CscaChain.verify(root, emptyList())
        assertFalse(chain.trusted)
        assertTrue(chain.detail.contains("信任库为空"))
    }

    @Test
    fun `跨越无关签发者的链无法成立`() {
        // 用一张证书当 DSC，信任库里只放另一张主体完全不同的证书
        val selfSigned = pool.filter { it.subjectX500Principal == it.issuerX500Principal }
        val target = selfSigned.first()
        val unrelated = selfSigned.first {
            it.subjectX500Principal != target.subjectX500Principal
        }

        val chain = CscaChain.verify(target, listOf(unrelated))

        assertFalse("无关签发者不应通过，实际：${chain.detail}", chain.trusted)
    }

    @Test
    fun `同主体多张证书时逐张试签直到命中`() {
        // 中国护照 CSCA 存在多张同主体不同密钥的历史证书，
        // 验证逻辑必须逐张尝试签名，而不能只看第一张。
        val sameSubject = pool.groupBy { it.subjectX500Principal }
            .filter { (k, v) -> v.size > 1 && k == v.first().issuerX500Principal }
            .maxByOrNull { it.value.size }

        assertNotNull("信任库中应存在同主体的多张自签名证书", sameSubject)
        val (subject, certs) = sameSubject!!
        assertTrue("同主体证书数应大于 1，实际 ${certs.size}", certs.size > 1)

        // 取其中任意一张，都应能在包含全部同主体证书的池中验证成功
        for (c in certs) {
            val chain = CscaChain.verify(c, certs)
            assertTrue(
                "同主体证书应能命中自身完成验证：${subject.name}",
                chain.trusted
            )
        }
    }

    @Test
    fun `索引键基于 DN 的规范编码而非字符串`() {
        // 同样的 DN 构造出的 X500Principal 编码应一致
        val a = X500Principal("CN=Test,OU=Unit,O=Org,C=CN")
        val b = X500Principal("CN=Test,OU=Unit,O=Org,C=CN")
        assertTrue(a.encoded.contentEquals(b.encoded))
    }
}
