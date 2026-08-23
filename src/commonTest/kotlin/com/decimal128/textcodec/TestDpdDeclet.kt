package com.decimal128.textcodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exhaustive tests for the DPD declet primitives.
 *
 * A declet is 10 bits, so the entire input domain is 1024 values for decode
 * and 1000 for encode. These tests enumerate both completely: there is no
 * sampling and no oracle needed beyond the format's own definition.
 */
class TestDpdDeclet {

    @Test
    fun everyDecletDecodesInRange() {
        for (declet in 0L..1023L) {
            val v = Decimal128TextCodec.dpdDecodeDeclet(declet)
            assertTrue(v in 0L..999L, "declet $declet decoded to $v, out of 0..999")
        }
    }

    @Test
    fun everyValueEncodesToTenBits() {
        for (n in 0L..999L) {
            val d = Decimal128TextCodec.dpdEncodeDeclet(n)
            assertTrue(d in 0L..1023L, "value $n encoded to $d, wider than 10 bits")
        }
    }

    /** encode is a right inverse of decode over the whole value domain. */
    @Test
    fun encodeThenDecodeIsIdentity() {
        for (n in 0L..999L)
            assertEquals(n, Decimal128TextCodec.dpdDecodeDeclet(Decimal128TextCodec.dpdEncodeDeclet(n)))
    }

    /**
     * Every value 0..999 is reachable, and exactly 1000 of the 1024 declets
     * are canonical. The other 24 are the non-canonical encodings: they decode
     * to a legal value, but re-encoding yields the canonical declet instead.
     */
    @Test
    fun canonicalAndNonCanonicalDecletsAreAccountedFor() {
        val seen = HashSet<Long>()
        var canonical = 0
        var nonCanonical = 0
        for (declet in 0L..1023L) {
            val v = Decimal128TextCodec.dpdDecodeDeclet(declet)
            seen.add(v)
            if (Decimal128TextCodec.dpdEncodeDeclet(v) == declet) canonical++ else nonCanonical++
        }
        assertEquals(1000, seen.size, "not every value in 0..999 is reachable")
        assertEquals(1000, canonical)
        assertEquals(24, nonCanonical)
    }

    /** Zero must round-trip as the all-zero declet, not merely as some encoding of 0. */
    @Test
    fun zeroIsTheAllZeroDeclet() {
        assertEquals(0L, Decimal128TextCodec.dpdEncodeDeclet(0L))
        assertEquals(0L, Decimal128TextCodec.dpdDecodeDeclet(0L))
    }

    /**
     * Regression pins on specific encodings.
     *
     * `9` and `999` were derived by hand from Cowlishaw's bcd2dpd equations to
     * confirm the rest: for 999 all three digits are 1001, giving
     * `pqrstuvwxy = 0011111111` = 0x0FF; for 9 only `v` and `y` are set,
     * giving 0x009. The remainder were computed and are here to catch drift,
     * not to independently prove the equations -- the exhaustive property
     * tests above do that.
     */
    @Test
    fun knownDecletEncodings() {
        assertEquals(0x009L, Decimal128TextCodec.dpdEncodeDeclet(9L))     // hand-derived
        assertEquals(0x0FFL, Decimal128TextCodec.dpdEncodeDeclet(999L))   // hand-derived
        assertEquals(0x005L, Decimal128TextCodec.dpdEncodeDeclet(5L))
        assertEquals(0x008L, Decimal128TextCodec.dpdEncodeDeclet(8L))
        assertEquals(0x057L, Decimal128TextCodec.dpdEncodeDeclet(57L))
        assertEquals(0x05EL, Decimal128TextCodec.dpdEncodeDeclet(98L))
        assertEquals(0x0DEL, Decimal128TextCodec.dpdEncodeDeclet(198L))
        assertEquals(0x1A1L, Decimal128TextCodec.dpdEncodeDeclet(321L))
        assertEquals(0x1DFL, Decimal128TextCodec.dpdEncodeDeclet(399L))
    }
}
