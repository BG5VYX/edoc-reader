package com.edocreader.app.nfc.pa

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * CSCA 信任库二进制包的解析。
 *
 * 刻意做成纯 JVM 逻辑（不引用任何 Android 类型），以便单元测试直接覆盖。
 *
 * 文件格式：
 * ```
 * magic    : "CSCA"          4 字节
 * version  : 1               1 字节
 * reserved : 00 00 00        3 字节
 * count    : uint32 大端      4 字节
 * 重复 count 次：
 *   length : uint32 大端      4 字节
 *   der    : length 字节      X.509 DER
 * ```
 *
 * 解析采取**容错**策略：个别证书若因算法参数不被本机 JCE 支持而无法解析，
 * 只跳过该证书并计数，绝不因单张坏证书导致整个信任库不可用。
 */
internal object CscaBundle {

    const val MAGIC = "CSCA"
    private const val HEADER_SIZE = 12

    /** 解析结果：成功解析的证书 + 被跳过的数量。 */
    data class Loaded(
        val certs: List<X509Certificate>,
        val skipped: Int
    )

    fun parse(bytes: ByteArray): Loaded {
        require(bytes.size > HEADER_SIZE) { "信任库文件过短（${bytes.size} 字节）" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) { "信任库文件头不合法" }

        val count = readU32(bytes, 8)
        require(count in 1..100_000) { "信任库声明的证书数量异常：$count" }

        val factory = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>(count)
        var skipped = 0

        var p = HEADER_SIZE
        for (i in 0 until count) {
            if (p + 4 > bytes.size) {
                skipped += count - i
                break
            }
            val len = readU32(bytes, p)
            p += 4
            if (len <= 0 || p + len > bytes.size) {
                skipped++
                break
            }
            try {
                out.add(
                    factory.generateCertificate(bytes.copyOfRange(p, p + len).inputStream())
                        as X509Certificate
                )
            } catch (_: Exception) {
                // 例如使用显式 EC 参数（自定义曲线）的证书，JDK 不支持解析
                skipped++
            }
            p += len
        }
        return Loaded(out, skipped)
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
