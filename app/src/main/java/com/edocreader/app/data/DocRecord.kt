package com.edocreader.app.data

import com.edocreader.app.mrz.MrzInfo
import com.edocreader.app.nfc.PassportReader
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

            return DocRecord(
                certName = certName,
                documentCode = mrz.documentCode,
                issuingState = mrz.issuingState,
                documentNumber = mrz.documentNumber,
                personalNumber = mrz.personalNumber,
                fullName = mrz.fullNameEnglish,
                nativeName = result.nativeName.orEmpty(),
                surname = mrz.surname,
                givenNames = mrz.givenNames,
                gender = mrz.genderLabel,
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
