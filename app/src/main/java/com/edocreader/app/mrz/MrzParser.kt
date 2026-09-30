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
     * 把各种形态的输入展开成「每行一条」的候选行。
     *
     * 需要处理三种现实情形：
     *   1. 正常的逐行输入；
     *   2. 用户把整段 MRZ 粘进一个输入框（文本里含换行符）；
     *   3. OCR 把多行 MRZ 识别成一整行（没有换行符，但总长度恰好是某格式长度的整数倍，
     *      如护照 2×44 = 88、通行证 3×30 = 90、TD2 2×36 = 72）。
     */
    private fun expandInput(candidateLines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in candidateLines) {
            if (line.any { it == '\n' || it == '\r' }) {
                out.addAll(line.split('\n', '\r').filter { it.isNotBlank() })
            } else if (line.isNotBlank()) {
                out.add(line)
            }
        }

        // 整段没有换行：若长度恰好是某格式长度的整数倍，按该长度切开
        if (out.size == 1) {
            val compact = out[0].replace(" ", "")
            for (len in intArrayOf(44, 30, 36)) {
                if (compact.length >= len * 2 && compact.length % len == 0) {
                    return compact.chunked(len)
                }
            }
        }
        return out
    }

    /**
     * 从 OCR 输出的一组文本行中，找出最像 MRZ 的连续行组。
     *
     * ML Kit 返回的是逐行文本，MRZ 通常位于证件资料页底部，且由 2~3 行等长、
     * 只含 [A-Z0-9<] 的字符串构成。
     */
    fun extractMrzLines(candidateLines: List<String>): List<String>? {
        val normalized = expandInput(candidateLines)
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
        // 先去掉首尾空白：空格在本函数中会被映射成填充符 `<`，
        // 粘贴时多出的一个空格会让行长度多 1 位，导致格式检测直接失败。
        // 若确实是被 OCR 漏掉的 `<`，后续的容差对齐会再补回来。
        for (raw in line.trim().uppercase()) {
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
            // 手动输入三要素由 fromThreeElements 构造，不经过 MRZ 行解析
            MrzFormat.MANUAL -> null
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
        val sanitized = candidateLines.map { sanitizeLine(it) }.filter { it.isNotBlank() }

        // 中国通行证的第 1 行单独就有用：证件号、出生日期、有效期（即 BAC 三要素）都在其中。
        // OCR 只认出一行时也能继续，不必等三行都识别到。
        if (sanitized.size == 1 && sanitized[0].length == 30) {
            parseChinesePermitMrz(sanitized[0])?.let {
                if (!requireValidChecksum || it.allCheckDigitsValid) return it
            }
        }

        val lines = extractMrzLines(candidateLines) ?: return null

        // 三行 30 字符：中国通行证的完整 DG1 布局
        if (lines.size == 3 && lines.all { it.length == 30 }) {
            parseChinesePermitMrz(lines.joinToString(""))?.let {
                if (!requireValidChecksum || it.allCheckDigitsValid) return it
            }
        }

        val info = parse(lines) ?: return null
        if (requireValidChecksum && !info.allCheckDigitsValid) return null
        return info
    }

    // ------------------------------------------------------ 中国通行证专用布局

    /**
     * 解析中国签发的**往来港澳通行证 / 往来台湾通行证**的芯片 MRZ。
     *
     * 这两类证件是 TD1 尺寸的卡片，但 DG1 里的字段布局**不是标准 TD1**：
     * 证件号在第 1 行，有效期反而排在出生日期**之前**。实测两本真实证件的
     * 全部校验位（含复合校验位）都能对上，布局如下（位置为 1 起算）：
     *
     * ```
     * 第 1 行：证件类型码(1-2) 证件号码(3-11) 校验位(12) 填充(13)
     *          有效期(14-19) 校验位(20) 填充(21)
     *          出生日期(22-27) 校验位(28) 填充(29) 复合校验位(30)
     * 第 2 行：12 字符附加信息 + 英文姓名（MRZ 格式，如 ZHENGJIAN<<YANGBEN）
     * 第 3 行：10 字符附加信息 + 填充
     * ```
     *
     * 复合校验位覆盖 `证件号+校验位 ‖ 有效期+校验位 ‖ 出生日期+校验位`。
     *
     * **只有当所有校验位都通过时才认定为此布局**，否则返回 null 交回标准 TD1 解析，
     * 避免把普通 TD1 证件误判。
     *
     * @param raw 90 字符（三行完整 MRZ）或 30 字符（仅第 1 行）。
     *   **只给第 1 行也能用**——证件号、出生日期、有效期都在第 1 行，
     *   这正是 BAC 所需的全部信息；姓名留空，由 DG11 补全。
     */
    fun parseChinesePermitMrz(raw: String): MrzInfo? {
        val clean = raw.replace("\r", "").replace("\n", "")
        if (clean.length != 30 && clean.length != 90) return null
        val l1 = clean.substring(0, 30)

        // 三个分隔位必须是填充符
        if (l1[12] != '<' || l1[20] != '<' || l1[28] != '<') return null

        val docNo = l1.substring(2, 11)
        val docNoCd = l1[11]
        val doe = l1.substring(13, 19)
        val doeCd = l1[19]
        val dob = l1.substring(21, 27)
        val dobCd = l1[27]
        val compositeCd = l1[29]

        // 校验位全部通过才采用此布局
        if (!isCheckDigitValid(docNo, docNoCd)) return null
        if (!isCheckDigitValid(dob, dobCd)) return null
        if (!isCheckDigitValid(doe, doeCd)) return null
        val expectedComposite = checkDigit(docNo + docNoCd + doe + doeCd + dob + dobCd)
        if (expectedComposite != compositeCd) return null

        // 第 2 行偏移 12 之后是英文姓名；只有给全 90 字符时才有第 2 行
        val l2 = if (clean.length == 90) clean.substring(30, 60) else ""
        val nameField = if (l2.length > 12) l2.substring(12).trimEnd('<') else ""
        val (surname, given) = splitNames(nameField)

        val notes = mutableListOf<String>()
        notes.add("按中国通行证专用布局解析（有效期在出生日期之前）")

        return MrzInfo(
            format = MrzFormat.TD1,
            rawLines = if (clean.length == 90) {
                listOf(l1, l2, clean.substring(60, 90))
            } else {
                listOf(l1)
            },
            documentCode = l1.substring(0, 2),
            issuingState = "CHN",
            documentNumber = docNo,
            documentNumberCheckDigit = docNoCd,
            nationality = "CHN",
            dateOfBirth = dob,
            dateOfBirthCheckDigit = dobCd,
            sex = "",
            dateOfExpiry = doe,
            dateOfExpiryCheckDigit = doeCd,
            personalNumber = "",
            personalNumberCheckDigit = '<',
            compositeCheckDigit = compositeCd,
            surname = surname,
            givenNames = given,
            optionalData1 = "",
            optionalData2 = "",
            allCheckDigitsValid = true,
            repairedFields = emptyList(),
            notes = notes
        )
    }

    // ------------------------------------------------------ 三要素直接构造

    /**
     * 由「证件号 / 出生日期 / 有效期」三要素直接构造 MRZ 信息。
     *
     * BAC 口令（MRZi）只需要
     * `证件号+校验位 ‖ 出生日期+校验位 ‖ 有效期+校验位`，
     * 而三个校验位都可以由字段本身算出，因此用户**无需输入完整 MRZ**——
     * 对不便拍摄或 OCR 失败的场景更实用。
     *
     * @param docNoRaw  证件号，字母数字混合（如 `EF1260892`、`CA3273201`）
     * @param dobRaw    出生日期，6 位 `YYMMDD` 或 8 位 `YYYYMMDD`
     * @param expiryRaw 有效期，同上
     * @return 解析结果；任一字段不合法时返回 null
     */
    fun fromThreeElements(docNoRaw: String, dobRaw: String, expiryRaw: String): MrzInfo? {
        val docNo = normalizeDocumentNumber(docNoRaw) ?: return null
        val dob = normalizeMrzDate(dobRaw) ?: return null
        val expiry = normalizeMrzDate(expiryRaw) ?: return null

        val notes = mutableListOf("手动输入三要素，三个校验位由字段自动计算")

        return MrzInfo(
            format = MrzFormat.MANUAL,
            rawLines = listOf(docNo, dob, expiry),
            documentCode = "",
            issuingState = "",
            documentNumber = docNo,
            documentNumberCheckDigit = checkDigit(docNo),
            nationality = "",
            dateOfBirth = dob,
            dateOfBirthCheckDigit = checkDigit(dob),
            sex = "",
            dateOfExpiry = expiry,
            dateOfExpiryCheckDigit = checkDigit(expiry),
            personalNumber = "",
            personalNumberCheckDigit = '<',
            compositeCheckDigit = '<',
            surname = "",
            givenNames = "",
            optionalData1 = "",
            optionalData2 = "",
            allCheckDigitsValid = true,
            repairedFields = emptyList(),
            notes = notes
        )
    }

    /** 规范化证件号：大写、仅保留 MRZ 字符，不足 9 位右侧补 `<`；超过 9 位或为空则返回 null。 */
    fun normalizeDocumentNumber(raw: String): String? {
        val cleaned = raw.uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
        if (cleaned.isEmpty() || cleaned.length > 9) return null
        return cleaned.padEnd(9, '<')
    }

    /** 规范化日期：接受 6 位 `YYMMDD` 或 8 位 `YYYYMMDD`，统一返回 6 位；非法则返回 null。 */
    fun normalizeMrzDate(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val six = when (digits.length) {
            6 -> digits
            8 -> digits.substring(2)
            else -> return null
        }
        val mm = six.substring(2, 4).toIntOrNull() ?: return null
        val dd = six.substring(4, 6).toIntOrNull() ?: return null
        if (mm !in 1..12 || dd !in 1..31) return null
        return six
    }
}
