package io.github.gaming32.worldhostserver.test

import io.github.gaming32.worldhostserver.util.U128ToIntRangeMap
import io.github.gaming32.worldhostserver.util.U32ToIntRangeMap
import kotlin.test.Test
import kotlin.test.assertEquals

class TestRangeMaps {
    @Test
    fun testU32() {
        val map = U32ToIntRangeMap().apply {
            put(5, 10, 8)
            put(15, 20, 32)
            trimToSize()
        }
        for (shouldBeNull in 0..4) {
            assertEquals(null, map.get(shouldBeNull))
        }
        for (shouldBe8 in 5..10) {
            assertEquals(8, map.get(shouldBe8))
        }
        for (shouldBeNull in 11..14) {
            assertEquals(null, map.get(shouldBeNull))
        }
        for (shouldBe32 in 15..20) {
            assertEquals(32, map.get(shouldBe32))
        }
        for (shouldBeNull in 21..25) {
            assertEquals(null, map.get(shouldBeNull))
        }
    }

    @Test
    fun testU128() {
        val map = U128ToIntRangeMap().apply {
            put(5.toBigInteger(), 10.toBigInteger(), 8)
            put(15.toBigInteger(), 20.toBigInteger(), 32)
            trimToSize()
        }
        for (shouldBeNull in 0..4) {
            assertEquals(null, map.get(shouldBeNull.toBigInteger()))
        }
        for (shouldBe8 in 5..10) {
            assertEquals(8, map.get(shouldBe8.toBigInteger()))
        }
        for (shouldBeNull in 11..14) {
            assertEquals(null, map.get(shouldBeNull.toBigInteger()))
        }
        for (shouldBe32 in 15..20) {
            assertEquals(32, map.get(shouldBe32.toBigInteger()))
        }
        for (shouldBeNull in 21..25) {
            assertEquals(null, map.get(shouldBeNull.toBigInteger()))
        }
    }
}
