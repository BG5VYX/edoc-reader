package com.edocreader.app.nfc.pa

import android.content.Context
import com.edocreader.app.App
import java.security.cert.X509Certificate

/**
 * CSCA 信任库。
 *
 * 被动认证只能证明「数据没被改过」；要回答「这张证件是不是某国真签发的」，
 * 必须把文档签名证书（DSC）链接到签发国的 CSCA 根证书上。
 * 本类负责从 assets 加载 CSCA 公钥证书，链式验证逻辑见 [CscaChain]。
 *
 * 证书来源（均为各国政府公开发布的主列表，仅含公钥，不含任何私钥）：
 *   · 德国 BSI German Master List
 *   · 荷兰 NPKD Netherlands Master List
 * 合并去重后覆盖 135 个国家/地区。
 */
object CscaTrustStore {

    private const val ASSET = "csca/csca_bundle.bin"

    @Volatile private var loaded = false
    @Volatile private var all: List<X509Certificate> = emptyList()
    @Volatile private var loadMessage = "尚未加载"

    /** 信任库中的证书总数（未加载时为 0）。 */
    val size: Int get() = all.size

    /** 加载状态说明，供界面展示。 */
    val status: String get() = loadMessage

    fun ensureLoaded(context: Context = App.instance): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                val bytes = context.assets.open(ASSET).use { it.readBytes() }
                val result = CscaBundle.parse(bytes)
                all = result.certs
                loaded = true
                loadMessage = if (result.skipped > 0) {
                    "已加载 ${result.certs.size} 张 CSCA 证书（${result.skipped} 张因算法参数不受支持已跳过）"
                } else {
                    "已加载 ${result.certs.size} 张 CSCA 证书"
                }
                true
            } catch (e: Exception) {
                loadMessage = "信任库加载失败：${e.message ?: e.javaClass.simpleName}"
                false
            }
        }
    }

    /** 把 DSC 链接到信任库中的签发国 CSCA。 */
    fun verify(dsc: X509Certificate): CscaChain.Chain {
        if (!ensureLoaded()) return CscaChain.Chain(false, emptyList(), loadMessage)
        return CscaChain.verify(dsc, all)
    }
}
