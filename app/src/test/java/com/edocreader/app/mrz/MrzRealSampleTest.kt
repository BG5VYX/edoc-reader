package com.edocreader.app.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实证件样本验证 MRZ 字段的位数与取值。
 *
 * 关键不变量（ICAO 9303-3，TD1 / TD2 / TD3 一致）：
 *   · 证件号   9 位（**字母数字混合**，如护照 `EF1260892`、往来港澳通行证 `CA3273201`）
 *   · 出生日期 6 位（YYMMDD）
 *   · 有效期   6 位（YYMMDD）
 *
 * 样本来源：中华人民共和国电子普通护照、往来港澳通行证的证件样本。
 */
class MrzRealSampleTest {

    /** 电子普通护照样本：证件号 EF1260892，1985-03-20 生，2029-01-17 到期。 */
    private val passportLine1 = "POCHNZHENGJIAN<<YANGBEN".padEnd(44, '<')
    private val passportLine2 = "EF12608921CHN8503208F2901178NGKELMPONBPJB978"

    /** 往来港澳通行证样本（TD1）：证件号 CA3273201，1981-08-03 生，2029-01-17 到期。 */
    private val permitLine1 = "C<CHNCA32732010<<<<<<<<<<<<<<<"
    private val permitLine2 = "8108038F2901178CHN<<<<<<<<<<<6"
    private val permitLine3 = "ZHENGJIAN<<YANGBEN".padEnd(30, '<')

    /** 按 TD3 规格构造第 2 行（自动算好全部校验位，共 44 字符）。 */
    private fun td3Line2(
        docNo: String,
        docNoCd: Char = MrzParser.checkDigit(docNo),
        dob: String = "850320",
        doe: String = "290117"
    ): String {
        val dobCd = MrzParser.checkDigit(dob)
        val doeCd = MrzParser.checkDigit(doe)
        val personal = "<".repeat(14)
        val personalCd = MrzParser.checkDigit(personal)
        val composite = MrzParser.checkDigit(
            docNo + docNoCd + dob + dobCd + doe + doeCd + personal + personalCd
        )
        return docNo + docNoCd + "CHN" + dob + dobCd + "F" + doe + doeCd +
            personal + personalCd + composite
    }

    private val td3Line1 = "P<CHN".padEnd(44, '<')

    // ------------------------------------------------------------ 护照（TD3）

    @Test
    fun `护照样本的行长度符合 TD3 规格`() {
        assertEquals(44, passportLine1.length)
        assertEquals(44, passportLine2.length)
        assertEquals(MrzFormat.TD3, MrzFormat.detect(listOf(passportLine1, passportLine2)))
    }

    @Test
    fun `护照样本的字段位数正确`() {
        val info = MrzParser.parse(listOf(passportLine1, passportLine2))
        assertNotNull(info)
        info!!

        assertEquals("证件号应为 9 位", 9, info.documentNumber.length)
        assertEquals("EF1260892", info.documentNumber)

        assertEquals("出生日期应为 6 位", 6, info.dateOfBirth.length)
        assertEquals("850320", info.dateOfBirth)

        assertEquals("有效期应为 6 位", 6, info.dateOfExpiry.length)
        assertEquals("290117", info.dateOfExpiry)

        assertEquals("CHN", info.nationality)
        assertEquals("F", info.sex)
        assertEquals("ZHENGJIAN", info.surname)
        assertEquals("YANGBEN", info.givenNames)
    }

    @Test
    fun `护照样本的全部校验位应通过`() {
        val info = MrzParser.parse(listOf(passportLine1, passportLine2))!!
        assertTrue("校验位应全部有效，备注：${info.notes}", info.allCheckDigitsValid)
    }

    @Test
    fun `护照样本的 BAC 口令为 24 字符`() {
        val info = MrzParser.parse(listOf(passportLine1, passportLine2))!!
        assertEquals(24, info.mrzInformation.length)
        assertEquals("EF12608921" + "8503208" + "2901178", info.mrzInformation)
    }

    // -------------------------------------------------------- 通行证（TD1）

    @Test
    fun `通行证样本的行长度符合 TD1 规格`() {
        assertEquals(30, permitLine1.length)
        assertEquals(30, permitLine2.length)
        assertEquals(30, permitLine3.length)
        assertEquals(
            MrzFormat.TD1,
            MrzFormat.detect(listOf(permitLine1, permitLine2, permitLine3))
        )
    }

