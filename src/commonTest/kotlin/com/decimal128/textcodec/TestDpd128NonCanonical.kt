package com.decimal128.textcodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Non-canonical DPD128 encodings.
 *
 * DPD's non-canonicality is narrower than BID's, and structurally so:
 *
 *  - The combination field cannot encode an out-of-range exponent. In both
 *    cases the exponent's top two bits come from a field restricted to
 *    `00`, `01` or `10`, so the biased exponent cannot exceed 12287, which is
 *    exactly the format maximum of qExp 6111.
 *  - The coefficient cannot exceed 10^34 - 1: a leading digit of at most 9
 *    followed by eleven declets of at most 999 reaches exactly that bound.
 *
 * So unlike BID there is no coefficient-too-large rule to apply. What remains
 * is the 24 non-canonical declets -- the "don't care" patterns, all decoding
 * to values whose last two digits are both 8 or 9 -- and junk bits in the
 * trailing field of an infinity.
 */
class TestDpd128NonCanonical {

    /** The 24 declet patterns that are not produced by canonical encoding. */
    private val nonCanonicalDeclets = longArrayOf(
        0x16E, 0x16F, 0x17E, 0x17F, 0x1EE, 0x1EF, 0x1FE, 0x1FF,
        0x26E, 0x26F, 0x27E, 0x27F, 0x2EE, 0x2EF, 0x2FE, 0x2FF,
        0x36E, 0x36F, 0x37E, 0x37F, 0x3EE, 0x3EF, 0x3FE, 0x3FF,
    )

    // qExp 0, leading digit 0, positive
    private val base = 0x2208000000000000L

    @Test
    fun theTwentyFourAreTrulyNonCanonical() {
        for (d in nonCanonicalDeclets) {
            val v = Decimal128TextCodec.dpdDecodeDeclet(d)
            assertNotEquals(d, Decimal128TextCodec.dpdEncodeDeclet(v),
                "0x${d.toString(16)} should not be the canonical form of $v")
            assertTrue(v % 10L >= 8L && (v / 10L) % 10L >= 8L,
                "every non-canonical declet decodes to a value ending in two digits >= 8; $v does not")
        }
    }

    /** A non-canonical declet renders exactly as its canonical spelling does. */
    @Test
    fun nonCanonicalRendersAsCanonical() {
        for (d in nonCanonicalDeclets) {
            val canon = Decimal128TextCodec.dpdEncodeDeclet(Decimal128TextCodec.dpdDecodeDeclet(d))
            // low group, declet 0
            assertEquals(
                Decimal128TextCodec.dpd128ToString(base, canon),
                Decimal128TextCodec.dpd128ToString(base, d),
                "low-group declet 0x${d.toString(16)}")
            // high group, declet 1 -- T begins at bit 4 of the high group, so
            // declet i starts at dpd128Hi bit (i*10 - 4)
            assertEquals(
                Decimal128TextCodec.dpd128ToString(base or (canon shl 6), 0L),
                Decimal128TextCodec.dpd128ToString(base or (d shl 6), 0L),
                "high-group declet 0x${d.toString(16)}")
        }
    }

    /** Re-parsing a non-canonical encoding yields the canonical one. */
    @Test
    fun reparsingNormalises() {
        for (d in nonCanonicalDeclets) {
            val canon = Decimal128TextCodec.dpdEncodeDeclet(Decimal128TextCodec.dpdDecodeDeclet(d))
            val text = Decimal128TextCodec.dpd128ToString(base, d)
            val out = LongArray(2)
            assertNull(Decimal128TextCodec.dpd128Parse(out, text))
            assertEquals(base, out[0])
            assertEquals(canon, out[1], "0x${d.toString(16)} should re-encode as 0x${canon.toString(16)}")
        }
    }

    /** Canonical output never emits a non-canonical declet. */
    @Test
    fun encodingNeverProducesNonCanonicalDeclets() {
        val nc = nonCanonicalDeclets.toHashSet()
        for (text in arrayOf(
            "0", "1", "9", "88", "99", "888", "899", "988", "999",
            "9999999999999999999999999999999999",
            "8888888888888888888888888888888888",
            "9898989898989898989898989898989898",
            "1.888888888888888888888888888888888E+99",
            "NaN999", "NaN888999888999888999888999888999", "-sNaN999999999999999999999999999999999",
        )) {
            val out = LongArray(2)
            assertNull(Decimal128TextCodec.dpd128Parse(out, text), "parse failed for $text")
            for (d in decletsOf(out[0], out[1]))
                assertTrue(d !in nc, "encoding of $text emitted non-canonical declet 0x${d.toString(16)}")
        }
    }

    /** Infinity ignores whatever sits in the trailing field. */
    @Test
    fun infinityIgnoresTrailingBits() {
        for (lo in longArrayOf(0L, 1L, 0x3FFL, -1L, 0x1FFL)) {
            assertEquals("Infinity", Decimal128TextCodec.dpd128ToString(0x7800000000000000L, lo))
            assertEquals("-Infinity", Decimal128TextCodec.dpd128ToString(-0x0800000000000000L, lo))
        }
        // and junk in the high group too
        assertEquals("Infinity", Decimal128TextCodec.dpd128ToString(0x7800FFFFFFFFFFFFL, -1L))
    }

    /** A NaN payload is a coefficient, so its declets normalise the same way. */
    @Test
    fun nanPayloadDecletsNormalise() {
        for (d in nonCanonicalDeclets) {
            val canon = Decimal128TextCodec.dpdEncodeDeclet(Decimal128TextCodec.dpdDecodeDeclet(d))
            assertEquals(
                Decimal128TextCodec.dpd128ToString(0x7C00000000000000L, canon),
                Decimal128TextCodec.dpd128ToString(0x7C00000000000000L, d),
                "NaN payload declet 0x${d.toString(16)}")
        }
    }

    /** All eleven declets of the trailing significand field. */
    private fun decletsOf(hi: Long, lo: Long): LongArray {
        val out = LongArray(11)
        for (i in 0 until 6) out[i] = (lo ushr (i * 10)) and 0x3FFL
        val high = ((hi and 0x3FFFFFFFFFFFL) shl 4) or (lo ushr 60)
        for (i in 0 until 5) out[6 + i] = (high ushr (i * 10)) and 0x3FFL
        return out
    }
}
