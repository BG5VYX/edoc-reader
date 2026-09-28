package com.edocreader.app.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MRZ 解析与校验位测试。
 *
 * 校验位期望值取自 ICAO Doc 9303 Part 3 的加权算法（因子 7-3-1），
 * 样例文档的其余字段按同一算法自行构造并保证自洽。
 */
class MrzParserTest {

    // ------------------------------------------------------------ 校验位

    @Test
    fun `校验位计算符合 ICAO 9303 加权算法`() {
        // ICAO 9303 官方工作样例中的证件号与其校验位
        assertEquals('3', MrzParser.checkDigit("L898902C<"))
        assertEquals('1', MrzParser.checkDigit("690806"))
        assertEquals('6', MrzParser.checkDigit("940623"))
        // 自洽样例
        assertEquals('2', MrzParser.checkDigit("E12345678"))
        assertEquals('4', MrzParser.checkDigit("800101"))
        assertEquals('9', MrzParser.checkDigit("300101"))
        assertEquals('8', MrzParser.checkDigit("12345678<"))
        assertEquals('7', MrzParser.checkDigit("D23145890"))
        assertEquals('0', MrzParser.checkDigit("<<<<<<<<<<<<<<"))
    }

    // --------------------------------------------------------------- TD3

    private val td3Line1 = "P<CHNZHANG<<SAN<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
    private val td3Line2 = "E123456782CHN8001014M3001019<<<<<<<<<<<<<<08"

    @Test
    fun `TD3 样例长度正确`() {
        assertEquals(44, td3Line1.length)
        assertEquals(44, td3Line2.length)
    }

    @Test
    fun `TD3 护照解析正确`() {
        val info = MrzParser.parse(listOf(td3Line1, td3Line2))
        assertNotNull(info)
        info!!
        assertEquals(MrzFormat.TD3, info.format)
        assertEquals("P<", info.documentCode)
        assertEquals("CHN", info.issuingState)
        assertEquals("E12345678", info.documentNumber)
        assertEquals('2', info.documentNumberCheckDigit)
        assertEquals("CHN", info.nationality)
        assertEquals("800101", info.dateOfBirth)
        assertEquals("300101", info.dateOfExpiry)
        assertEquals("M", info.sex)
        assertEquals("ZHANG", info.surname)
        assertEquals("SAN", info.givenNames)
        assertEquals("ZHANG SAN", info.fullNameEnglish)
        assertTrue("校验位应全部通过，实际备注：${info.notes}", info.allCheckDigitsValid)
    }

    @Test
    fun `TD3 的 MRZi 长度必须为 24 且内容正确`() {
        val info = MrzParser.parse(listOf(td3Line1, td3Line2))!!
        assertEquals("E12345678280010143001019", info.mrzInformation)
        assertEquals(24, info.mrzInformation.length)
    }

    @Test
    fun `TD3 日期换算为 ISO 格式`() {
        val info = MrzParser.parse(listOf(td3Line1, td3Line2))!!
        assertEquals("1980-01-01", info.birthDateIso)
        assertEquals("2030-01-01", info.expiryDateIso)
    }

    @Test
    fun `TD3 证件类型标签识别`() {
        val info = MrzParser.parse(listOf(td3Line1, td3Line2))!!
        assertTrue(info.documentTypeLabel.contains("护照"))
        assertEquals("男 / M", info.genderLabel)
    }

    // --------------------------------------------------------------- TD1

    private val td1Line1 = "C<CHN12345678<8<<<<<<<<<<<<<<<"
    private val td1Line2 = "8001014M3001019CHN<<<<<<<<<<<6"
    private val td1Line3 = "ZHANG<<SAN<<<<<<<<<<<<<<<<<<<<"

    @Test
    fun `TD1 样例长度正确`() {
        assertEquals(30, td1Line1.length)
        assertEquals(30, td1Line2.length)
        assertEquals(30, td1Line3.length)
    }

