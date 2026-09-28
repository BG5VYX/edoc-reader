package com.edocreader.app.nfc.pa

import com.edocreader.app.util.Hex
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 被动认证（Passive Authentication, ICAO 9303 Part 11 §5.1）。
 *
 * 被动认证分两步：
 *  1. **数据完整性**：EF.SOD 中的 LDS Security Object 保存了每个数据组的摘要，
 *     重新计算本地读到的数据组摘要并与之比对，可发现数据被篡改；
 *  2. **签名可信性**：EF.SOD 是一个 CMS SignedData，由签发国的文档签名证书（DSC）
 *     签名。校验 CMS 签名可以确认 SOD 本身未被伪造。
 *
 * 注意：DSC 本身要由签发国的 CSCA 证书签发，而 CSCA 主列表（ICAO PKD 或各国
 * 官方发布）不在本应用内，因此本实现**不**对 DSC 做链路信任判定，只校验
 * SOD 的 CMS 签名与 DSC 证书自身的有效期，并在结论中明确标注这一点。
 */
object PassiveAuth {

    data class DgHash(
        val dgNumber: Int,
        val expectedHash: ByteArray
    )

    data class SodInfo(
        val hashAlgorithm: String,
        val dgHashes: List<DgHash>,
        val ldsVersion: String?,
        val signerCertificate: X509Certificate?,
        val signatureAlgorithm: String?,
        val cmsSignatureValid: Boolean?,
        val messages: MutableList<String> = mutableListOf()
    )

    data class DgVerification(
        val dgNumber: Int,
        val present: Boolean,
        val matches: Boolean?,
        val expectedHash: String?,
        val computedHash: String?
    )

    data class Result(
        val hashAlgorithm: String,
        val dgVerifications: List<DgVerification>,
        val cmsSignatureValid: Boolean?,
        val signerSubject: String?,
        val signerIssuer: String?,
        val signerValidFrom: String?,
        val signerValidTo: String?,
        val signerExpired: Boolean,
        /** 是否成功把 DSC 链接到信任库中的签发国 CSCA。 */
        val cscaTrusted: Boolean,
        /** 命中的根证书主体名称。 */
        val cscaSubject: String?,
        /** 命中的根证书序列号（十六进制）。 */
        val cscaSerial: String?,
        /** 根证书有效期截止时间。 */
        val cscaNotAfter: String?,
        /** 根证书当前是否已过期（历史证件仍可能合法，故仅作提示）。 */
        val cscaCurrentlyExpired: Boolean,
        /** 信任链回溯的结论说明。 */
        val cscaChainDetail: String,
        /** 信任库中的证书总数。 */
        val cscaStoreSize: Int,
        val messages: List<String>
    ) {
        val allDgHashesMatch: Boolean
            get() = dgVerifications.filter { it.present }.all { it.matches == true }
    }

    /**
     * 解析并校验 EF.SOD。
     *
     * @param sod            EF.SOD 原始字节
     * @param dataGroups     已读取的数据组：编号 → 原始字节
     */
    fun verify(sod: ByteArray, dataGroups: Map<Int, ByteArray>): Result {
        val messages = mutableListOf<String>()
        val info = parseSod(sod, messages)

        val verifications = mutableListOf<DgVerification>()
        for (entry in info.dgHashes) {
            val dg = dataGroups[entry.dgNumber]
            if (dg == null) {
                verifications.add(
                    DgVerification(entry.dgNumber, present = false, matches = null, expectedHash = Hex.encode(entry.expectedHash), computedHash = null)
                )
                continue
            }
            val computed = MessageDigest.getInstance(info.hashAlgorithm).digest(dg)
            verifications.add(
                DgVerification(
                    dgNumber = entry.dgNumber,
                    present = true,
                    matches = computed.contentEquals(entry.expectedHash),
                    expectedHash = Hex.encode(entry.expectedHash),
                    computedHash = Hex.encode(computed)
                )
            )
        }

        // 读取到但 SOD 未列出的数据组
        for (dgNumber in dataGroups.keys) {
            if (info.dgHashes.none { it.dgNumber == dgNumber }) {
                messages.add("数据组 DG$dgNumber 已读取，但 EF.SOD 未包含其摘要（芯片可能未做签名保护）")
            }
        }

        val cert = info.signerCertificate
        val expired = cert?.let { isExpired(it) } ?: false

        // 信任链：把文档签名证书链接到签发国 CSCA 根证书
        val chain = if (cert == null) {
            CscaChain.Chain(false, emptyList(), "未从 EF.SOD 中取得签名者证书")
        } else {
            CscaTrustStore.verify(cert)
        }
        val root = chain.path.lastOrNull()

        if (cert != null) {
            messages.add(
                if (chain.trusted) "信任链验证通过：${root?.subjectX500Principal?.name}"
                else "信任链未通过：${chain.detail}"
            )
        }

        return Result(
            hashAlgorithm = info.hashAlgorithm,
            dgVerifications = verifications.sortedBy { it.dgNumber },
            cmsSignatureValid = info.cmsSignatureValid,
            signerSubject = cert?.subjectX500Principal?.name,
            signerIssuer = cert?.issuerX500Principal?.name,
            signerValidFrom = cert?.notBefore?.toString(),
            signerValidTo = cert?.notAfter?.toString(),
            signerExpired = expired,
            cscaTrusted = chain.trusted,
            cscaSubject = root?.subjectX500Principal?.name,
            cscaSerial = root?.serialNumber?.toString(16),
            cscaNotAfter = root?.notAfter?.toString(),
            cscaCurrentlyExpired = root?.let { isExpired(it) } ?: false,
            cscaChainDetail = chain.detail,
            cscaStoreSize = CscaTrustStore.size,
            messages = messages + info.messages
        )
    }