    @Test
    fun `通行证样本的字段位数正确`() {
        val info = MrzParser.parse(listOf(permitLine1, permitLine2, permitLine3))
        assertNotNull(info)
        info!!

        assertEquals("证件号应为 9 位", 9, info.documentNumber.length)
        assertEquals("CA3273201", info.documentNumber)

        assertEquals("出生日期应为 6 位", 6, info.dateOfBirth.length)
        assertEquals("810803", info.dateOfBirth)

        assertEquals("有效期应为 6 位", 6, info.dateOfExpiry.length)
        assertEquals("290117", info.dateOfExpiry)

        assertEquals("CHN", info.nationality)
        assertEquals("F", info.sex)
        assertEquals("ZHENGJIAN", info.surname)
        assertEquals("YANGBEN", info.givenNames)
    }

    @Test
    fun `通行证样本的全部校验位应通过`() {
        val info = MrzParser.parse(listOf(permitLine1, permitLine2, permitLine3))!!
        assertTrue("校验位应全部有效，备注：${info.notes}", info.allCheckDigitsValid)
    }

    @Test
    fun `通行证样本的证件号含字母且未被改成数字`() {
        val info = MrzParser.parse(listOf(permitLine1, permitLine2, permitLine3))!!
        assertTrue("证件号首字符应为字母 C", info.documentNumber[0].isLetter())
        assertTrue("证件号第 2 位应为字母 A", info.documentNumber[1].isLetter())
    }

    // ------------------------------------------------------ 位数不变量

    @Test
    fun `两种格式的证件号与日期位数一致`() {
        val cases = listOf(
            "TD1" to MrzParser.parse(listOf(permitLine1, permitLine2, permitLine3)),
            "TD3" to MrzParser.parse(listOf(passportLine1, passportLine2))
        )
        for ((name, info) in cases) {
            assertNotNull("$name 应解析成功", info)
            assertEquals("$name 证件号应为 9 位", 9, info!!.documentNumber.length)
            assertEquals("$name 出生日期应为 6 位", 6, info.dateOfBirth.length)
            assertEquals("$name 有效期应为 6 位", 6, info.dateOfExpiry.length)
            assertEquals("$name BAC 口令应为 24 字符", 24, info.mrzInformation.length)
        }
    }

    @Test
    fun `构造的 TD3 行长度正确`() {
        assertEquals(44, td3Line2("EF1260892").length)
        assertEquals(44, td3Line1.length)
    }

    // -------------------------------------------------- 字母数字纠错回归

    @Test
    fun `单字符纠错只应改动一个字符且保留其余字母`() {
        // 证件号 AB1234567 校验位不匹配时：
        //   旧实现先把整串做「字母→数字」规范化（AB1234567 → 481234567），
        //   一次性毁掉两个字母，再用只有数字的字符表纠错，结果面目全非；
        //   新实现直接在包含字母的完整 MRZ 字符集内做单字符纠错。
        val docNo = "AB1234567"
        val trickCd = MrzParser.checkDigit("481234567") // 让旧实现恰好命中的校验位
        val line2 = td3Line2(docNo, trickCd)

        val info = MrzParser.parse(listOf(td3Line1, line2))
        assertNotNull(info)
        info!!

        assertEquals("证件号长度不应改变", 9, info.documentNumber.length)
        val changed = docNo.indices.count { docNo[it] != info.documentNumber[it] }
        assertEquals(
            "单字符纠错只应改动一个字符，实际 $docNo → ${info.documentNumber}",
            1, changed
        )
        assertTrue(
            "字母 B 应被保留，实际 ${info.documentNumber}",
            info.documentNumber.contains('B')
        )
        assertEquals(
            "纠错结果必须满足校验位",
            trickCd,
            MrzParser.checkDigit(info.documentNumber)
        )
    }

    @Test
    fun `证件号含字母时纠错结果仍满足校验位`() {
        val docNo = "EF1260892"
        val wrongCd = '2'
        val info = MrzParser.parse(listOf(td3Line1, td3Line2(docNo, wrongCd)))!!

        assertEquals("证件号长度不应改变", 9, info.documentNumber.length)
        assertEquals(
            "纠错结果必须满足校验位",
            wrongCd,
            MrzParser.checkDigit(info.documentNumber)
        )
    }

