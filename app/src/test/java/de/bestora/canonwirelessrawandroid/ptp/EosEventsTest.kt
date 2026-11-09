package de.bestora.canonwirelessrawandroid.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class EosEventsTest {
    private fun records(vararg r: Pair<Int, IntArray>): ByteArray {
        val size = r.sumOf { 8 + 4 * it.second.size } + 8
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        r.forEach { (type, data) ->
            buf.putInt(8 + 4 * data.size).putInt(type)
            data.forEach(buf::putInt)
        }
        return buf.putInt(8).putInt(0).array()
    }

    @Test
    fun `picks object-added handles out of mixed events`() {
        val events = records(
            0xC189 to intArrayOf(0xD101, 5), // property changed
            0xC181 to intArrayOf(0x1234, 0x10001, 0xB108),
            0xC1A7 to intArrayOf(0x1235, 0x10001),
        )
        assertEquals(listOf(0x1234, 0x1235), addedObjectHandles(events))
    }

    @Test
    fun `stops at the terminator and at a truncated record`() {
        assertEquals(emptyList<Int>(), addedObjectHandles(records()))
        val truncated = records(0xC181 to intArrayOf(7)).copyOf(10)
        assertEquals(emptyList<Int>(), addedObjectHandles(truncated))
    }
}
