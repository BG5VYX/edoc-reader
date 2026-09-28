package com.edocreader.app.nfc.pa

import com.edocreader.app.util.Hex
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * CSCA 信任链回溯。
 *
 * 纯 JVM 逻辑，不引用 Android 类型，便于用真实证书直接做单元测试。
 *
 * 被动认证只能证明「数据没被改过」；要回答「这张证件是不是某国真签发的」，
 * 必须把文档签名证书（DSC）沿签发关系逐级上溯，直到命中信任库中的
 * 签发国根证书（自签名 CSCA）。
 */
object CscaChain {

    /** 最大回溯深度，用于处理 CSCA 轮换产生的 link 证书。 */
    const val MAX_DEPTH = 4

    data class Chain(        /** 是否成功链接到信任库中的 CSCA。 */
        val trusted: Boolean,
        /** 从直接签发者到根的路径（不含被验证的 DSC 本身）。 */
        val path: List<X509Certificate> = emptyList(),
        /** 人类可读的结论说明。 */
        val detail: String = ""
    )

    /**
     * @param dsc  待验证的文档签名证书
     * @param pool 信任库中的 CSCA 证书集合
     */
    fun verify(dsc: X509Certificate, pool: List<X509Certificate>): Chain {
        if (pool.isEmpty()) return Chain(false, emptyList(), "信任库为空")

        val bySubject = pool.groupBy { indexKey(it.subjectX500Principal) }
        val path = mutableListOf<X509Certificate>()
        val visited = mutableSetOf<String>()
        var current = dsc

        for (depth in 0 until MAX_DEPTH) {
            val candidates = candidatesFor(current, pool, bySubject)
            if (candidates.isEmpty()) {
                val who = current.issuerX500Principal.name
                return Chain(
                    false, path,
                    if (depth == 0) "信任库中没有该签发者：$who"
                    else "证书链中断：信任库中没有 $who"
                )
            }

            var matched: X509Certificate? = null
            for (c in candidates) {
                if (verifiesWith(current, c)) {
                    matched = c
                    break
                }
            }
            if (matched == null) {
                return Chain(
                    false, path,
                    "找到 ${candidates.size} 张同名签发者证书，但签名均不匹配"
                )
            }

            path.add(matched)
            val id = matched.serialNumber.toString(16) + "|" + matched.subjectX500Principal.name
            if (!visited.add(id)) return Chain(false, path, "证书链出现环，已中止")

            // 自签名 → 已到达签发国根证书
            if (matched.subjectX500Principal == matched.issuerX500Principal) {
                return Chain(true, path, "已链接到签发国根证书（自签名 CSCA）")
            }

            current = matched
        }

        return Chain(false, path, "证书链超过最大深度 $MAX_DEPTH，未能到达根证书")
    }

    /** 用签发者证书的公钥验证目标证书的签名。 */
    private fun verifiesWith(target: X509Certificate, issuer: X509Certificate): Boolean = try {
        target.verify(issuer.publicKey)
        true
    } catch (_: Exception) {
        false
    }

    /** 先走索引，未命中再线性回退以应对 DN 编码差异。 */
    private fun candidatesFor(
        cert: X509Certificate,
        pool: List<X509Certificate>,
        bySubject: Map<String, List<X509Certificate>>
    ): List<X509Certificate> {
        val hit = bySubject[indexKey(cert.issuerX500Principal)]
        if (!hit.isNullOrEmpty()) return hit
        return pool.filter { it.subjectX500Principal == cert.issuerX500Principal }
    }

    /** 用 DN 的规范 DER 编码做索引键。 */
    private fun indexKey(name: X500Principal): String = Hex.encode(name.encoded)
}