    @Test
    fun `校验位有效时证件号原样保留不被规范化`() {
        val docNo = "EE1260892"
        val info = MrzParser.parse(listOf(td3Line1, td3Line2(docNo)))!!
        assertEquals("证件号必须原样保留", docNo, info.documentNumber)
        assertTrue("首字符应仍是字母", info.documentNumber[0].isLetter())
    }

    @Test
    fun `纠错不得把证件号里的字母替换成数字`() {
        val docNo = "AB1234567"
        val info = MrzParser.parse(listOf(td3Line1, td3Line2(docNo)))!!
        assertEquals("前两位必须保持字母", "AB", info.documentNumber.substring(0, 2))
        assertFalse("不应变成纯数字", info.documentNumber.all { it.isDigit() })
    }

    @Test
    fun `全字母证件号不会被破坏`() {
        val docNo = "ABCDEFGHI"
        val info = MrzParser.parse(listOf(td3Line1, td3Line2(docNo)))!!
        assertEquals("全字母证件号应原样保留", docNo, info.documentNumber)
    }

    // ------------------------------------------------ 输入形态的容错

    @Test
    fun `首尾多余空格不应导致解析失败`() {
        // 空格会被 sanitizeLine 映射成填充符 `<`，若不去掉首尾空白，
        // 行长度会多出 1 位（30 → 31），格式检测直接失败。
        val padded = listOf(
            "  $permitLine1  ",
            "\t$permitLine2",
            "$permitLine3 "
        )
        val info = MrzParser.parseFromLines(padded)
        assertNotNull("首尾空格不应影响解析", info)
        assertEquals("CA3273201", info!!.documentNumber)
        assertEquals(9, info.documentNumber.length)
    }

    @Test
    fun `整段粘贴含换行符时应能自动拆行`() {
        val pasted = "$permitLine1\n$permitLine2\n$permitLine3"
        val info = MrzParser.parseFromLines(listOf(pasted))
        assertNotNull("整段粘贴应能自动拆行", info)
        assertEquals(MrzFormat.TD1, info!!.format)
        assertEquals("CA3273201", info.documentNumber)
        assertEquals("810803", info.dateOfBirth)
        assertEquals("290117", info.dateOfExpiry)
    }

    @Test
    fun `护照整段粘贴含换行符时应能自动拆行`() {
        val pasted = "$passportLine1\n$passportLine2"
        val info = MrzParser.parseFromLines(listOf(pasted))
        assertNotNull(info)
        assertEquals(MrzFormat.TD3, info!!.format)
        assertEquals("EF1260892", info.documentNumber)
    }

    @Test
    fun `OCR 把两行护照识别成一整行时应能按 44 切开`() {
        val merged = passportLine1 + passportLine2 // 88 字符
        assertEquals(88, merged.length)

        val info = MrzParser.parseFromLines(listOf(merged))
        assertNotNull("88 字符应能按 2×44 拆开", info)
        assertEquals(MrzFormat.TD3, info!!.format)
        assertEquals("EF1260892", info.documentNumber)
        assertEquals("850320", info.dateOfBirth)
    }

    @Test
    fun `OCR 把三行通行证识别成一整行时应能按 30 切开`() {
        val merged = permitLine1 + permitLine2 + permitLine3 // 90 字符
        assertEquals(90, merged.length)

        val info = MrzParser.parseFromLines(listOf(merged))
        assertNotNull("90 字符应能按 3×30 拆开", info)
        assertEquals(MrzFormat.TD1, info!!.format)
        assertEquals("CA3273201", info.documentNumber)
        assertEquals("810803", info.dateOfBirth)
    }

    @Test
    fun `单行 44 字符不应被误拆`() {
        // 44 恰好是 TD3 行长，但只有一行，不应被当成 2×44 拆开
        val lines = MrzParser.extractMrzLines(listOf(passportLine1))
        assertTrue("单行 44 字符不应被拆分", lines == null || lines.size < 2)
    }

    @Test
    fun `空白行与噪声行应被忽略`() {
        val noisy = listOf("", "   ", "###", permitLine1, permitLine2, permitLine3, "END")
        val info = MrzParser.parseFromLines(noisy)
        assertNotNull("应能从噪声行中挑出 MRZ", info)
        assertEquals("CA3273201", info!!.documentNumber)
    }

    // ------------------------------------------------ 三要素直接构造

