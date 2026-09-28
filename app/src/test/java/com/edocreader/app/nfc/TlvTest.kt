package com.edocreader.app.nfc

import com.edocreader.app.util.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BER-TLV 解析器测试。
 *
 * 重点覆盖 `rawStart` / `rawLength` 的语义：它们必须始终相对于**原始缓冲区**，
 * 无论节点嵌套多深。这是调用方（如从 DG15 中切出 SubjectPublicKeyInfo）
 * 能正确取回节点完整字节的前提。
 */
class TlvTest {

    /** 把节点在原始缓冲区中对应的完整 TLV 字节切出来。 */
    private fun rawOf(buffer: ByteArray, node: TlvNode): ByteArray =
        buffer.copyOfRange(node.rawStart, node.rawStart + node.rawLength)

    @Test
    fun `顶层节点的 rawStart 指向缓冲区起点`() {
        // 两个完整 TLV：01 01 AA 与 04 02 BB CC
        val buf = byteArrayOf(0x01, 0x01, 0xAA.toByte(), 0x04, 0x02, 0xBB.toByte(), 0xCC.toByte())
        val nodes = Tlv.parse(buf)
        assertEquals("应解析出两个顶层节点", 2, nodes.size)
        assertEquals(0, nodes[0].rawStart)
        assertEquals(3, nodes[0].rawLength)
        assertEquals("第二个节点应从偏移 3 开始", 3, nodes[1].rawStart)
        assertEquals(4, nodes[1].rawLength)
        assertArrayEquals(
            byteArrayOf(0x04, 0x02, 0xBB.toByte(), 0xCC.toByte()),
            rawOf(buf, nodes[1])
        )
    }

    @Test
    fun `嵌套节点的 rawStart 仍相对原始缓冲区`() {
        // 构造 30 06 { 30 04 { 04 02 AB CD } }
        val buf = byteArrayOf(
            0x30, 0x06,
            0x30, 0x04,
            0x04, 0x02, 0xAB.toByte(), 0xCD.toByte()
        )
        val outer = Tlv.parse(buf).first()
        assertNotNull(outer)
        assertEquals(0, outer!!.rawStart)
        assertEquals(buf.size, outer.rawLength)

        val middle = outer.child(0x30)
        assertNotNull("应能取到中间层节点", middle)
        assertEquals("中间层 rawStart 应为 2", 2, middle!!.rawStart)
        assertEquals("中间层 rawLength 应为 6", 6, middle.rawLength)
        assertArrayEquals(
            "切出的字节应与原始一致",
            byteArrayOf(0x30, 0x04, 0x04, 0x02, 0xAB.toByte(), 0xCD.toByte()),
            rawOf(buf, middle)
        )

        val inner = middle.child(0x04)
        assertNotNull(inner)
        assertEquals("最内层 rawStart 应为 4", 4, inner!!.rawStart)
        assertArrayEquals(byteArrayOf(0x04, 0x02, 0xAB.toByte(), 0xCD.toByte()), rawOf(buf, inner))
    }

    @Test
    fun `多层嵌套下每一层都能切回自身字节`() {
        // 用真实形态：6F { 30 { 30 { 06 }, 03 } }
        val buf = byteArrayOf(
            0x6F, 0x0D,
            0x30, 0x0B,
            0x30, 0x05, 0x06, 0x03, 0x2A, 0x03, 0x04,
            0x03, 0x02, 0x00, 0x11
        )
        val root = Tlv.parse(buf).first()!!
        assertEquals(0, root.rawStart)
        assertEquals(buf.size, root.rawLength)

        // 逐层验证：切出的字节必须能被再次解析出相同结构
        for (node in root.flatten()) {
            val raw = rawOf(buf, node)
            val reparsed = Tlv.parse(raw).firstOrNull()
            assertNotNull("切出的字节应可重新解析：${node.tagHex}", reparsed)
            assertEquals("tag 应一致", node.tag, reparsed!!.tag)
            assertEquals("value 应一致", Hex.encode(node.value), Hex.encode(reparsed.value))
        }
    }

    @Test
    fun `长形式长度下 rawStart 与 rawLength 正确`() {
        // 长度 0x82 形式：30 82 01 00 ... 共 256 字节内容
        val content = ByteArray(256) { it.toByte() }
        val buf = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00) + content
        val node = Tlv.parse(buf).first()!!
        assertEquals(0, node.rawStart)
        assertEquals(4 + 256, node.rawLength)
        assertArrayEquals(buf, rawOf(buf, node))
    }

    @Test
    fun `多字节 tag 解析正确`() {
        // 5F 1F <len> value
        val buf = byteArrayOf(0x5F, 0x1F, 0x03) + "ABC".toByteArray()
        val node = Tlv.parse(buf).first()!!
        assertEquals(0x5F1F, node.tag)
        assertEquals("ABC", String(node.value))
        assertArrayEquals(buf, rawOf(buf, node))
    }

    @Test
    fun `子节点解析不越过父节点边界`() {
        // 父节点声明长度只覆盖前两个字节，后面还有一个独立顶层节点
        val buf = byteArrayOf(
            0x30, 0x02, 0x04, 0x00,
            0x04, 0x01, 0x5A
        )
        val nodes = Tlv.parse(buf)
        assertEquals("应解析出两个顶层节点", 2, nodes.size)
        assertEquals("父节点不应吞掉后面的节点", 1, nodes[0].children.size)
        assertEquals(0x5A, nodes[1].value[0].toInt())
    }

    @Test
    fun `不定长与截断数据不会导致异常`() {
        assertTrue(Tlv.parse(byteArrayOf(0x30, 0x80.toByte())).isEmpty())
        assertTrue(Tlv.parse(byteArrayOf(0x30, 0x05, 0x01)).isEmpty())
        assertTrue(Tlv.parse(ByteArray(0)).isEmpty())
        assertNull(Tlv.parseOne(byteArrayOf(0x30), 0, 1))
    }

    @Test
    fun `encode 与 parse 互为逆运算`() {
        val value = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = Tlv.encode(0x87, value)
        val node = Tlv.parse(encoded).first()!!
        assertEquals(0x87, node.tag)
        assertArrayEquals(value, node.value)
        assertArrayEquals(encoded, rawOf(encoded, node))
    }
}
