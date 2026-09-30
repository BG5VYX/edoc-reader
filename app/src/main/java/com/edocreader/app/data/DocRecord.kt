package com.edocreader.app.data

import com.edocreader.app.mrz.MrzInfo
import com.edocreader.app.mrz.MrzParser
import com.edocreader.app.nfc.PassportReader
import com.edocreader.app.nfc.dg.DgParsers
import com.edocreader.app.nfc.pa.PassiveAuth
import com.edocreader.app.util.Hex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 一条证件读取记录。
 *
 * 该对象是本地存储与批量导出的最小单元，字段设计兼顾"人可读"与"机器可解析"：
 * 展示字段直接给出，原始 MRZ 与各数据组的处理结果以结构化形式保留，便于后续审计。
 */
data class DocRecord(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),

    // ---- 证件主体信息 ----
    val certName: String = "",
    val documentCode: String = "",
    val issuingState: String = "",
    val documentNumber: String = "",
    val personalNumber: String = "",
    /** 公民身份号码（18 位，来自 DG11 0x5F10；中国证件专用）。 */
    val idNumber: String = "",
    val fullName: String = "",
    val nativeName: String = "",
    val surname: String = "",
    val givenNames: String = "",
    val gender: String = "",
    val nationality: String = "",
    val dateOfBirth: String = "",
    val dateOfExpiry: String = "",
    val mrzFormat: String = "",

    // ---- 芯片读取结果 ----
    val chipRead: Boolean = false,
    val chipReadElapsedMs: Long = 0,
    val chipMrzMatchesOcr: Boolean? = null,
    val readDataGroups: List<Int> = emptyList(),
    val availableDgTags: List<String> = emptyList(),
    val dg14Infos: List<String> = emptyList(),
    val dg15Info: String = "",
    val dg11Items: List<Dg11Item> = emptyList(),

    // ---- 被动认证 ----
    val passiveAuthHashAlgorithm: String = "",
    val passiveAuthAllDgMatch: Boolean? = null,
    val passiveAuthCmsSignatureValid: Boolean? = null,
    val passiveAuthSignerSubject: String = "",
    val passiveAuthSignerIssuer: String = "",
    val passiveAuthSignerValidTo: String = "",
    val passiveAuthNotes: List<String> = emptyList(),
    val passiveAuthDgDetails: List<DgCheck> = emptyList(),

    // ---- CSCA 信任链 ----
    /** 是否链接到签发国 CSCA 根证书。 */
    val cscaTrusted: Boolean = false,
    val cscaSubject: String = "",
    val cscaSerial: String = "",
    val cscaNotAfter: String = "",
    val cscaCurrentlyExpired: Boolean = false,
    val cscaChainDetail: String = "",
    val cscaStoreSize: Int = 0,

    // ---- 主动认证（AA）----
    /** 是否实际发起了 AA。 */
    val activeAuthPerformed: Boolean = false,
    /** 验签结果；芯片不支持 AA 时为 null。 */
    val activeAuthVerified: Boolean? = null,
    val activeAuthAlgorithm: String = "",
    val activeAuthKeyDetail: String = "",
    val activeAuthChallenge: String = "",
    val activeAuthSignatureLength: Int = 0,
    val activeAuthDetail: String = "",

    // ---- 原始数据 ----
    val ocrMrzLines: List<String> = emptyList(),
    val chipMrzLines: List<String> = emptyList(),
    val ocrRepairs: List<String> = emptyList(),
    val ocrNotes: List<String> = emptyList(),

    // ---- 面部图像 ----
    val faceImageFileName: String = "",
    val faceImageFormat: String = "",
    val faceImageBytes: Int = 0,

    // ---- 过程日志 ----
    val steps: List<String> = emptyList()
) {
    data class Dg11Item(val tag: String, val label: String, val value: String)
    data class DgCheck(
        val dgNumber: Int,
        val present: Boolean,
        val matches: Boolean?,
        val expectedHash: String?,
        val computedHash: String?
    )


    /**
     * 附加人脸图像信息，返回一份新的记录。
     *
     * 这里显式构造而不用 data class 自动生成的 copy()：
     * DocRecord 有 50+ 个字段，copy() 会生成一个参数极多的
     * copy$default 合成方法，一旦编译产物不同步就会出现
     * NoSuchMethodError，且难以排查。
     */
    fun withFaceImage(fileName: String, byteCount: Int): DocRecord = DocRecord(
        id = id,
        createdAt = createdAt,
        certName = certName,
        documentCode = documentCode,
        issuingState = issuingState,
        documentNumber = documentNumber,
        personalNumber = personalNumber,
        idNumber = idNumber,
        fullName = fullName,
        nativeName = nativeName,
        surname = surname,
        givenNames = givenNames,
        gender = gender,
        nationality = nationality,
        dateOfBirth = dateOfBirth,
        dateOfExpiry = dateOfExpiry,
        mrzFormat = mrzFormat,
        chipRead = chipRead,
        chipReadElapsedMs = chipReadElapsedMs,
        chipMrzMatchesOcr = chipMrzMatchesOcr,
        readDataGroups = readDataGroups,
        availableDgTags = availableDgTags,
        dg14Infos = dg14Infos,
        dg15Info = dg15Info,
        dg11Items = dg11Items,
        passiveAuthHashAlgorithm = passiveAuthHashAlgorithm,
        passiveAuthAllDgMatch = passiveAuthAllDgMatch,
        passiveAuthCmsSignatureValid = passiveAuthCmsSignatureValid,
        passiveAuthSignerSubject = passiveAuthSignerSubject,
        passiveAuthSignerIssuer = passiveAuthSignerIssuer,
        passiveAuthSignerValidTo = passiveAuthSignerValidTo,
        passiveAuthNotes = passiveAuthNotes,
        passiveAuthDgDetails = passiveAuthDgDetails,
        cscaTrusted = cscaTrusted,
        cscaSubject = cscaSubject,
        cscaSerial = cscaSerial,
        cscaNotAfter = cscaNotAfter,
        cscaCurrentlyExpired = cscaCurrentlyExpired,
        cscaChainDetail = cscaChainDetail,
        cscaStoreSize = cscaStoreSize,
        activeAuthPerformed = activeAuthPerformed,
        activeAuthVerified = activeAuthVerified,
        activeAuthAlgorithm = activeAuthAlgorithm,
        activeAuthKeyDetail = activeAuthKeyDetail,
        activeAuthChallenge = activeAuthChallenge,
        activeAuthSignatureLength = activeAuthSignatureLength,
        activeAuthDetail = activeAuthDetail,
        ocrMrzLines = ocrMrzLines,
        chipMrzLines = chipMrzLines,
        ocrRepairs = ocrRepairs,
        ocrNotes = ocrNotes,
        faceImageFileName = fileName,
        faceImageFormat = faceImageFormat,
        faceImageBytes = byteCount,
        steps = steps,
    )

    /** 展示用时间。 */
    val createdAtText: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(createdAt))

    /** 列表页标题。 */
    val displayTitle: String
        get() = fullName.ifBlank { nativeName }.ifBlank { documentNumber }

    val displaySubtitle: String
        get() = listOf(certName, documentNumber, issuingState).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {

        /** 由读取结果构造记录。 */
        fun fromReadResult(
            result: PassportReader.Result,
            certName: String,
            faceImageFileName: String
        ): DocRecord {
            val mrz = result.chipMrz ?: result.ocrMrz
            val pa = result.passiveAuth

            // 中国签发的通行证把英文姓名与公民身份号码放在 DG11 中，
            // 且 MRZ 里没有独立性别字段——这里用 DG11 补全。
            val dg11Name = DgParsers.extractEnglishName(result.dg11Items)
            val dg11Id = DgParsers.extractIdNumber(result.dg11Items)
            val (nameSurname, nameGiven) = if (dg11Name != null) {
                MrzParser.splitNames(dg11Name)
            } else {
                mrz.surname to mrz.givenNames
            }
            val fullName = if (dg11Name != null) {
                listOf(nameSurname, nameGiven).filter { it.isNotBlank() }.joinToString(" ")
            } else {
                mrz.fullNameEnglish
            }
            val gender = mrz.genderLabel.takeIf { mrz.sex.isNotBlank() }
                ?: DgParsers.genderFromIdNumber(dg11Id).orEmpty()

            return DocRecord(
                certName = certName,
                documentCode = mrz.documentCode,
                issuingState = mrz.issuingState,
                documentNumber = mrz.documentNumber,
                personalNumber = mrz.personalNumber,
                idNumber = dg11Id.orEmpty(),
                fullName = fullName,
                nativeName = result.nativeName.orEmpty(),
                surname = nameSurname,
                givenNames = nameGiven,
                gender = gender,
                nationality = mrz.nationality,
                dateOfBirth = mrz.birthDateIso,
                dateOfExpiry = mrz.expiryDateIso,
                mrzFormat = mrz.format.label,

                chipRead = true,
                chipReadElapsedMs = result.elapsedMs,
                chipMrzMatchesOcr = result.mrzMatchesOcr,
                readDataGroups = result.readDataGroups,
                availableDgTags = result.availableDgTags,
                dg14Infos = result.dg14Infos,
                dg15Info = result.dg15Info.orEmpty(),
                dg11Items = result.dg11Items.map {
                    Dg11Item(String.format("0x%02X", it.tag), it.label, it.value)
                },

                passiveAuthHashAlgorithm = pa?.hashAlgorithm.orEmpty(),
                passiveAuthAllDgMatch = pa?.allDgHashesMatch,
                passiveAuthCmsSignatureValid = pa?.cmsSignatureValid,
                passiveAuthSignerSubject = pa?.signerSubject.orEmpty(),
                passiveAuthSignerIssuer = pa?.signerIssuer.orEmpty(),
                passiveAuthSignerValidTo = pa?.signerValidTo.orEmpty(),
                passiveAuthNotes = pa?.messages.orEmpty(),
                passiveAuthDgDetails = pa?.dgVerifications?.map {
                    DgCheck(it.dgNumber, it.present, it.matches, it.expectedHash, it.computedHash)
                }.orEmpty(),

                cscaTrusted = pa?.cscaTrusted ?: false,
                cscaSubject = pa?.cscaSubject.orEmpty(),
                cscaSerial = pa?.cscaSerial.orEmpty(),
                cscaNotAfter = pa?.cscaNotAfter.orEmpty(),
                cscaCurrentlyExpired = pa?.cscaCurrentlyExpired ?: false,
                cscaChainDetail = pa?.cscaChainDetail.orEmpty(),
                cscaStoreSize = pa?.cscaStoreSize ?: 0,

                activeAuthPerformed = result.activeAuth?.performed ?: false,
                activeAuthVerified = result.activeAuth?.verified,
                activeAuthAlgorithm = result.activeAuth?.algorithm.orEmpty(),
                activeAuthKeyDetail = result.activeAuth?.keyDetail.orEmpty(),
                activeAuthChallenge = result.activeAuth?.challengeHex.orEmpty(),
                activeAuthSignatureLength = result.activeAuth?.signatureLength ?: 0,
                activeAuthDetail = result.activeAuth?.detail.orEmpty(),

                ocrMrzLines = result.ocrMrz.rawLines,
                chipMrzLines = result.chipMrz?.rawLines.orEmpty(),
                ocrRepairs = result.ocrMrz.repairedFields,
                ocrNotes = result.ocrMrz.notes,

                faceImageFileName = faceImageFileName,
                faceImageFormat = result.faceImageFormat.orEmpty(),
                faceImageBytes = result.faceImage?.size ?: 0,

                steps = result.steps
            )
        }

        /** 仅 OCR（未读芯片）时也允许保存一条记录，便于补读或人工核对。 */
        fun fromOcrOnly(mrz: MrzInfo, certName: String): DocRecord = DocRecord(
            certName = certName,
            documentCode = mrz.documentCode,
            issuingState = mrz.issuingState,
            documentNumber = mrz.documentNumber,
            personalNumber = mrz.personalNumber,
            fullName = mrz.fullNameEnglish,
            surname = mrz.surname,
            givenNames = mrz.givenNames,
            gender = mrz.genderLabel,
            nationality = mrz.nationality,
            dateOfBirth = mrz.birthDateIso,
            dateOfExpiry = mrz.expiryDateIso,
            mrzFormat = mrz.format.label,
            chipRead = false,
            ocrMrzLines = mrz.rawLines,
            ocrRepairs = mrz.repairedFields,
            ocrNotes = mrz.notes
        )

        fun passiveAuthToChecks(pa: PassiveAuth.Result?): List<DgCheck> = pa?.dgVerifications?.map {
            DgCheck(it.dgNumber, it.present, it.matches, it.expectedHash, it.computedHash)
        }.orEmpty()

        fun hex(bytes: ByteArray?): String = bytes?.let { Hex.encode(it) } ?: ""
    }
}