    @Test
    fun `三要素构造出的 BAC 口令与真实护照 MRZ 完全一致`() {
        // 这是三要素模式的核心保证：用户只填三个字段，
        // 自动算出的校验位必须与证件上印刷的 MRZ 完全一致，否则 BAC 必然失败。
        val fromRealMrz = MrzParser.parse(listOf(passportLine1, passportLine2))!!
        val fromFields = MrzParser.fromThreeElements("EF1260892", "850320", "290117")

        assertNotNull("三要素应能构造成功", fromFields)
        assertEquals(
            "BAC 口令必须与真实 MRZ 一致",
            fromRealMrz.mrzInformation, fromFields!!.mrzInformation
        )
        assertEquals("EF1260892185032082901178", fromFields.mrzInformation)
        assertEquals(24, fromFields.mrzInformation.length)
    }

    @Test
    fun `三要素构造出的 BAC 口令与真实通行证 MRZ 完全一致`() {
        val fromRealMrz = MrzParser.parse(listOf(permitLine1, permitLine2, permitLine3))!!
        val fromFields = MrzParser.fromThreeElements("CA3273201", "810803", "290117")

        assertNotNull(fromFields)
        assertEquals(
            "BAC 口令必须与真实 MRZ 一致",
            fromRealMrz.mrzInformation, fromFields!!.mrzInformation
        )
        assertEquals(24, fromFields.mrzInformation.length)
    }

    @Test
    fun `三要素模式的校验位由字段自动算出`() {
        val info = MrzParser.fromThreeElements("EF1260892", "850320", "290117")!!
        assertEquals('1', info.documentNumberCheckDigit)
        assertEquals('8', info.dateOfBirthCheckDigit)
        assertEquals('8', info.dateOfExpiryCheckDigit)
        assertTrue("自算校验位必然自洽", info.allCheckDigitsValid)
        assertEquals(MrzFormat.MANUAL, info.format)
    }

    @Test
    fun `三要素模式接受 8 位日期并自动取后 6 位`() {
        val a = MrzParser.fromThreeElements("EF1260892", "850320", "290117")!!
        val b = MrzParser.fromThreeElements("EF1260892", "19850320", "20290117")!!

        assertEquals("6 位与 8 位输入应得到相同结果", a.mrzInformation, b.mrzInformation)
        assertEquals("850320", b.dateOfBirth)
        assertEquals("290117", b.dateOfExpiry)
    }

    @Test
    fun `三要素模式自动补足 9 位证件号`() {
        val info = MrzParser.fromThreeElements("E1234", "850320", "290117")
        assertNotNull(info)
        assertEquals(9, info!!.documentNumber.length)
        assertEquals("E1234<<<<", info.documentNumber)
    }

    @Test
    fun `三要素模式容忍空格与大小写`() {
        val info = MrzParser.fromThreeElements(" ef1260892 ", " 850320 ", " 290117 ")
        assertNotNull(info)
        assertEquals("EF1260892", info!!.documentNumber)
    }

    @Test
    fun `三要素模式的日期会做合法性检查`() {
        assertNull("月份 13 非法", MrzParser.fromThreeElements("EF1260892", "851320", "290117"))
        assertNull("日期 32 非法", MrzParser.fromThreeElements("EF1260892", "850332", "290117"))
        assertNull("位数不足非法", MrzParser.fromThreeElements("EF1260892", "8503", "290117"))
        assertNull("含字母非法", MrzParser.fromThreeElements("EF1260892", "85O320", "290117"))
    }

    @Test
    fun `三要素模式的证件号会做合法性检查`() {
        assertNull("空证件号非法", MrzParser.fromThreeElements("", "850320", "290117"))
        assertNull("超过 9 位非法", MrzParser.fromThreeElements("EF12608921", "850320", "290117"))
        assertNull("只含符号非法", MrzParser.fromThreeElements("---", "850320", "290117"))
    }

    @Test
    fun `三要素模式可被 NfcReadActivity 正确还原`() {
        // 模拟 NfcReadActivity 的还原路径：拿三个字段重新构造，结果必须一致
        val original = MrzParser.fromThreeElements("CA3273201", "810803", "290117")!!
        val restored = MrzParser.fromThreeElements(
            original.documentNumber, original.dateOfBirth, original.dateOfExpiry
        )!!
        assertEquals(original.mrzInformation, restored.mrzInformation)
        assertEquals("手动输入（未识别证件类型）", restored.documentTypeLabel)
    }
}
