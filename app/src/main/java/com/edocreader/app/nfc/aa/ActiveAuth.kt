package com.edocreader.app.nfc.aa

import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/**
 * 主动认证（Active Authentication，AA）。
 *
 * 被动认证只能证明「数据没被改过」，CSCA 信任链只能证明「签发国可信」，
 * 但两者都无法排除**芯片被克隆**——攻击者可以把真芯片的全部数据原样复制到另一颗芯片上，
 * 所有摘要与签名依然自洽。
 *
 * AA 解决这个问题：芯片内保存一对只有它自己知道的私钥，读卡器发送一个随机挑战值，
 * 芯片用私钥签名返回。伪造者没有私钥，无法生成正确签名。
 *
 * 流程（ICAO 9303-11）：
 * ```
 *   读卡器 → 芯片：INTERNAL AUTHENTICATE (INS 0x88)，携带 8 字节随机挑战
 *   芯片  → 读卡器：用 DG15 中对应私钥对挑战值的签名
 *   读卡器：用 DG15 中的公钥验签
 * ```
 *
 * 本类只包含纯逻辑（挑战值生成与验签），不涉及 APDU 收发，便于单元测试。
 */
object ActiveAuth {

    /** 挑战值长度固定为 8 字节（ICAO 9303-11）。 */
    const val CHALLENGE_LENGTH = 8

    /** 一次验签尝试的结果。 */
    data class Attempt(
        val ok: Boolean,
        /** 命中的算法；未命中为 null。 */
        val algorithm: String?,
        /** 实际尝试过的算法数量。 */
        val tried: Int
    )

    /** 一次完整主动认证的结果，用于界面展示与记录导出。 */
    data class Outcome(
        /** 是否实际向芯片发起了 AA。DG15 缺失或公钥无法解析时为 false。 */
        val performed: Boolean,
        /** 验签结果。芯片不支持 AA 时为 null。 */
        val verified: Boolean?,
        /** 命中的签名算法。 */
        val algorithm: String?,
        /** 公钥摘要，例如 "RSA 2048 位"。 */
        val keyDetail: String?,
        /** 发送给芯片的挑战值（十六进制）。 */
        val challengeHex: String,
        /** 芯片返回的签名长度（字节）。 */
        val signatureLength: Int,
        /** 尝试过的算法数量。 */
        val algorithmsTried: Int,
        /** 人类可读的结论说明。 */
        val detail: String
    )

    fun newChallenge(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(CHALLENGE_LENGTH).also { random.nextBytes(it) }

    /**
     * 用 DG15 中的公钥验证芯片返回的签名。
     *
     * ICAO 9303-11 并未把 AA 的签名算法固定为唯一值——芯片可以自行选择，
     * 只要与 DG15 中的公钥类型匹配即可。因此这里按「同类型全部候选算法」
     * 逐个尝试，命中即通过。这是各家实现的通行做法。
     */
    fun verify(challenge: ByteArray, signature: ByteArray, key: PublicKey): Attempt {
        val candidates = candidatesFor(key)
        for (alg in candidates) {
            try {
                val verifier = Signature.getInstance(alg)
                verifier.initVerify(key)
                verifier.update(challenge)
                if (verifier.verify(signature)) {
                    return Attempt(true, alg, candidates.size)
                }
            } catch (_: Exception) {
                // 该算法本机不可用，或签名格式与算法不匹配，继续尝试下一个
            }
        }
        return Attempt(false, null, candidates.size)
    }

    /** 与公钥类型匹配的候选签名算法，按可能性从高到低排列。 */
    fun candidatesFor(key: PublicKey): List<String> = when (key.algorithm.uppercase()) {
        "RSA" -> listOf(
            "SHA256withRSA", "SHA1withRSA", "SHA384withRSA", "SHA512withRSA"
        )
        "EC", "ECDSA" -> listOf(
            "SHA256withECDSA", "SHA1withECDSA", "SHA384withECDSA", "SHA512withECDSA"
        )
        else -> emptyList()
    }
}