    private fun isExpired(cert: X509Certificate): Boolean = try {
        cert.checkValidity()
        false
    } catch (_: Exception) {
        true
    }

    // ------------------------------------------------------------------ SOD

    private fun parseSod(sod: ByteArray, messages: MutableList<String>): SodInfo {
        val root = Der.parse(sod).firstOrNull()
            ?: throw IllegalArgumentException("EF.SOD 不是合法的 DER 结构")

        // ContentInfo ::= SEQUENCE { contentType OID, content [0] EXPLICIT SignedData }
        val contentInfo = if (root.tag == 0x30) root else root.children.firstOrNull() ?: root
        val explicitContent = contentInfo.firstChildWithTag(0xA0)
            ?: throw IllegalArgumentException("EF.SOD 缺少 SignedData 内容")
        val signedData = explicitContent.children.firstOrNull()
            ?: throw IllegalArgumentException("EF.SOD 的 SignedData 为空")

        var hashAlgorithm = "SHA-256"
        val dgHashes = mutableListOf<DgHash>()
        var ldsVersion: String? = null

        // SignedData ::= SEQUENCE { version, digestAlgorithms, encapContentInfo, [0] certs, [1] crls, signerInfos }
        val encapContentInfo = signedData.children.getOrNull(2)
            ?: throw IllegalArgumentException("SignedData 缺少 encapContentInfo")

        val eContentOctets = findEContent(encapContentInfo)
        if (eContentOctets == null) {
            messages.add("SOD 中未找到 eContent，无法比对数据组摘要")
        } else {
            val lds = Der.parse(eContentOctets).firstOrNull()
            if (lds == null) {
                messages.add("eContent 不是合法的 LDSSecurityObject")
            } else {
                // LDSSecurityObject ::= SEQUENCE { version INTEGER, hashAlgorithm AlgorithmIdentifier, dataGroupHashValues SEQUENCE OF DataGroupHash }
                lds.children.getOrNull(0)?.let { ldsVersion = it.asInt().toString() }
                val hashAlgNode = lds.children.getOrNull(1)
                val oidNode = hashAlgNode?.children?.firstOrNull()
                if (oidNode != null && oidNode.tag == 0x06) {
                    val oid = Der.decodeOid(oidNode.value)
                    hashAlgorithm = Der.hashAlgorithmFor(oid)
                        ?: run {
                            messages.add("未知的摘要算法 OID：$oid，回退为 SHA-256")
                            "SHA-256"
                        }
                }
                val dgList = lds.children.getOrNull(2)
                dgList?.children?.forEach { dgNode ->
                    val num = dgNode.children.getOrNull(0)?.asInt() ?: return@forEach
                    val hash = dgNode.children.getOrNull(1)?.value ?: return@forEach
                    dgHashes.add(DgHash(num, hash))
                }
            }
        }

        // ---- 签名者信息 ----
        val certificates = signedData.children.firstOrNull { it.tag == 0xA0 }
        val signerInfos = signedData.children.lastOrNull { it.tag == 0x31 }

        val certList = parseCertificates(certificates, messages)
        val signerInfo = signerInfos?.children?.firstOrNull()

        var cmsSignatureValid: Boolean? = null
        var signatureAlgorithm: String? = null
        var signerCert: X509Certificate? = null

        if (signerInfo == null) {
            messages.add("SOD 中未找到 SignerInfo，无法校验 CMS 签名")
        } else if (certList.isEmpty()) {
            messages.add("SOD 中未内嵌文档签名证书（DSC），无法校验 CMS 签名")
        } else {
            signerCert = matchSignerCertificate(signerInfo, certList) ?: certList.first()
            val verifyResult = verifyCmsSignature(signerInfo, signerCert, eContentOctets)
            cmsSignatureValid = verifyResult.first
            signatureAlgorithm = verifyResult.second
            messages.addAll(verifyResult.third)
        }

        return SodInfo(
            hashAlgorithm = hashAlgorithm,
            dgHashes = dgHashes,
            ldsVersion = ldsVersion,
            signerCertificate = signerCert,
            signatureAlgorithm = signatureAlgorithm,
            cmsSignatureValid = cmsSignatureValid,
            messages = messages
        )
    }

