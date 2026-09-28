package com.edocreader.app.mrz

import java.util.Calendar

/** 机读区格式。 */
enum class MrzFormat(val label: String, val lineLength: Int, val lineCount: Int) {
    /** 3 行 × 30 字符：护照卡、往来港澳/台湾通行证卡、身份证等。 */
    TD1("TD1（3×30）", 30, 3),

    /** 2 行 × 36 字符：旧式身份证件。 */
    TD2("TD2（2×36）", 36, 2),

    /** 2 行 × 44 字符：护照（含中国电子普通护照）。 */
    TD3("TD3（2×44）", 44, 2);

    companion object {
        fun detect(lines: List<String>): MrzFormat? {
            val lens = lines.map { it.length }
            return when {
                lines.size == 3 && lens.all { it == 30 } -> TD1
                lines.size == 2 && lens.all { it == 36 } -> TD2
                lines.size == 2 && lens.all { it == 44 } -> TD3
                else -> null
            }
        }
    }
}

/**
 * 解析后的 MRZ 信息。
 *
 * 字段命名与 ICAO 9303 Part 3 保持一致，日期保留原始 YYMMDD 形式（BAC 口令必须使用
 * 原始形式），同时提供 YYYY-MM-DD 的展示形式。
 */
data class MrzInfo(
    val format: MrzFormat,
    val rawLines: List<String>,
    val documentCode: String,
    val issuingState: String,
    val documentNumber: String,
    val documentNumberCheckDigit: Char,
    val nationality: String,
    val dateOfBirth: String,
    val dateOfBirthCheckDigit: Char,
    val sex: String,
    val dateOfExpiry: String,
    val dateOfExpiryCheckDigit: Char,
    val personalNumber: String,
    val personalNumberCheckDigit: Char,
    val compositeCheckDigit: Char,
    val surname: String,
    val givenNames: String,
    val optionalData1: String,
    val optionalData2: String,
    val allCheckDigitsValid: Boolean,
    val repairedFields: List<String>,
    val notes: List<String>
) {

    /** BAC 口令（MRZi）：证件号+校验位 ‖ 出生日期+校验位 ‖ 有效期+校验位。 */
    val mrzInformation: String
        get() = buildString {
            append(documentNumber)
            append(documentNumberCheckDigit)
            append(dateOfBirth)
            append(dateOfBirthCheckDigit)
            append(dateOfExpiry)
            append(dateOfExpiryCheckDigit)
        }

    /** 英文姓名。中国签发的证件按「姓 名」顺序显示（如 ZHANG SAN）。 */
    val fullNameEnglish: String
        get() = listOf(surname, givenNames).filter { it.isNotBlank() }.joinToString(" ")

    val documentTypeLabel: String
        get() = when {
            documentCode.startsWith("P") -> "护照 / Passport"
            documentCode.startsWith("C") -> "往来港澳通行证"
            documentCode.startsWith("I") -> "身份证件 / ID"
            documentCode.startsWith("V") -> "签证 / Visa"
            documentCode.startsWith("A") -> "外交/公务证件"
            documentCode.startsWith("D") -> "外交证件"
            else -> "未知证件类型（$documentCode）"
        }

    val genderLabel: String
        get() = when (sex.uppercase()) {
            "M" -> "男 / M"
            "F" -> "女 / F"
            else -> "未指定 / X"
        }

    val birthDateIso: String get() = MrzDate.toIso(dateOfBirth, isBirthDate = true)
    val expiryDateIso: String get() = MrzDate.toIso(dateOfExpiry, isBirthDate = false)

    val displayName: String
        get() = surname.replace('<', ' ').trim().ifBlank { documentNumber }
}

/** YYMMDD ↔ YYYY-MM-DD 转换（含世纪推断）。 */
object MrzDate {

    fun toIso(yymmdd: String, isBirthDate: Boolean): String {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return yymmdd
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4)
        val dd = yymmdd.substring(4, 6)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentCentury = currentYear / 100 * 100
        val currentYY = currentYear % 100
        val century = if (isBirthDate) {
            // 出生日期：不可能晚于今天
            if (yy > currentYY) currentCentury - 100 else currentCentury
        } else {
            // 有效期：一般落在本世纪，若明显过早则视为上世纪末（长期证件）
            if (yy > currentYY + 30) currentCentury - 100 else currentCentury
        }
        return String.format("%04d-%s-%s", century + yy, mm, dd)
    }

    /** 判断证件是否已过期。 */
    fun isExpired(yymmdd: String): Boolean {
        val iso = toIso(yymmdd, isBirthDate = false)
        if (iso.length != 10) return false
        return try {
            val parts = iso.split("-")
            val cal = Calendar.getInstance()
            val exp = Calendar.getInstance().apply {
                set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 23, 59, 59)
            }
            exp.before(cal)
        } catch (_: Exception) {
            false
        }
    }
}
