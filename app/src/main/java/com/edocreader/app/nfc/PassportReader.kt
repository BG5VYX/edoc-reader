package com.edocreader.app.nfc

import android.nfc.tech.IsoDep
import com.edocreader.app.mrz.MrzFormat
import com.edocreader.app.mrz.MrzInfo
import com.edocreader.app.mrz.MrzParser
import com.edocreader.app.nfc.aa.ActiveAuth
import com.edocreader.app.nfc.dg.DgParsers
import com.edocreader.app.nfc.pa.PassiveAuth
import com.edocreader.app.util.Hex

/**
 * 完整的芯片读取流程编排。
 *
 * 流程（全部在本地完成，不依赖任何授权文件或联网服务）：
 *   1. SELECT eMRTD 应用（AID A0000002471001）
 *   2. BAC 双向认证（口令来自 OCR 识别的 MRZ 三要素）
 *   3. 建立 3DES 安全报文通道
 *   4. 依次读取 EF.COM / EF.DG1 / DG2 / DG11 / DG14 / DG15 / EF.SOD
 *   5. 解析各数据组，并用 EF.SOD 做被动认证（摘要比对 + CMS 验签）
 */
class PassportReader(
    private val isoDep: IsoDep,
    private val onProgress: (String) -> Unit = {}
) {

    class ReadException(message: String) : Exception(message)

    /** 读取结果。 */
    class Result(
        val ocrMrz: MrzInfo,
        val chipMrz: MrzInfo?,
        val mrzMatchesOcr: Boolean?,
        val faceImage: ByteArray?,
        val faceImageFormat: String?,
        val dg11Items: List<DgParsers.Dg11Item>,
        val nativeName: String?,

        /**
         * 原始数据组字节（十六进制），用于离线诊断。
         *
         * 遇到字段解析异常时，光看「解析结果」无法判断是布局理解错了还是数据本身如此，
         * 保留原始字节就能在本地精确复现。
         * DG2 体积大（数十 KB），只保留开头 64 字节用于识别图像格式。
         */
        val rawDg1Hex: String,
        val rawDg11Hex: String,
        val rawDg2HeadHex: String,
        val availableDgTags: List<String>,
        val dg14Infos: List<String>,
        val dg15Info: String?,
        val passiveAuth: PassiveAuth.Result?,
        /** 主动认证结果；芯片未提供 DG15 时为 null。 */
        val activeAuth: ActiveAuth.Outcome?,
        val readDataGroups: List<Int>,
        val steps: List<String>,
        val elapsedMs: Long
    )

    private val steps = mutableListOf<String>()

    private fun step(message: String) {
        steps.add(message)
        onProgress(message)
    }

    fun read(ocrMrz: MrzInfo): Result {
        val startedAt = System.currentTimeMillis()
        val channel = IsoDepChannel(isoDep)
        val fileSystemHolder = arrayOfNulls<EmrtdFileSystem>(1)

        try {
            step("已连接芯片：${Hex.encode(isoDep.tag.id)}")

            // ---- 1. 选择 eMRTD 应用 ----
            val bootstrap = EmrtdFileSystem(channel, SecureMessaging(ByteArray(16), ByteArray(16), ByteArray(8)), ::step)
            if (!bootstrap.selectApplication()) {
                throw ReadException("未能选择 eMRTD 应用，请确认证件为带芯片的电子护照 / 电子通行证")
            }
            step("已选择 eMRTD 应用")

            // ---- 2. BAC ----
            step("开始 BAC 双向认证…")
            val bac = BacProtocol(channel)
            val session = try {
                bac.perform(ocrMrz.mrzInformation)
            } catch (e: BacProtocol.BacException) {
                throw ReadException(
                    "BAC 认证失败：${e.message}\n" +
                        "常见原因：MRZ 三要素识别有误、证件已锁定（多次失败会锁定，需等待或到发证机关解锁）、" +
                        "或该证件仅支持 PACE。"
                )
            }
            step("BAC 认证通过，会话密钥已协商")

            // ---- 3. 安全报文通道 ----
            val sm = SecureMessaging(session.ksEnc, session.ksMac, session.ssc)
            val fs = EmrtdFileSystem(channel, sm, ::step)
            fileSystemHolder[0] = fs

            // ---- 4. EF.COM ----
            val efCom = fs.readFile(EmrtdFileSystem.EF_COM)
            val dgTags = efCom?.let { DgParsers.parseEfCom(it) } ?: emptyList()
            step("EF.COM：芯片包含数据组 ${dgTags.joinToString(", ").ifEmpty { "未知" }}")

            // ---- 5. 依次读取数据组 ----
            val dataGroups = linkedMapOf<Int, ByteArray>()
            val dg1 = readDataGroup(fs, EmrtdFileSystem.EF_DG1, 1, dataGroups)
            val dg2 = readDataGroup(fs, EmrtdFileSystem.EF_DG2, 2, dataGroups)
            val dg11 = readDataGroup(fs, EmrtdFileSystem.EF_DG11, 11, dataGroups)
            val dg14 = readDataGroup(fs, EmrtdFileSystem.EF_DG14, 14, dataGroups)
            val dg15 = readDataGroup(fs, EmrtdFileSystem.EF_DG15, 15, dataGroups)

            // ---- 6. 主动认证（AA）----
            val activeAuth = dg15?.let { performActiveAuth(fs, it) }
            if (activeAuth == null) {
                step("芯片未提供 DG15，跳过主动认证")
            }

            // ---- 7. EF.SOD + 被动认证 ----
            val sod = fs.readFile(EmrtdFileSystem.EF_SOD)
            val passiveAuth = if (sod != null) {
                step("读取 EF.SOD 完成，开始被动认证…")
                runCatching { PassiveAuth.verify(sod, dataGroups) }
                    .onFailure { step("被动认证执行异常：${it.message}") }
                    .getOrNull()
            } else {
                step("芯片未提供 EF.SOD，跳过被动认证")
                null
            }
            passiveAuth?.let {
                step(
                    "被动认证：摘要比对 ${if (it.allDgHashesMatch) "全部通过" else "存在不一致"}；" +
                        "CMS 签名 ${when (it.cmsSignatureValid) { true -> "有效"; false -> "无效"; null -> "未校验" }}"
                )
            }

            // ---- 8. 解析 ----
            val chipMrzText = dg1?.let { DgParsers.parseDg1(it) }
            val chipMrz = chipMrzText?.let { parseChipMrz(it) }
            val face = dg2?.let { DgParsers.parseDg2(it) }
            val dg11Items = dg11?.let { DgParsers.parseDg11(it) } ?: emptyList()

            val mrzMatches = chipMrz?.let { c ->
                c.documentNumber == ocrMrz.documentNumber &&
                    c.dateOfBirth == ocrMrz.dateOfBirth &&
                    c.dateOfExpiry == ocrMrz.dateOfExpiry
            }

            step("读取完成，用时 ${(System.currentTimeMillis() - startedAt)} ms")

            return Result(
                ocrMrz = ocrMrz,
                chipMrz = chipMrz,
                mrzMatchesOcr = mrzMatches,
                faceImage = face?.bytes,
                faceImageFormat = face?.format,
                dg11Items = dg11Items,
                nativeName = DgParsers.extractNativeName(dg11Items),
                rawDg1Hex = dg1?.let { Hex.encode(it) }.orEmpty(),
                rawDg11Hex = dg11?.let { Hex.encode(it) }.orEmpty(),
                rawDg2HeadHex = dg2?.let { Hex.encode(it.copyOfRange(0, minOf(64, it.size))) }.orEmpty(),
                availableDgTags = dgTags,
                dg14Infos = dg14?.let { DgParsers.describeDg14(it) } ?: emptyList(),
                dg15Info = dg15?.let { DgParsers.describeDg15(it) },
                passiveAuth = passiveAuth,
                activeAuth = activeAuth,
                readDataGroups = dataGroups.keys.toList(),
                steps = steps.toList(),
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        } finally {
            runCatching { channel.close() }
        }
    }

    /**
     * 执行主动认证（AA）。
     *
     * 用 DG15 中的公钥验证芯片对随机挑战值的签名，用于排除「芯片被克隆」。
     * 芯片不支持 AA 时返回 verified = null 而非失败——这是证件的正常配置差异，
     * 不应被当作读取错误。
     */
    private fun performActiveAuth(fs: EmrtdFileSystem, dg15: ByteArray): ActiveAuth.Outcome {
        val key = DgParsers.parseDg15PublicKey(dg15)
        if (key == null) {
            step("DG15 存在但公钥无法解析，跳过主动认证")
            return ActiveAuth.Outcome(
                performed = false, verified = null, algorithm = null, keyDetail = null,
                challengeHex = "", signatureLength = 0, algorithmsTried = 0,
                detail = "DG15 中的主动认证公钥无法解析（可能使用了本机不支持的算法）"
            )
        }

        val challenge = ActiveAuth.newChallenge()
        step("开始主动认证：挑战值 ${Hex.encode(challenge)}（${key.detail}）")

        val cmd = CommandApdu(
            0x00, BacProtocol.INS_INTERNAL_AUTHENTICATE, 0x00, 0x00, challenge, 256
        )
        val rsp = try {
            fs.sendCommand(cmd)
        } catch (e: Exception) {
            step("主动认证命令执行失败：${e.message}")
            return ActiveAuth.Outcome(
                performed = false, verified = null, algorithm = null, keyDetail = key.detail,
                challengeHex = Hex.encode(challenge), signatureLength = 0, algorithmsTried = 0,
                detail = "主动认证命令执行失败：${e.message}"
            )
        }

        if (!rsp.isSuccess || rsp.data.isEmpty()) {
            // 6A81 = 功能不支持，是「该证件未启用 AA」的正常表现
            val notSupported = rsp.status == 0x6A81 || rsp.status == 0x6D00
            step(
                if (notSupported) "该证件未启用主动认证（${rsp.statusHex}）"
                else "芯片拒绝主动认证（${rsp.statusHex}）"
            )
            return ActiveAuth.Outcome(
                performed = true, verified = null, algorithm = null, keyDetail = key.detail,
                challengeHex = Hex.encode(challenge), signatureLength = 0, algorithmsTried = 0,
                detail = if (notSupported) "该证件未启用主动认证（芯片返回 ${rsp.statusHex}）"
                else "芯片拒绝主动认证（芯片返回 ${rsp.statusHex}）"
            )
        }

        val attempt = ActiveAuth.verify(challenge, rsp.data, key.publicKey)
        step(
            if (attempt.ok) "主动认证通过（${attempt.algorithm}）"
            else "主动认证未通过：尝试 ${attempt.tried} 种算法均不匹配"
        )
        return ActiveAuth.Outcome(
            performed = true,
            verified = attempt.ok,
            algorithm = attempt.algorithm,
            keyDetail = key.detail,
            challengeHex = Hex.encode(challenge),
            signatureLength = rsp.data.size,
            algorithmsTried = attempt.tried,
            detail = if (attempt.ok) {
                "主动认证通过，芯片持有与 DG15 公钥配对的私钥，可排除芯片克隆（${attempt.algorithm}）"
            } else {
                "主动认证失败：签名与 DG15 公钥不匹配，该芯片可能是克隆芯片"
            }
        )
    }

    private fun readDataGroup(
        fs: EmrtdFileSystem,
        fileId: Int,
        dgNumber: Int,
        sink: MutableMap<Int, ByteArray>
    ): ByteArray? {
        val data = try {
            fs.readFile(fileId)
        } catch (e: Exception) {
            step("读取 DG$dgNumber 失败：${e.message}")
            null
        }
        if (data == null) {
            step("DG$dgNumber 不存在或读取失败")
        } else {
            sink[dgNumber] = data
            step("DG$dgNumber 读取成功（${data.size} 字节）")
        }
        return data
    }

    /** 把芯片 DG1 中的 MRZ 文本切成行组后解析。 */
    private fun parseChipMrz(mrzText: String): MrzInfo? {
        val clean = mrzText.replace("\r", "").replace("\n", "")

        // 中国签发的往来港澳/台湾通行证在 DG1 中使用专用字段布局，
        // 先按该布局尝试；只有全部校验位（含复合校验位）都通过才会采用，
        // 因此不会误判普通 TD1 证件。
        MrzParser.parseChinesePermitMrz(clean)?.let { return it }

        val candidates = when {
            clean.length == 88 -> listOf(clean.substring(0, 44), clean.substring(44, 88))
            clean.length == 90 -> listOf(clean.substring(0, 30), clean.substring(30, 60), clean.substring(60, 90))
            clean.length == 72 -> listOf(clean.substring(0, 36), clean.substring(36, 72))
            else -> {
                // 尝试按固定宽度切分
                val format = MrzFormat.TD3
                if (clean.length % format.lineLength == 0) {
                    clean.chunked(format.lineLength)
                } else {
                    return null
                }
            }
        }
        return MrzParser.parse(candidates)
    }
}
