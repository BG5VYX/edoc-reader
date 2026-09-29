package com.edocreader.app.mrz

/**
 * ICAO 9303 机读区（MRZ）解析与校验/纠错。
 *
 * 职责：
 *  1. 把 OCR 得到的若干行文本整理成合法的 TD1 / TD2 / TD3 行组；
 *  2. 按规范切分字段；
 *  3. 计算并校验各校验位（含复合校验位）；
 *  4. 当校验失败时，尝试有限度的"按校验位反推纠错"，以挽救 OCR 的字母/数字混淆。
 */
object MrzParser {

    private val CHECK_WEIGHTS = intArrayOf(7, 3, 1)

    // ---------------------------------------------------------------- 字符值

    /** 单个 MRZ 字符的加权值：0-9 → 0-9，A-Z → 10-35，'<' → 0。 */
    fun charValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        '<' -> 0
        else -> -1
    }

    /** 计算一段 MRZ 文本的校验位。返回 '0'..'9'。 */
    fun checkDigit(data: String): Char {
        var sum = 0
        for (i in data.indices) {
            val v = charValue(data[i])
            if (v < 0) return '?'
            sum += v * CHECK_WEIGHTS[i % 3]
        }
        return ('0' + (sum % 10))
    }

    /**
     * MRZ 允许出现的全部字符（ICAO 9303-3）：
     * 数字、大写字母与填充符 `<`，共 37 个。
     * 证件号、个人编号等**字母数字混合字段**的纠错必须在此全集内进行。
     */
    private const val MRZ_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<"

    // ------------------------------------------------------------ 行组整理

    /**
     * 从 OCR 输出的一组文本行中，找出最像 MRZ 的连续行组。
     *
     * ML Kit 返回的是逐行文本，MRZ 通常位于证件资料页底部，且由 2~3 行等长、
     * 只含 [A-Z0-9<] 的字符串构成。
     */
    fun extractMrzLines(candidateLines: List<String>): List<String>? {
        val normalized = candidateLines
            .map { sanitizeLine(it) }
            .filter { it.length >= 28 }

        // TD1 / TD3 优先（证件主体类型），逐个窗口尝试
        for (format in listOf(MrzFormat.TD1, MrzFormat.TD3, MrzFormat.TD2)) {
            val n = format.lineCount
            val len = format.lineLength
            for (start in 0..(normalized.size - n)) {
                val window = normalized.subList(start, start + n)
                if (window.all { it.length == len }) return window.toList()
            }
        }

        // 退化情形：行长度有 1~2 个字符的偏差（首尾可能被裁掉），做对齐裁剪后重试
        for (format in listOf(MrzFormat.TD1, MrzFormat.TD3, MrzFormat.TD2)) {
            val n = format.lineCount
            val len = format.lineLength
            for (start in 0..(normalized.size - n)) {
                val window = normalized.subList(start, start + n)
                if (window.all { kotlin.math.abs(it.length - len) <= 2 }) {
                    return window.map { adjustLength(it, len) }
                }
            }
        }
        return null
    }

    /** 去掉空白与常见噪声字符，统一大写，把 OCR 常见的伪字符映射回 MRZ 字符集。 */
    fun sanitizeLine(line: String): String {
        val sb = StringBuilder()
        for (raw in line.uppercase()) {
            when {
                raw in 'A'..'Z' || raw in '0'..'9' || raw == '<' -> sb.append(raw)
                // OCR 常见的标点/符号误识别
                raw == ' ' || raw == '.' || raw == ',' || raw == ':' || raw == ';' ||
                    raw == '\'' || raw == '"' || raw == '`' || raw == '|' || raw == '_' -> sb.append('<')
                raw == '*' || raw == '»' || raw == '«' || raw == '>' -> sb.append('<')
                raw == '§' -> sb.append('5')
                raw == '€' -> sb.append('E')
                else -> { /* 丢弃 */ }
            }
        }
        return sb.toString()
    }

    private fun adjustLength(line: String, len: Int): String = when {
        line.length == len -> line
        line.length > len -> line.substring(0, len)
        else -> line.padEnd(len, '<')
    }

    // ---------------------------------------------------------------- 解析

    /** 解析一个已对齐的 MRZ 行组。返回 null 表示格式不匹配。 */
    fun parse(lines: List<String>): MrzInfo? {
        val format = MrzFormat.detect(lines) ?: return null
        val l = lines.map { it }
        val notes = mutableListOf<String>()
        val repaired = mutableListOf<String>()

        return when (format) {
            MrzFormat.TD3 -> parseTd3(l, notes, repaired)
            MrzFormat.TD2 -> parseTd2(l, notes, repaired)
            MrzFormat.TD1 -> parseTd1(l, notes, repaired)
        }
    }

    // -------------------------------------------------------------- TD3（护照）

    private fun parseTd3(l: List<String>, notes: MutableList<String>, repaired: MutableList<String>): MrzInfo {
        val line1 = l[0]
        val line2 = l[1]

        val documentCode = line1.substring(0, 2)
        val issuingState = line1.substring(2, 5)
        val names = line1.substring(5)
        val (surname, given) = splitNames(names)

        val docNoRaw = line2.substring(0, 9)
        val docNoCd = line2[9]
        val nationality = line2.substring(10, 13)
        val dobRaw = line2.substring(13, 19)
        val dobCd = line2[19]
        val sex = line2[20].toString()
        val doeRaw = line2.substring(21, 27)
        val doeCd = line2[27]
        val personalRaw = line2.substring(28, 42)
        val personalCd = line2[42]
        val compositeCd = line2[43]

        val docNo = repairAlphaNumericField("证件号码", docNoRaw, docNoCd, repaired, notes)
        val dob = repairNumericField("出生日期", dobRaw, dobCd, repaired, notes)
        val doe = repairNumericField("有效期", doeRaw, doeCd, repaired, notes)
        val personal = if (personalRaw.all { it == '<' }) personalRaw
        else repairAlphaNumericField("个人编号", personalRaw, personalCd, repaired, notes)

        val expectedComposite = checkDigit(docNo + docNoCd + dob + dobCd + doe + doeCd + personal + personalCd)
        val compositeOk = expectedComposite == compositeCd
        if (!compositeOk) notes.add("复合校验位不匹配（期望 $expectedComposite，实际 $compositeCd）")

        val ok = isCheckDigitValid(docNo, docNoCd) &&
            isCheckDigitValid(dob, dobCd) &&
            isCheckDigitValid(doe, doeCd) &&
            (personalRaw.all { it == '<' } || isCheckDigitValid(personal, personalCd)) &&
            compositeOk

        return MrzInfo(
            format = MrzFormat.TD3,
            rawLines = l,
            documentCode = documentCode,
            issuingState = issuingState,
            documentNumber = docNo,
            documentNumberCheckDigit = docNoCd,
            nationality = nationality,
            dateOfBirth = dob,
            dateOfBirthCheckDigit = dobCd,
            sex = sex,
            dateOfExpiry = doe,
            dateOfExpiryCheckDigit = doeCd,
            personalNumber = personal,
            personalNumberCheckDigit = personalCd,
            compositeCheckDigit = compositeCd,
            surname = surname,
            givenNames = given,
            optionalData1 = personal,
            optionalData2 = "",
            allCheckDigitsValid = ok,
            repairedFields = repaired,
            notes = notes
        )
    }

    // ---------------------------------------------------------------- TD2

    private fun parseTd2(l: List<String>, notes: MutableList<String>, repaired: MutableList<String>): MrzInfo {
        val line1 = l[0]
        val line2 = l[1]

        val documentCode = line1.substring(0, 2)
        val issuingState = line1.substring(2, 5)
        val names = line1.substring(5, 36)

        val docNoRaw = line2.substring(0, 9)
        val docNoCd = line2[9]
        val dobRaw = line2.substring(13, 19)
        val dobCd = line2[19]
        val sex = line2[20].toString()
        val doeRaw = line2.substring(21, 27)
        val doeCd = line2[27]
        val nationality = line2.substring(10, 13)
        val optional = line2.substring(28, 35)
        val compositeCd = line2[35]

        val (surname, given) = splitNames(names)

        val docNo = repairAlphaNumericField("证件号码", docNoRaw, docNoCd, repaired, notes)
        val dob = repairNumericField("出生日期", dobRaw, dobCd, repaired, notes)
        val doe = repairNumericField("有效期", doeRaw, doeCd, repaired, notes)

        val expectedComposite = checkDigit(docNo + docNoCd + dob + dobCd + doe + doeCd + optional)
        val compositeOk = expectedComposite == compositeCd
        if (!compositeOk) notes.add("复合校验位不匹配（期望 $expectedComposite，实际 $compositeCd）")

        val ok = isCheckDigitValid(docNo, docNoCd) && isCheckDigitValid(dob, dobCd) &&
            isCheckDigitValid(doe, doeCd) && compositeOk

        return MrzInfo(
            format = MrzFormat.TD2,
            rawLines = l,
            documentCode = documentCode,
            issuingState = issuingState,
            documentNumber = docNo,
            documentNumberCheckDigit = docNoCd,
            nationality = nationality,
            dateOfBirth = dob,
            dateOfBirthCheckDigit = dobCd,
            sex = sex,
            dateOfExpiry = doe,
            dateOfExpiryCheckDigit = doeCd,
            personalNumber = "",
            personalNumberCheckDigit = '<',
            compositeCheckDigit = compositeCd,
            surname = surname,
            givenNames = given,
            optionalData1 = optional,
            optionalData2 = "",
            allCheckDigitsValid = ok,
            repairedFields = repaired,
            notes = notes
        )
    }

    // ------------------------------------------- TD1（往来港澳通行证卡等）

    private fun parseTd1(l: List<String>, notes: MutableList<String>, repaired: MutableList<String>): MrzInfo {
        val line1 = l[0]
        val line2 = l[1]
        val line3 = l[2]

        val documentCode = line1.substring(0, 2)
        val issuingState = line1.substring(2, 5)
        val docNoRaw = line1.substring(5, 14)
        val docNoCd = line1[14]
        val optional1 = line1.substring(15, 30)

        val dobRaw = line2.substring(0, 6)
        val dobCd = line2[6]
        val sex = line2[7].toString()
        val doeRaw = line2.substring(8, 14)
        val doeCd = line2[14]
        val nationality = line2.substring(15, 18)
        val optional2 = line2.substring(18, 29)
        val compositeCd = line2[29]

        val (surname, given) = splitNames(line3)

        val docNo = repairAlphaNumericField("证件号码", docNoRaw, docNoCd, repaired, notes)
        val dob = repairNumericField("出生日期", dobRaw, dobCd, repaired, notes)
        val doe = repairNumericField("有效期", doeRaw, doeCd, repaired, notes)

        val expectedComposite = checkDigit(docNo + docNoCd + dob + dobCd + doe + doeCd + optional2)
        val compositeOk = expectedComposite == compositeCd
        if (!compositeOk) notes.add("复合校验位不匹配（期望 $expectedComposite，实际 $compositeCd）")

        val ok = isCheckDigitValid(docNo, docNoCd) && isCheckDigitValid(dob, dobCd) &&
            isCheckDigitValid(doe, doeCd) && compositeOk

        return MrzInfo(
            format = MrzFormat.TD1,
            rawLines = l,
            documentCode = documentCode,
            issuingState = issuingState,
            documentNumber = docNo,
            documentNumberCheckDigit = docNoCd,
            nationality = nationality,
            dateOfBirth = dob,
            dateOfBirthCheckDigit = dobCd,
            sex = sex,
            dateOfExpiry = doe,
            dateOfExpiryCheckDigit = doeCd,
            personalNumber = "",
            personalNumberCheckDigit = '<',
            compositeCheckDigit = compositeCd,
            surname = surname,
            givenNames = given,
            optionalData1 = optional1,
            optionalData2 = optional2,
            allCheckDigitsValid = ok,
            repairedFields = repaired,
            notes = notes
        )
    }

    // ------------------------------------------------------------- 公共工具

    /** MRZ 姓名域：`SURNAME<<GIVEN<NAMES`。 */
    fun splitNames(field: String): Pair<String, String> {
        val idx = field.indexOf("<<")
        return if (idx < 0) {
            field.replace('<', ' ').trim() to ""
        } else {
            val surname = field.substring(0, idx).replace('<', ' ').trim()
            val given = field.substring(idx + 2).replace('<', ' ').trim()
            surname to given
        }
    }

    fun isCheckDigitValid(field: String, checkDigit: Char): Boolean =
        checkDigit(field) == checkDigit

    /**
     * 数字型字段纠错。
     *
     * 策略（由轻到重，命中即返回）：
     *  1. 原样通过校验 → 直接返回；
     *  2. 把 OCR 常见的字母→数字混淆做统一映射（O→0、I/L→1、S→5、B→8、Z→2 …）；
     *  3. 单字符穷举：把每一位替换为 [0-9<] 中的候选，寻找满足校验位的组合；
     *  4. 双字符穷举（仅证件号等较短字段），成本可控。
     */
    private fun repairNumericField(
        label: String,
        raw: String,
        checkDigit: Char,
        repaired: MutableList<String>,
        notes: MutableList<String>
    ): String {
        if (raw.all { it == '<' }) return raw
        if (isCheckDigitValid(raw, checkDigit)) return raw

        val normalized = raw.map { letterToDigit(it) }.joinToString("")
        if (isCheckDigitValid(normalized, checkDigit)) {
            repaired.add("$label：字母→数字规范化（$raw → $normalized）")
            return normalized
        }

        val alphabet = "0123456789<"
        val single = bruteForce(normalized, checkDigit, alphabet, maxChanges = 1)
        if (single != null) {
            repaired.add("$label：单字符纠错（$raw → $single）")
            return single
        }

        if (normalized.length <= 15) {
            val double = bruteForce(normalized, checkDigit, alphabet, maxChanges = 2)
            if (double != null) {
                repaired.add("$label：双字符纠错（$raw → $double）")
                return double
            }
        }

        notes.add("$label：校验位纠错失败，保留原值（$raw）")
        return normalized
    }

    /**
     * 字母数字混合字段（证件号、个人编号）的纠错。
     *
     * 证件号**不是纯数字**：中国护照号形如 `EF1260892`，往来港澳通行证号形如 `CA3273201`，
     * 都含字母。因此必须：
     *   1. 在包含字母的**完整 MRZ 字符集**内做纠错，否则字母位永远无法被修正；
     *   2. 绝不套用「字母→数字」规范化，否则 `EF1260892` 会被破坏成 `8F1260892`，
     *      进而导致 BAC 口令错误、读卡失败；
     *   3. 纠错失败时保留原值，宁可交由上层判断，也不要静默篡改。
     */
    private fun repairAlphaNumericField(
        label: String,
        raw: String,
        checkDigit: Char,
        repaired: MutableList<String>,
        notes: MutableList<String>
    ): String {
        if (raw.all { it == '<' }) return raw
        if (isCheckDigitValid(raw, checkDigit)) return raw

        val single = bruteForce(raw, checkDigit, MRZ_ALPHABET, maxChanges = 1)
        if (single != null) {
            repaired.add("$label：单字符纠错（$raw → $single）")
            return single
        }

        if (raw.length <= 15) {
            val double = bruteForce(raw, checkDigit, MRZ_ALPHABET, maxChanges = 2)
            if (double != null) {
                repaired.add("$label：双字符纠错（$raw → $double）")
                return double
            }
        }

        notes.add("$label：校验位纠错失败，保留原值（$raw）")
        return raw
    }

    private fun letterToDigit(c: Char): Char = when (c) {
        'O', 'Q', 'D', 'U', '0' -> if (c == '0') '0' else '0'
        'I', 'L', 'J', 'T' -> if (c == 'T') '7' else '1'
        'Z' -> '2'
        'A' -> '4'
        'S' -> '5'
        'G' -> '6'
        'B' -> '8'
        'E' -> '8'
        else -> c
    }

    /**
     * 在 [alphabet] 内最多改动 [maxChanges] 个字符，使校验位等于 [expected]。
     * 返回修复后的字符串，找不到返回 null。
     */
    private fun bruteForce(
        field: String,
        expected: Char,
        alphabet: String,
        maxChanges: Int
    ): String? {
        val chars = field.toCharArray()

        fun tryChange(startIdx: Int, changesLeft: Int): String? {
            if (changesLeft == 0) return null
            for (p in startIdx until chars.size) {
                val original = chars[p]
                for (cand in alphabet) {
                    if (cand == original) continue
                    chars[p] = cand
                    if (checkDigit(String(chars)) == expected) {
                        val fixed = String(chars)
                        chars[p] = original
                        return fixed
                    }
                    if (changesLeft > 1) {
                        val deeper = tryChange(p + 1, changesLeft - 1)
                        if (deeper != null) {
                            chars[p] = original
                            return deeper
                        }
                    }
                    chars[p] = original
                }
            }
            return null
        }

        return tryChange(0, maxChanges)
    }

    /**
     * 一站式入口：给定 OCR 得到的候选文本行，返回解析结果。
     * [requireValidChecksum] 为 true 时只接受校验位全部通过的解析结果。
     */
    fun parseFromLines(
        candidateLines: List<String>,
        requireValidChecksum: Boolean = false
    ): MrzInfo? {
        val lines = extractMrzLines(candidateLines) ?: return null
        val info = parse(lines) ?: return null
        if (requireValidChecksum && !info.allCheckDigitsValid) return null
        return info
    }
}