    /** 在 EncapsulatedContentInfo 中找到 eContent 的 OCTET STRING 内容。 */
    private fun findEContent(encapContentInfo: DerNode): ByteArray? {
        // 形式一：SEQUENCE { OID, [0] EXPLICIT OCTET STRING }
        val explicit = encapContentInfo.firstChildWithTag(0xA0)
        if (explicit != null) {
            val octets = explicit.children.firstOrNull()
            if (octets != null) return octets.value
        }
        // 形式二：直接内联 OCTET STRING
        val inline = encapContentInfo.children.firstOrNull { it.tag == 0x04 }
        return inline?.value
    }

    private fun parseCertificates(node: DerNode?, messages: MutableList<String>): List<X509Certificate> {
        if (node == null) return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        for (child in node.children) {
            try {
                val cert = factory.generateCertificate(ByteArrayInputStream(child.raw)) as X509Certificate
                certs.add(cert)
            } catch (e: Exception) {
                messages.add("解析内嵌证书失败：${e.message}")
            }
        }
        return certs
    }

    /**
     * SignerInfo ::= SEQUENCE {
     *   version INTEGER,
     *   sid IssuerAndSerialNumber | [0] SubjectKeyIdentifier,
     *   digestAlgorithm AlgorithmIdentifier,
     *   signedAttrs [0] IMPLICIT SET OF Attribute OPTIONAL,
     *   signatureAlgorithm AlgorithmIdentifier,
     *   signature OCTET STRING
     * }
     */
    private fun matchSignerCertificate(signerInfo: DerNode, certs: List<X509Certificate>): X509Certificate? {
        val sid = signerInfo.children.getOrNull(1) ?: return null
        if (sid.tag == 0x30) {
            val serial = sid.children.getOrNull(1)?.value ?: return null
            return certs.firstOrNull { it.serialNumber.toByteArray().contentEquals(serial) }
        }
        return null
    }

    private fun verifyCmsSignature(
        signerInfo: DerNode,
        cert: X509Certificate,
        eContent: ByteArray?
    ): Triple<Boolean?, String?, List<String>> {
        val messages = mutableListOf<String>()

        val digestAlgNode = signerInfo.children.getOrNull(2)
        val digestOid = digestAlgNode?.children?.firstOrNull()?.let { Der.decodeOid(it.value) }
        val digestAlg = digestOid?.let { Der.hashAlgorithmFor(it) } ?: "SHA-256"

        val signedAttrs = signerInfo.children.firstOrNull { it.tag == 0xA0 }
        val sigAlgNode = signerInfo.children.lastOrNull { it.tag == 0x30 && it !== signedAttrs }
        val signatureNode = signerInfo.children.lastOrNull { it.tag == 0x04 }

        if (signatureNode == null) {
            messages.add("SignerInfo 中缺少签名值")
            return Triple(null, null, messages)
        }

        val sigOid = sigAlgNode?.children?.firstOrNull()?.let { Der.decodeOid(it.value) }
        val jcaAlg = sigOid?.let { Der.signatureAlgorithmFor(it, digestAlg) } ?: "SHA256withRSA"

        // 1) 校验 signedAttrs 中的 messageDigest 属性与 eContent 摘要一致
        if (signedAttrs != null && eContent != null) {
            val mdAttr = findAttribute(signedAttrs, "1.2.840.113549.1.9.4")
            val attrDigest = mdAttr?.let { attr ->
                attr.children.lastOrNull()?.children?.firstOrNull()?.value
            }
            if (attrDigest != null) {
                val actual = MessageDigest.getInstance(digestAlg).digest(eContent)
                if (!actual.contentEquals(attrDigest)) {
                    messages.add("messageDigest 属性与 eContent 摘要不一致，SOD 内容可能被篡改")
                    return Triple(false, jcaAlg, messages)
                }
            }
        }

        // 2) 用 DSC 公钥验证签名。签名对象是 signedAttrs 的 DER 编码，
        //    但外层 tag 需由 [0](0xA0) 改为 SET(0x31)。
        val toVerify = if (signedAttrs != null) {
            val bytes = signedAttrs.raw.copyOf()
            bytes[0] = 0x31
            bytes
        } else {
            eContent ?: run {
                messages.add("既无 signedAttrs 也无 eContent，无法验签")
                return Triple(null, jcaAlg, messages)
            }
        }

        return try {
            val sig = Signature.getInstance(jcaAlg)
            sig.initVerify(cert.publicKey)
            sig.update(toVerify)
            val ok = sig.verify(signatureNode.value)
            if (!ok) messages.add("CMS 签名验证失败")
            Triple(ok, jcaAlg, messages)
        } catch (e: Exception) {
            messages.add("CMS 签名验证异常（$jcaAlg）：${e.message}")
            Triple(null, jcaAlg, messages)
        }
    }

    /** 在 signedAttrs 中按 OID 查找属性节点。 */
    private fun findAttribute(signedAttrs: DerNode, oid: String): DerNode? =
        signedAttrs.children.firstOrNull { attr ->
            val oidNode = attr.children.firstOrNull() ?: return@firstOrNull false
            oidNode.tag == 0x06 && Der.decodeOid(oidNode.value) == oid
        }
}