    @Test
    fun `TD1 通行证解析正确`() {
        val info = MrzParser.parse(listOf(td1Line1, td1Line2, td1Line3))
        assertNotNull(info)
        info!!
        assertEquals(MrzFormat.TD1, info.format)
        assertEquals("C<", info.documentCode)
        assertEquals("CHN", info.issuingState)
        assertEquals("12345678<", info.documentNumber)
        assertEquals("800101", info.dateOfBirth)
        assertEquals("300101", info.dateOfExpiry)
        assertEquals("CHN", info.nationality)
        assertEquals("ZHANG", info.surname)
        assertEquals("SAN", info.givenNames)
        assertTrue("TD1 校验位应全部通过，实际备注：${info.notes}", info.allCheckDigitsValid)
        assertEquals(24, info.mrzInformation.length)
    }

    @Test
    fun `TD1 证件类型识别为往来港澳通行证`() {
        val info = MrzParser.parse(listOf(td1Line1, td1Line2, td1Line3))!!
        assertTrue(info.documentTypeLabel.contains("往来港澳通行证"))
    }

    // ------------------------------------------------------------ 行组提取

    @Test
    fun `从混合文本中提取 TD3 行组`() {
        val lines = listOf(
            "中华人民共和国护照",
            "姓名 ZHANG SAN",
            td3Line1,
            td3Line2,
            "签发日期 2020-01-01"
        )
        val info = MrzParser.parseFromLines(lines)
        assertNotNull(info)
        assertEquals(MrzFormat.TD3, info!!.format)
        assertEquals("E12345678", info.documentNumber)
    }

    @Test
    fun `从混合文本中提取 TD1 行组`() {
        val lines = listOf("往来港澳通行证", td1Line1, td1Line2, td1Line3)
        val info = MrzParser.parseFromLines(lines)
        assertNotNull(info)
        assertEquals(MrzFormat.TD1, info!!.format)
    }

    @Test
    fun `无法识别时返回 null`() {
        assertNull(MrzParser.parseFromLines(listOf("HELLO", "WORLD")))
    }

    @Test
    fun `requireValidChecksum 会拒绝校验位不通过的输入`() {
        // 把有效期校验位故意改错
        val broken = td3Line2.substring(0, 27) + "0" + td3Line2.substring(28)
        assertNull(MrzParser.parseFromLines(listOf(td3Line1, broken), requireValidChecksum = true))
        assertNotNull(MrzParser.parseFromLines(listOf(td3Line1, broken), requireValidChecksum = false))
    }

    // -------------------------------------------------------------- 纠错

    @Test
    fun `字母数字混淆可被自动纠错`() {
        // 把出生日期与有效期中的 0 误识成 O
        val broken = "E123456782CHN8OO1O14M3OO1O19<<<<<<<<<<<<<<08"
        assertEquals(44, broken.length)
        val info = MrzParser.parse(listOf(td3Line1, broken))
        assertNotNull(info)
        info!!
        assertEquals("800101", info.dateOfBirth)
        assertEquals("300101", info.dateOfExpiry)
        assertTrue("纠错后校验位应通过", info.allCheckDigitsValid)
        assertTrue(info.repairedFields.isNotEmpty())
    }

    // ------------------------------------------------------------ 其他工具

    @Test
    fun `姓名字段按双尖括号切分`() {
        val (surname, given) = MrzParser.splitNames("ZHANG<<SAN<SI")
        assertEquals("ZHANG", surname)
        assertEquals("SAN SI", given)
    }

    @Test
    fun `无分隔符时整段视为姓`() {
        val (surname, given) = MrzParser.splitNames("ZHANGSAN")
        assertEquals("ZHANGSAN", surname)
        assertEquals("", given)
    }

    @Test
    fun `非法字符会被清洗为尖括号`() {
        val cleaned = MrzParser.sanitizeLine("p<chn zhang...san*")
        assertFalse(cleaned.contains(' '))
        assertFalse(cleaned.contains('.'))
        assertFalse(cleaned.contains('*'))
        assertTrue(cleaned.startsWith("P<CHN"))
    }

    @Test
    fun `行长度偏差会被对齐裁剪`() {
        val lines = listOf(td3Line1 + "<<", td3Line2)
        val info = MrzParser.parseFromLines(lines)
        assertNotNull(info)
        assertEquals("E12345678", info!!.documentNumber)
    }

    @Test
    fun `已过期证件可被识别`() {
        assertTrue(MrzDate.isExpired("200101"))
        assertFalse(MrzDate.isExpired("300101"))
    }
}
