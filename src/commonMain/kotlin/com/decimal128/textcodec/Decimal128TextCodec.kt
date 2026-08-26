// SPDX-License-Identifier: MIT
@file:Suppress("NOTHING_TO_INLINE")

package com.decimal128.textcodec

import kotlin.math.max
import kotlin.math.min

/**
 * Thin codec between the two IEEE 754-2019 decimal128 interchange encodings
 * — BID (Binary Integer Decimal) and DPD (Densely Packed Decimal) — and
 * decimal character sequences.
 *
 * A decimal128 encoding is 128 bits: a sign bit, a combination field and a
 * trailing significand field, laid out per IEEE 754-2019 §3.5.2. It crosses
 * this API as a pair of 64-bit `Long`s, the high 64 bits and the low 64.
 * That is the entire boundary; there is no value type, no context, no
 * status flags.
 *
 * This codec provides no arithmetic, no comparison and no field extraction.
 * For the full IEEE 754-2019 decimal128 implementation — arithmetic,
 * comparison, every rounding-direction attribute, IEEE status flags — see
 * https://decimal128.com.
 *
 * ## Rounding
 *
 * Rounding, where the text → bits direction requires it, is the IEEE
 * 754-2019 `roundTiesToEven` attribute and is not configurable. IEEE
 * 754-2019 §5.4.2 places `convertFromDecimalCharacter` under the applicable
 * rounding-direction attribute; this codec deliberately implements only the
 * ties-to-even case and is therefore a conforming subset of that operation.
 * Callers needing another rounding direction want the full library.
 *
 * Output form is the GDAS `to-scientific-string` form and is not
 * configurable. Cohort information is preserved: `"1.00"` and `"1.0"` are
 * distinct outputs from distinct encodings of the same numerical value.
 *
 * ## Round-trip behavior
 *
 * The renderer's output round-trips exactly: parsing rendered text and
 * rendering it again reproduces that text, and rendering a canonical
 * encoding and parsing the result reproduces the original bits. Three
 * classes of input do not round-trip:
 *
 * - **More than 34 significant digits** are accepted and rounded to 34 with
 *   `roundTiesToEven`. The rounded value is encoded; the input text is not
 *   preserved.
 * - **Magnitudes outside the representable range** overflow to `Infinity`
 *   or underflow to a subnormal or zero, per IEEE 754-2019.
 * - **Non-canonical bit patterns** render as something the encoder would
 *   never have emitted, so re-parsing yields the canonical pattern instead.
 *
 * ## Non-canonical bit patterns
 *
 * Not every 128-bit pattern is canonical. Each encoding has its own
 * non-canonical forms — patterns the encoder would never emit — and what
 * makes a pattern non-canonical is a property of that encoding, documented
 * where that encoding is decoded.
 *
 * Neither renderer can fail on one: every 128-bit pattern renders as the
 * value IEEE 754-2019 assigns it. Decoding is one-way for such a pattern,
 * because the parsers emit only canonical encodings, so re-parsing the
 * rendered text yields bits that differ from the original. Non-canonical
 * patterns never arise from this codec's own parsers; they appear only when
 * encodings come from outside, such as network or file data.
 *
 * ## Thread safety
 *
 * All functions are pure and safe for concurrent use.
 *
 * ## This implementation
 *
 * The renderers return a `String`; their `bid128ToAscii` and
 * `dpd128ToAscii` peers write into a caller-owned `ByteArray` and return a
 * length, for callers who want no allocation. The parsers write the
 * encoding into a caller-supplied `LongArray` and return `null` on success,
 * or a message on failure. The implementation is one file and runs on
 * Kotlin Multiplatform with no external runtime dependencies.
 */
object Decimal128TextCodec {

    // Compile-time flag controlling the verify() state assertions. Its value
    // comes from the generated VerifyFlag.kt, which build.gradle.kts writes
    // from `-Pverify`; the default is OFF.
    // A consumer compiles this module into their own program and must not take
    // a throw inside a library on data they do not control. Because the flag is
    // a const, `if (VERIFY_ENABLED)` folds away entirely when it is off and
    // neither the message nor the predicate survives. CI runs one armed pass
    // over the whole corpus.

    private inline fun verify(block: () -> Boolean) {
        if (VERIFY_ENABLED)
            check(block())
    }

    /**
     * Render a BID128 encoding as a decimal character sequence.
     *
     * The output is the GDAS `to-scientific-string` form: integer notation
     * when the quantum exponent is zero (e.g. `"123"`), plain notation when
     * the adjusted exponent is in the range -6 to 0 (e.g. `"123.45"`),
     * normalized scientific notation otherwise (e.g. `"1.234E+5"`). The
     * exponent sign is always written, as in `"6.02E+23"`. Cohort information
     * is preserved: `"1.00"` and `"1.0"` are distinct outputs from distinct
     * encodings of the same numerical value.
     *
     * Special values render as `"Infinity"`, `"-Infinity"`, `"NaN"` and
     * `"sNaN"`, each with an optional sign and an optional payload suffix such
     * as `"NaN123"`.
     *
     * The two interchange encodings differ only in how the coefficient is
     * stored: BID128 as a binary integer, DPD128 as a leading digit plus
     * eleven 10-bit declets. The sign and the 14-bit biased exponent are the
     * same in both, and peer encodings of the same value render to identical
     * text.
     *
     * This cannot fail. Every 128-bit pattern renders: a non-canonical BID128
     * coefficient (≥ 10^34) renders as zero at the encoded exponent, and a NaN
     * payload ≥ 10^33 renders as no payload, both per IEEE 754-2019 §3.5.2.
     *
     * @param bid128Hi high 64 bits of the BID128 encoding
     * @param bid128Lo low 64 bits of the BID128 encoding
     * @return the decimal character sequence for the BID128 encoding
     */
    fun bid128ToString(bid128Hi: Long, bid128Lo: Long): String {
        val ascii = ByteArray(MAX_LEN)
        val len = bid128ToAscii(ascii, bid128Hi, bid128Lo)
        return ascii.decodeToString(0, len)
    }

    /**
     * Render a DPD128 encoding as a decimal character sequence.
     *
     * The output is the GDAS `to-scientific-string` form: integer notation
     * when the quantum exponent is zero (e.g. `"123"`), plain notation when
     * the adjusted exponent is in the range -6 to 0 (e.g. `"123.45"`),
     * normalized scientific notation otherwise (e.g. `"1.234E+5"`). The
     * exponent sign is always written, as in `"6.02E+23"`. Cohort information
     * is preserved: `"1.00"` and `"1.0"` are distinct outputs from distinct
     * encodings of the same numerical value.
     *
     * Special values render as `"Infinity"`, `"-Infinity"`, `"NaN"` and
     * `"sNaN"`, each with an optional sign and an optional payload suffix such
     * as `"NaN123"`.
     *
     * The two interchange encodings differ only in how the coefficient is
     * stored: BID128 as a binary integer, DPD128 as a leading digit plus
     * eleven 10-bit declets. The sign and the 14-bit biased exponent are the
     * same in both, and peer encodings of the same value render to identical
     * text.
     *
     * This cannot fail. Every 128-bit pattern renders: a DPD128 coefficient is
     * always in range, since a leading digit of at most 9 followed by eleven
     * declets cannot reach 10^34; a NaN payload, carried in those same
     * declets, is likewise always in range; and non-canonical declets decode
     * to their value, per IEEE 754-2019 §3.5.2.
     *
     * @param dpd128Hi high 64 bits of the DPD128 encoding
     * @param dpd128Lo low 64 bits of the DPD128 encoding
     * @return the decimal character sequence for the DPD128 encoding
     */
    fun dpd128ToString(dpd128Hi: Long, dpd128Lo: Long): String {
        val ascii = ByteArray(MAX_LEN)
        val len = dpd128ToAscii(ascii, dpd128Hi, dpd128Lo)
        return ascii.decodeToString(0, len)
    }

    /**
     * The exact bound on a rendered decimal128, in ASCII character bytes.
     *
     * The widest scientific form `-9.999...E-6143` is 1 sign, 1 digit, 1 radix
     * point, 33 digits, 1 `E`, 1 sign and 4 exponent digits; the widest plain
     * form `-0.00000999...` is 1 sign, 1 zero, 1 radix point, 5 leading zeros
     * and 34 digits. Each form reaches exactly 42.
     *
     * The plain form's five leading zeros are pinned by the GDAS rule that
     * selects it only when the adjusted exponent is at least -6, which puts
     * five zeros between the radix point and the first significant digit. The
     * exponent field cannot exceed four digits inside decimal128's range, so
     * neither number can drift without the format itself changing.
     *
     * Every write in the renderers is guarded against this bound. Sizing it
     * exactly rather than generously is what turns those guards into real
     * checks: with slack, an error in the arithmetic above would quietly
     * consume the slack instead of firing.
     *
     * A buffer passed to `bid128ToAscii` must be at least this long. The C
     * port's `D128TEXT_MAX_LEN` is one larger, because it writes a NUL and
     * this does not.
     */
    const val MAX_LEN = 42

    /**
     * Render a BID128 encoding into a caller-supplied buffer, allocating
     * nothing.
     *
     * Writes the ASCII text into `out` starting at index 0 and returns its
     * length; the text is `out[0]` through `out[n - 1]`. No terminator is
     * written. `out` must be at least `MAX_LEN` long; a shorter one throws
     * `IndexOutOfBoundsException` on the first write past its end rather than
     * corrupting anything, so an undersized buffer is a caller bug that
     * surfaces immediately.
     *
     * The text is identical to what `bid128ToString` returns, and this form is
     * the one to reach for when the destination is bytes — a stream, a socket,
     * a CSV or JSON writer — because it leaves nothing for the collector.
     *
     * The two interchange encodings differ only in how the coefficient is
     * stored: BID128 as a binary integer, DPD128 as a leading digit plus
     * eleven 10-bit declets. The sign and the 14-bit biased exponent are the
     * same in both, and peer encodings of the same value render to identical
     * text.
     *
     * @param out the destination, at least `MAX_LEN` bytes
     * @param bid128Hi high 64 bits of the BID128 encoding
     * @param bid128Lo low 64 bits of the BID128 encoding
     * @return the number of bytes written
     */
    fun bid128ToAscii(out: ByteArray, bid128Hi: Long, bid128Lo: Long): Int {
        // IEEE754-2019 Table 3.6-Decimal2 Interchange format parameters -- p 23
        val k = 128 // storage width in bits
        // val p = 34 // precision in digits
        // val emax = 6144
        val bias = 6176
        val w5 = 17 // w+5, combination field width in bits
        val t = 110 // trailing significand field width in bits
        verify { 1 + w5 + t == k }

        // 1 + 17 + 110 == 128
        // 1 bit for the sign
        val sign = bid128Hi < 0L
        // w5 bit combination field ... for bid128 0x1FFFF
        val combination = (bid128Hi shr (t - 64)).toInt() and 0x1FFFF
        val coeffTHi = bid128Hi and ((1L shl t) - 1L)

        val coeffHi: Long
        val qExp: Int
        when {
            // if the top 2 bits are not 0b11
            combination shr (w5 - 2) != 0b11 -> {
                //  IEEE754-2019 3.5.2 c) 2) i) -- p 21
                //   If G0 and G1 together are one of 00, 01, or 10, then the biased
                //   exponent E is formed from G0 through Gw+1 and the significand
                //   is formed from bits Gw+2 through the end of the encoding (including T).
                //
                // w+5=17 w=12 Gw+1=G13 Gw+2=G14
                // the top 14 bits of the combination field represent the biased exponent ...
                val biasedExponentE = combination shr 3
                qExp = biasedExponentE - bias
                // ... and the bottom 3 bits are the most significant bits of the significand
                // coeff/significand is 3 + 110 == 113 ... but that's not all ... see below
                coeffHi = (combination and 0x07).toLong()
            }

            combination shr (w5 - 4) != 0b1111 -> {
                // IEEE754-2019 3.5.2 c) 2) ii) -- p 21
                //  If G0 and G1 together are 11 and G2 and G3 together are one
                //  of 00, 01, or 10, then the biased exponent E is formed from
                //  G2 through Gw+3 and the significand is formed by prefixing
                //  the 4 bits (8 + Gw+4) to T.
                //
                // w+5=17 w=12 Gw+3=G15 Gw+4=G16
                //
                val biasedExponentE = (combination ushr 1) and ((1 shl (w5 - 5 + 2)) - 1)
                qExp = biasedExponentE - bias
                coeffHi = 8L + (combination and 1).toLong()
            }
            // if the top 5 bits are 0b11110 then Infinity
            (combination shr (w5 - 5)) == 0b11110 -> {
                return infBytes(out, sign)
            }
            // if the top 5 bits are 0x11111 then NaN
            (combination shr (w5 - 5)) == 0b11111 -> {
                // with the next bit determining signaling NaN
                val payloadHi = coeffTHi
                val payloadLo = bid128Lo
                val isSignaling = ((combination shr (w5 - 6)) and 1) != 0
                return nanBytes(out, sign, isSignaling, payloadHi, payloadLo)
            }

            else -> {
                // all possible cases were covered above
                throw IllegalStateException()
            }
        }
        val dw1 = ((coeffHi shl (t - 64)) or coeffTHi)
        val dw0 = bid128Lo
        // IEEE754-2019 3.5.2 p21
        //  If the value exceeds the maximum, the significand c is
        //  non-canonical and the value used for c is zero.
        if ((dw1 or dw0) == 0L ||
            unsignedLT(MAXX_COEFF_34_HI, dw1) ||
            dw1 == MAXX_COEFF_34_HI && unsignedLT(MAXX_COEFF_34_LO - 1L, dw0)
        ) {
            return zerBytes(out, sign, qExp)
        }
        return fnzBytes(out, sign, qExp, dw1, dw0)
    }

    /**
     * Render a DPD128 encoding into a caller-supplied buffer, allocating
     * nothing.
     *
     * Writes the ASCII text into `out` starting at index 0 and returns its
     * length; the text is `out[0]` through `out[n - 1]`. No terminator is
     * written. `out` must be at least `MAX_LEN` long; a shorter one throws
     * `IndexOutOfBoundsException` on the first write past its end rather than
     * corrupting anything, so an undersized buffer is a caller bug that
     * surfaces immediately.
     *
     * The text is identical to what `dpd128ToString` returns, and this form is
     * the one to reach for when the destination is bytes — a stream, a socket,
     * a CSV or JSON writer — because it leaves nothing for the collector.
     *
     * The two interchange encodings differ only in how the coefficient is
     * stored: BID128 as a binary integer, DPD128 as a leading digit plus
     * eleven 10-bit declets. The sign and the 14-bit biased exponent are the
     * same in both, and peer encodings of the same value render to identical
     * text.
     *
     * @param out the destination, at least `MAX_LEN` bytes
     * @param dpd128Hi high 64 bits of the DPD128 encoding
     * @param dpd128Lo low 64 bits of the DPD128 encoding
     * @return the number of bytes written
     */
    fun dpd128ToAscii(out: ByteArray, dpd128Hi: Long, dpd128Lo: Long): Int {
        val bias = 6176
        val w = 12  // exponent continuation width
        val w5 = 17 // combination field width in bits
        val t = 110 // trailing significand field width in bits

        val sign = dpd128Hi < 0L
        val combination = (dpd128Hi shr (t - 64)).toInt() and 0x1FFFF
        // in DPD the low w bits of the biased exponent always sit at the bottom of G
        val expContinuation = combination and 0x0FFF

        val d0: Long
        val qExp: Int
        var isNaN = false
        var isSignaling = false

        when {
            // G0G1 is 00, 01 or 10: leading digit 0..7, exponent MSBs in G0G1
            combination shr (w5 - 2) != 0b11 -> {
                val expMSB = combination shr (w5 - 2)
                qExp = ((expMSB shl w) or expContinuation) - bias
                d0 = ((combination shr w) and 0b111).toLong()
            }

            // G0G1 is 11 and G2G3 is not 11: leading digit 8..9, exponent MSBs in G2G3
            combination shr (w5 - 4) != 0b1111 -> {
                val expMSB = (combination shr (w5 - 4)) and 0b11
                qExp = ((expMSB shl w) or expContinuation) - bias
                d0 = 8L + ((combination shr w) and 0b1).toLong()
            }

            (combination shr (w5 - 5)) == 0b11110 -> {
                return infBytes(out, sign)
            }

            else -> {
                verify { (combination shr (w5 - 5)) == 0b11111 }
                isNaN = true
                isSignaling = ((combination shr (w5 - 6)) and 1) != 0
                d0 = 0L
                qExp = 0
            }
        }

        // Rebuild the binary coefficient from the declets. The leading digit
        // rides along as a sixth declet of the high group: values 0..9 encode
        // to themselves as a declet, so it decodes back unchanged.
        val decletsHi = (d0 shl 50) or ((dpd128Hi and 0x3FFFFFFFFFFFL) shl 4) or (dpd128Lo ushr 60)
        val decletsLo = dpd128Lo and 0x0FFFFFFFFFFFFFFFL // low 60 bits, six declets
        val binHi = binFromDeclets(decletsHi)
        val binLo = binFromDeclets(decletsLo)

        val dw1: Long
        val dw0: Long
        if (binHi == 0L) {
            dw1 = 0L
            dw0 = binLo
        } else {
            dw0 = (binHi * TEN_POW_18) + binLo
            val carry = if (unsignedLT(dw0, binLo)) 1L else 0L
            dw1 = unsignedMulHi(binHi, TEN_POW_18) + carry
        }

        if (isNaN)
            return nanBytes(out, sign, isSignaling, dw1, dw0)
        if ((dw1 or dw0) == 0L)
            return zerBytes(out, sign, qExp)
        return fnzBytes(out, sign, qExp, dw1, dw0)
    }

    /**
     * Parse a decimal character sequence into a BID128 encoding.
     *
     * Returns `null` on success, with the encoding written to `out`: `out[0]`
     * takes the high 64 bits, `out[1]` the low 64. On failure returns a
     * human-readable message and writes the canonical positive quiet NaN, so a
     * caller who discards the message still observes a legal IEEE 754-2019
     * datum rather than stale or partial data. The exact wording of the
     * message is not part of the contract and may change between versions.
     *
     * Accepted input:
     *
     * - Plain notation: `"0"`, `"-1.5"`, `"123.456"`, `"0.0001"`.
     * - Scientific notation: `"1.5E10"`, `"-2.3e-7"`, `"6.02E+23"`. Both `E`
     *   and `e` are accepted as the exponent separator.
     * - Digit separators: `_` between two digits, in the coefficient or the
     *   exponent (`"1_000"`, `"1.234_567"`, `"1e1_0"`). An underscore must be
     *   both preceded and followed by a digit, so `"1_"`, `"_1"`, `"1__2"`,
     *   `"1_.5"` and `"1._5"` are all rejected.
     * - The case-insensitive words `Infinity`, `Inf`, `NaN`, `qNaN` and
     *   `sNaN`, each with an optional sign and an optional NaN payload of
     *   decimal digits (`"NaN123"`, `"-sNaN42"`). A payload may also be
     *   parenthesised, as `"NaN(123)"`.
     *
     * The parenthesised payload form is positional and balanced: `(`
     * immediately after the word, at least one digit, `)` last. `"NaN()"`,
     * `"NaN(1"`, `"NaN(1)2(3)"`, `"NaN[1]"` and `"NaN{1}"` are rejected.
     * Rendering never emits parentheses, and the full decimal128 ports accept
     * the same spelling.
     *
     * More than 34 significant digits round to 34, ties-to-even. Magnitudes
     * above the representable range become `Infinity`; magnitudes below it
     * become a subnormal or zero. None of those are failures.
     *
     * Failures are structural: an empty sequence, unrecognized characters,
     * missing digits around the radix point, a malformed exponent field.
     *
     * The two interchange encodings differ only in how the coefficient is
     * stored: BID128 as a binary integer, DPD128 as a leading digit plus
     * eleven 10-bit declets. The same text parses to peer encodings of the
     * same value in either format, and the canonical positive quiet NaN is the
     * same bit pattern in both.
     *
     * @param out receives the BID128 encoding, or the canonical positive
     *   quiet NaN on failure; length ≥ 2
     * @param str the character sequence to parse
     * @return `null` on success, or a human-readable message on failure
     * @throws IllegalArgumentException if `out.size < 2`
     */
    fun bid128Parse(out: LongArray, str: String): String? {
        require(out.size >= 2) { "out must have size >= 2, was ${out.size}" }
        val errorStr = encodeStringOrError(out, formatIsBid = true, str = str)
        if (errorStr != null) {
            out[0] = QNAN_PATTERN_HI
            out[1] = 0x0000000000000000L
        }
        return errorStr
    }

    /**
     * Parse a decimal character sequence into a DPD128 encoding.
     *
     * Returns `null` on success, with the encoding written to `out`: `out[0]`
     * takes the high 64 bits, `out[1]` the low 64. On failure returns a
     * human-readable message and writes the canonical positive quiet NaN, so a
     * caller who discards the message still observes a legal IEEE 754-2019
     * datum rather than stale or partial data. The exact wording of the
     * message is not part of the contract and may change between versions.
     *
     * Accepted input:
     *
     * - Plain notation: `"0"`, `"-1.5"`, `"123.456"`, `"0.0001"`.
     * - Scientific notation: `"1.5E10"`, `"-2.3e-7"`, `"6.02E+23"`. Both `E`
     *   and `e` are accepted as the exponent separator.
     * - Digit separators: `_` between two digits, in the coefficient or the
     *   exponent (`"1_000"`, `"1.234_567"`, `"1e1_0"`). An underscore must be
     *   both preceded and followed by a digit, so `"1_"`, `"_1"`, `"1__2"`,
     *   `"1_.5"` and `"1._5"` are all rejected.
     * - The case-insensitive words `Infinity`, `Inf`, `NaN`, `qNaN` and
     *   `sNaN`, each with an optional sign and an optional NaN payload of
     *   decimal digits (`"NaN123"`, `"-sNaN42"`). A payload may also be
     *   parenthesised, as `"NaN(123)"`.
     *
     * The parenthesised payload form is positional and balanced: `(`
     * immediately after the word, at least one digit, `)` last. `"NaN()"`,
     * `"NaN(1"`, `"NaN(1)2(3)"`, `"NaN[1]"` and `"NaN{1}"` are rejected.
     * Rendering never emits parentheses, and the full decimal128 ports accept
     * the same spelling.
     *
     * More than 34 significant digits round to 34, ties-to-even. Magnitudes
     * above the representable range become `Infinity`; magnitudes below it
     * become a subnormal or zero. None of those are failures.
     *
     * Failures are structural: an empty sequence, unrecognized characters,
     * missing digits around the radix point, a malformed exponent field.
     *
     * The two interchange encodings differ only in how the coefficient is
     * stored: BID128 as a binary integer, DPD128 as a leading digit plus
     * eleven 10-bit declets. The same text parses to peer encodings of the
     * same value in either format, and the canonical positive quiet NaN is the
     * same bit pattern in both.
     *
     * @param out receives the DPD128 encoding, or the canonical positive
     *   quiet NaN on failure; length ≥ 2
     * @param str the character sequence to parse
     * @return `null` on success, or a human-readable message on failure
     * @throws IllegalArgumentException if `out.size < 2`
     */
    fun dpd128Parse(out: LongArray, str: String): String? {
        require(out.size >= 2) { "out must have size >= 2, was ${out.size}" }
        val errorStr = encodeStringOrError(out, formatIsBid = false, str = str)
        if (errorStr != null) {
            out[0] = QNAN_PATTERN_HI
            out[1] = 0x0000000000000000L
        }
        return errorStr
    }

    // -- decode to String --------------------------------------------------------

    // 10**34 ... has 35 digits ... maxx = max eXclusive
    private const val MAXX_COEFF_34_HI = 0x0001ED09BEAD87C0L
    private const val MAXX_COEFF_34_LO = 0x378D8E6400000000L

    // 10**33 is the smallest 34-digit coefficient
    private const val MIN_PRECISION_34_COEFF_HI = 0x0000314DC6448D93L
    private const val MIN_PRECISION_34_COEFF_LO = 0x38C15B0A00000000L

    // 33 nines
    private const val MAX_PAYLOAD_HI = MIN_PRECISION_34_COEFF_HI
    private const val MAX_PAYLOAD_LO = MIN_PRECISION_34_COEFF_LO - 1L

    // The encoding's fixed bit patterns. SIGN_BIT is 0x8000000000000000
    // spelled as Long.MIN_VALUE, which is that pattern in a signed dword --
    // the same reason this file carries unsignedMulHi and ushr.
    private const val SIGN_BIT = Long.MIN_VALUE
    private const val INF_PATTERN_HI = 0x7800000000000000L
    private const val QNAN_PATTERN_HI = 0x7C00000000000000L
    private const val SNAN_PATTERN_HI = 0x7E00000000000000L

    private const val ascii_minus: Byte = 0x2D
    private const val ascii_plus: Byte = 0x2B
    private const val ascii_0: Byte = 0x30
    private const val ascii_dot: Byte = 0x2E
    private const val ascii_E: Byte = 0x45


    /* ----------------------------------------------------------------------
     * Rendering into the caller's buffer
     *
     * The only renderers. Both output forms land here: the caller-buffer
     * functions return what these write, and the String functions wrap it.
     *
     * These leaf renderers know nothing about BID or DPD: both decoders
     * reduce an encoding to (sign, qExp, coefficient) and hand it here.
     *
     * The two once had String-producing peers, so that "Infinity", a bare
     * NaN and every plain-form zero could come back as interned constants at
     * zero bytes allocated. That cost a second copy of the field extraction
     * and the class dispatch in each encoding -- the same forty lines written
     * twice, kept in step only by the corpus running both forms past the same
     * expected text.
     * One renderer per encoding is worth more than the constants; a String
     * result now always allocates a MAX_LEN scratch buffer and a String.
     *
     * None of the optional leading characters live in the string data. The
     * sign, and the 's' that marks a signaling NaN, are each written
     * unconditionally and then overwritten by whatever follows when they are
     * not wanted. The one exception is a TRAILING '+' in the exponent-form
     * zero, noted where it happens.
     * -------------------------------------------------------------------- */

    private const val ascii_s: Byte = 0x73




    private val INFINITY_TEXT = "Infinity".encodeToByteArray()
    private val NAN_TEXT = "NaN".encodeToByteArray()
    private val ZERO_EXP_TEXT = "0E".encodeToByteArray()

    /**
     * Every plain-form zero is a prefix of this literal. `k = -qExp` is the
     * count of zeros after the radix point, so `k == 0` renders `"0"` with no
     * radix point at all and `k >= 1` renders `"0."` followed by `k` zeros --
     * lengths 1 and `k + 2`.
     */
    private val PLAIN_ZERO = "0.000000".encodeToByteArray()

    private fun infBytes(out: ByteArray, sign: Boolean): Int {
        val signLen = if (sign) 1 else 0
        out[0] = ascii_minus // overwritten by the 'I' when unsigned
        INFINITY_TEXT.copyInto(out, signLen)
        return signLen + INFINITY_TEXT.size
    }

    private fun nanBytes(
        out: ByteArray, sign: Boolean, isSignaling: Boolean,
        payloadHi: Long, payloadLo: Long
    ): Int {
        val signLen = if (sign) 1 else 0
        val sLen = if (isSignaling) 1 else 0
        val wordOff = signLen + sLen
        val baseLen = wordOff + NAN_TEXT.size
        out[0] = ascii_minus   // overwritten by the 's' or the 'N' when unsigned
        out[signLen] = ascii_s // overwritten by the 'N' when quiet
        NAN_TEXT.copyInto(out, wordOff)
        if ((payloadHi or payloadLo) == 0L ||
            // non canonical IEEE 754-2019 3.5.2 c) 2)
            unsignedLT(MAX_PAYLOAD_HI, payloadHi) ||
            payloadHi == MAX_PAYLOAD_HI && unsignedLT(MAX_PAYLOAD_LO, payloadLo)
        )
            return baseLen
        val digitLen = calcDigitLen128(payloadHi, payloadLo)
        u128ToAscii(digitLen, payloadHi, payloadLo, out, baseLen)
        return baseLen + digitLen
    }

    private fun zerBytes(out: ByteArray, sign: Boolean, qExp: Int): Int {
        val signLen = if (sign) 1 else 0
        out[0] = ascii_minus // overwritten by the '0' when unsigned
        if (qExp <= 0 && qExp >= -6) {
            val k = -qExp
            val zeroLen = if (k == 0) 1 else k + 2
            PLAIN_ZERO.copyInto(out, signLen, 0, zeroLen)
            return signLen + zeroLen
        }
        // A positive exponent needs an explicit '+'; a negative one prints its
        // own '-' from appendIntBytes. Write the '+' unconditionally and let
        // appendIntBytes land on top of it when qExp < 0 -- the same discipline
        // as the leading sign, pointed the other way. It is correct only
        // because appendIntBytes emits a '-' for a negative value and emits
        // nothing for a positive one, and because the plain form above has
        // already taken every qExp in [-6, 0], so this branch never sees zero.
        val plusLen = if (qExp > 0) 1 else 0
        val baseLen = signLen + ZERO_EXP_TEXT.size + plusLen
        ZERO_EXP_TEXT.copyInto(out, signLen)
        out[signLen + ZERO_EXP_TEXT.size] = ascii_plus // overwritten by the '-' when qExp < 0
        return baseLen + appendIntBytes(out, baseLen, qExp)
    }

    /**
     * Write a signed decimal exponent at [off], returning its length. The
     * magnitude never exceeds 6176, so it is at most four digits.
     */
    private fun appendIntBytes(out: ByteArray, off: Int, v: Int): Int {
        var signLen = 0
        var magnitude = v
        if (v < 0) {
            out[off] = ascii_minus
            signLen = 1
            magnitude = -v
        }
        val digitLen = when {
            magnitude >= 1000 -> 4
            magnitude >= 100 -> 3
            magnitude >= 10 -> 2
            else -> 1
        }
        renderTailDigitsBeforeIndex(digitLen, magnitude.toLong(), out, off + signLen + digitLen)
        return signLen + digitLen
    }

    private fun fnzBytes(out: ByteArray, sign: Boolean, qExp: Int, dw1: Long, dw0: Long): Int {
        val digitLen = calcDigitLen128(dw1, dw0)
        val eExp = qExp + (digitLen - 1)
        if (qExp == 0)
            return fnzToIntegerBytes(out, sign, digitLen, dw1, dw0)
        if (qExp <= 0 && eExp >= -6)
            return fnzToDecimalPointBytes(out, sign, qExp, digitLen, dw1, dw0)
        return fnzToNormalizedScientificBytes(out, sign, qExp, digitLen, dw1, dw0)
    }

    private fun fnzToIntegerBytes(out: ByteArray, sign: Boolean, digitLen: Int, dw1: Long, dw0: Long): Int {
        verify { digitLen > 0 }
        val signLen = if (sign) 1 else 0
        out[0] = ascii_minus // overwritten by the first digit when unsigned
        u128ToAscii(digitLen, dw1, dw0, out, signLen)
        return signLen + digitLen
    }

    private fun fnzToDecimalPointBytes(
        out: ByteArray, sign: Boolean, qExp: Int,
        digitLen: Int, dw1: Long, dw0: Long
    ): Int {
        val digitsRightOfDecimal = -qExp
        val leadingZeroCount = max(1 + digitsRightOfDecimal - digitLen, 0)
        val signLen = if (sign) 1 else 0
        val decimalPointLen = 1
        val totalLen = signLen + leadingZeroCount + decimalPointLen + digitLen
        out[0] = ascii_minus
        for (i in signLen..leadingZeroCount) // there is one extra here
            out[i] = ascii_0
        u128ToAscii(digitLen, dw1, dw0, out, signLen + leadingZeroCount)
        for (i in totalLen - 1 downTo totalLen - digitsRightOfDecimal)
            out[i] = out[i - 1]
        out[totalLen - digitsRightOfDecimal - 1] = ascii_dot
        return totalLen
    }

    private fun fnzToNormalizedScientificBytes(
        out: ByteArray, sign: Boolean, qExp: Int,
        digitLen: Int, dw1: Long, dw0: Long
    ): Int {
        verify { digitLen > 0 }
        val eExp = qExp + (digitLen - 1)
        val eExpAbs = (eExp xor (eExp shr 31)) - (eExp shr 31)
        val signLen = if (sign) 1 else 0
        val decimalPointLen = if (digitLen > 1) 1 else 0
        val expELen = 1
        val expSignLen = 1
        val expSignByte = if (eExp < 0) ascii_minus else ascii_plus
        val expDigitLen = when {
            eExpAbs >= 1000 -> 4
            eExpAbs >= 100 -> 3
            eExpAbs >= 10 -> 2
            else -> 1
        }
        val totalLen = signLen + decimalPointLen + digitLen + expELen + expSignLen + expDigitLen
        out[0] = ascii_minus
        u128ToAscii(digitLen, dw1, dw0, out, signLen + decimalPointLen)
        if (decimalPointLen > 0) {
            out[signLen] = out[signLen + 1]
            out[signLen + 1] = ascii_dot
        }
        val iE = signLen + decimalPointLen + digitLen
        out[iE] = ascii_E
        out[iE + 1] = expSignByte
        renderTailDigitsBeforeIndex(expDigitLen, eExpAbs.toLong(), out, totalLen)
        return totalLen
    }

    // ------- render an integer -------------------------------------------

    /**
     * Reciprocal-multiplication constants for unsigned `÷ 10^8` on a 64-bit
     * dividend, exact for all non-negative inputs.
     *
     * Compute `n / 10^8` as `unsignedMulHi(n, M_U64_DIV_1E8) ushr S_U64_DIV_1E8`.
     * The `S_U64_DIV_1E8` shift is "+ 64 high" because `unsignedMulHi` already
     * returns the upper 64 bits of the 128-bit product; the named shift
     * combines with that to give the effective magic-number divide.
     */
    private const val M_U64_DIV_1E8 = Long.MIN_VALUE or 0x2BCC77118461CEFDL // -6067343680855748867
    private const val S_U64_DIV_1E8 = 26 // + 64 high

    private const val MASK32L = 0x0000_0000_FFFF_FFFFL

    /**
     * Render a 128-bit unsigned integer as `digitPrintCount` decimal digits
     * into [ascii], ending at offset `off + digitPrintCount`.
     *
     * Three phases:
     *
     *  1. While `dw1T != 0`: peel 8 digits per outer iteration via three
     *     `÷ 10^8` divisions on rotating 96-bit chunks, using
     *     [unsignedMulHi] for the high half of each multi-word divide.
     *
     *  2. Once the value fits in 64 bits: peel 8 digits at a time via a
     *     single `÷ 10^8` per iteration.
     *
     *  3. For 1–7 leftover digits: delegate to [renderTailDigitsBeforeIndex].
     *
     * Each 8-digit chunk is rendered by [render8DigitsBeforeIndex]. The
     * caller must have allocated a buffer with at least
     * `off + digitPrintCount` bytes, and is responsible for any leading-zero
     * padding outside the rendered range.
     *
     * @return `digitPrintCount`, unchanged from the input
     */
    private fun u128ToAscii(
        digitPrintCount: Int, dw1: Long, dw0: Long,
        ascii: ByteArray, off: Int
    ): Int {
        var dw1T = dw1
        var dw0T = dw0
        var ich = off + digitPrintCount
        var remainingDigitCount = digitPrintCount
        while (dw1T != 0L) {
            val q2 = unsignedMulHi(dw1T, M_U64_DIV_1E8) ushr S_U64_DIV_1E8
            val r2 = dw1T - (q2 * 1_0000_0000L)
            val s2 = (r2 shl 32) or (dw0T ushr 32)
            val q1 = unsignedMulHi(s2, M_U64_DIV_1E8) ushr S_U64_DIV_1E8
            val r1 = s2 - (q1 * 1_0000_0000L)
            val s1 = (r1 shl 32) or (dw0T and MASK32L)
            val q0 = unsignedMulHi(s1, M_U64_DIV_1E8) ushr S_U64_DIV_1E8
            val r0 = s1 - (q0 * 1_0000_0000L)
            dw0T = (q1 shl 32) + q0
            dw1T = q2
            render8DigitsBeforeIndex(r0, ascii, ich)
            ich -= 8
            remainingDigitCount -= 8
        }
        while (remainingDigitCount >= 8) {
            val t0 = unsignedMulHi(dw0T, M_U64_DIV_1E8) ushr S_U64_DIV_1E8
            val r0 = dw0T - (t0 * 1_0000_0000L)
            dw0T = t0
            render8DigitsBeforeIndex(r0, ascii, ich)
            ich -= 8
            remainingDigitCount -= 8
        }
        if (remainingDigitCount > 0)
            renderTailDigitsBeforeIndex(remainingDigitCount, dw0T, ascii, ich)
        return digitPrintCount
    }

    /**
     * Reciprocal-multiplication constants for unsigned division by small
     * powers of 10 on 32-bit dividends.
     *
     * `M_U32_DIV_1E1` / `S_U32_DIV_1E1`: divide by 10, valid for
     * dividends up to 2^32 - 1.
     *
     * `M_U32_DIV_1E2`: divide by 100, same range.
     *
     * `M_U64_DIV_1E4`: divide by 10000 on 64-bit dividends. Used to split
     * an 8-digit value into two 4-digit halves.
     */
    private const val M_U32_DIV_1E1 = 0xCCCCCCCDL
    private const val S_U32_DIV_1E1 = 35

    private const val M_U32_DIV_1E2 = 0x51EB851FL
    private const val S_U32_DIV_1E2 = 37

    private const val M_U64_DIV_1E4 = 0x346DC5D63886594BL
    private const val S_U64_DIV_1E4 = 11 // + 64 high

    /**
     * Render exactly 8 decimal digits of [dw] into [ascii], ending at
     * `offMaxx`. Caller must ensure `dw < 10^8`; the function pads with
     * leading zeros if `dw` has fewer than 8 significant digits.
     *
     * Implemented as a binary cascade: `÷ 10^4` once to split into two
     * 4-digit halves, then `÷ 10^2` twice to split each half into two
     * 2-digit pairs, then `÷ 10` four times to extract individual digits.
     * The 8 byte stores at the end are independent and benefit from a
     * hoisted bounds check that lets the JIT elide per-store checks.
     *
     * Always writes exactly 8 bytes at offsets `offMaxx - 8` through
     * `offMaxx - 1`. Throws [IndexOutOfBoundsException] if those offsets
     * are not within [ascii].
     */
    private fun render8DigitsBeforeIndex(dw: Long, ascii: ByteArray, offMaxx: Int) {
        val abcd = unsignedMulHi(dw, M_U64_DIV_1E4) ushr S_U64_DIV_1E4
        val efgh = dw - (abcd * 10000L)

        val ab = (abcd * M_U32_DIV_1E2) ushr S_U32_DIV_1E2
        val cd = abcd - (ab * 100L)

        val ef = (efgh * M_U32_DIV_1E2) ushr S_U32_DIV_1E2
        val gh = efgh - (ef * 100L)

        val a = (ab * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
        val b = ab - (a * 10L)

        val c = (cd * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
        val d = cd - (c * 10L)

        val e = (ef * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
        val f = ef - (e * 10L)

        val g = (gh * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
        val h = gh - (g * 10L)

        // Explicit bounds check to enable elimination of individual checks
        val offMin = offMaxx - 8
        if (offMin >= 0 && offMaxx <= ascii.size) {
            ascii[offMaxx - 8] = (a.toInt() + '0'.code).toByte()
            ascii[offMaxx - 7] = (b.toInt() + '0'.code).toByte()
            ascii[offMaxx - 6] = (c.toInt() + '0'.code).toByte()
            ascii[offMaxx - 5] = (d.toInt() + '0'.code).toByte()
            ascii[offMaxx - 4] = (e.toInt() + '0'.code).toByte()
            ascii[offMaxx - 3] = (f.toInt() + '0'.code).toByte()
            ascii[offMaxx - 2] = (g.toInt() + '0'.code).toByte()
            ascii[offMaxx - 1] = (h.toInt() + '0'.code).toByte()
        } else {
            throw IndexOutOfBoundsException()
        }
    }

    /**
     * Render exactly [renderDigitCount] decimal digits of [dw] into [ascii],
     * ending at `offMaxx`. Pads with leading zeros if `dw` has fewer
     * significant digits than `renderDigitCount`.
     *
     * Two phases:
     *
     *  1. While at least 4 digits remain: peel 4 digits per iteration
     *     via `÷ 10^4` followed by a 2-digit / 1-digit cascade. Hoisted
     *     bounds check enables bounds check elimination within the
     *     iteration.
     *
     *  2. For the remaining 0–3 digits: peel one digit at a time via
     *     `÷ 10` thru reciprocal multiplication.
     *
     * Always writes exactly `renderDigitCount` bytes at offsets
     * `offMaxx - renderDigitCount` through `offMaxx - 1`.
     *
     * @throws IllegalArgumentException if the 4-digit phase would write
     *         outside [ascii]
     */
    private fun renderTailDigitsBeforeIndex(renderDigitCount: Int, dw: Long, ascii: ByteArray, offMaxx: Int) {
        var t = dw
        var remainingDigitCount = renderDigitCount
        var ib = offMaxx
        while (remainingDigitCount >= 4) {
            val t0 = unsignedMulHi(t, M_U64_DIV_1E4) ushr S_U64_DIV_1E4
            val abcd = t - (t0 * 10000L)
            t = t0
            val ab = (abcd * M_U32_DIV_1E2) ushr S_U32_DIV_1E2
            val cd = abcd - (ab * 100L)
            val a = (ab * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
            val b = ab - (a * 10L)
            val c = (cd * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
            val d = cd - (c * 10L)
            if (ib - 4 >= 0 && ib <= ascii.size) {
                ascii[ib - 4] = (a.toInt() + '0'.code).toByte()
                ascii[ib - 3] = (b.toInt() + '0'.code).toByte()
                ascii[ib - 2] = (c.toInt() + '0'.code).toByte()
                ascii[ib - 1] = (d.toInt() + '0'.code).toByte()
                ib -= 4
                remainingDigitCount -= 4
            } else {
                throw IllegalArgumentException()
            }
        }
        while (remainingDigitCount > 0) {
            val divTen = (t * M_U32_DIV_1E1) ushr S_U32_DIV_1E1
            val digit = (t - (divTen * 10L)).toInt()
            ascii[--ib] = ('0'.code + digit).toByte()
            t = divTen
            --remainingDigitCount
        }
        verify { offMaxx - ib == renderDigitCount }
    }

    // -- encode from String --------------------------------------------------------

    /**
     * The code of the character at [index], or -1 past the end of the string.
     * The parsers walk the input as character codes, so end-of-input is one
     * more code value rather than a bound test at every read. -1 is not a
     * character, which keeps NUL an ordinary input character -- and an
     * invalid one -- instead of doubling as the terminator.
     */
    private inline fun codeAt(str: String, index: Int): Int = if (index >= 0 && index < str.length) str[index].code else -1

    /**
     * Top-level dispatcher for parsing a decimal character sequence into an
     * encoding. Reads optional sign, then dispatches by leading character
     * to the finite-value, infinity, or NaN parser.
     *
     * The leading character is also passed through to the dispatched
     * parser so it doesn't have to re-read what the dispatcher already
     * consumed.
     *
     * @return `null` on success (with `out` populated), or a
     *         human-readable error string on failure
     */
    private fun encodeStringOrError(out: LongArray, formatIsBid: Boolean, str: String): String? {
        var pos = 0
        val chFirst = codeAt(str, pos++)
        var ch = chFirst
        val sign = ch == '-'.code
        if (ch == '+'.code || ch == '-'.code)
            ch = codeAt(str, pos++)
        if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code)
            return parseFiniteValueText(out, formatIsBid, sign, ch, str, pos)
        val chLower = ch or 0x20
        if (chLower == 'i'.code)
            return parseInfinityText(out, sign, ch, str, pos)
        if (chLower == 'n'.code || chLower == 's'.code || chLower == 'q'.code)
            return parseNanText(out, formatIsBid, sign, ch, str, pos)
        if (chFirst == -1)
            return "empty string"
        return "invalid format"
    }

    /**
     * Parse the keyword `Infinity` (or its 3-letter abbreviation `Inf`),
     * case-insensitively, and write the corresponding encoding into [out].
     * Returns an error string for any other input.
     */
    private fun parseInfinityText(out: LongArray, sign: Boolean, chFirst: Int, str: String, startPos: Int): String? {
        var pos = startPos
        var ch = chFirst
        var chPrevCode = 0
        val target = "infinity"
        for (i in 0 until 8) {
            val targetCode = target[i].code
            if (ch or 0x20 != targetCode) {
                if (ch == -1)
                    break
                else
                    return "failure parsing Infinity"
            }
            chPrevCode = ch or 0x20
            ch = codeAt(str, pos++)
        }
        if (ch != -1 || (chPrevCode != 'f'.code && chPrevCode != 'y'.code))
            return "failure parsing Infinity"
        out[0] = INF_PATTERN_HI or if (sign) SIGN_BIT else 0L
        out[1] = 0L
        return null
    }

    /**
     * Parse `NaN`, `qNaN`, or `sNaN` (case-insensitive) with an optional
     * decimal payload, and write the corresponding encoding into [out].
     * Payloads of 34+ digits AS WRITTEN are non-canonical and produce a
     * zero payload per IEEE 754-2019 §3.5.2.
     *
     * The payload may be written bare (`NaN123`) or enclosed in parentheses
     * (`NaN(123)`). The parenthesised form is positional and balanced: the
     * `(` must follow the word immediately, at least one digit must appear,
     * and the `)` must be the last character of the input. So `NaN(1)2(3)`,
     * `NaN(1`, `NaN(1]` and `NaN()` are all rejected, and `NaN[1]` / `NaN{1}`
     * are not this form at all.
     *
     * The reason this spelling exists at all
     * is that C's own `strtod` reads
     * `NAN(n-char-sequence)`, so the spelling reaches a caller from the
     * platform itself. The resemblance stops at the parentheses -- strtod's
     * sequence is letters, digits and underscores read as an
     * implementation-defined integer (`NAN(abc)` and `NAN(0x1c8)` are legal
     * there), while a decimal128 payload is decimal digits. This is a
     * paren-tolerant spelling of our own payload syntax, not C's.
     *
     * The legacy base skipped `(`, `)`, `[`, `]`, `{` and `}` ANYWHERE, so it
     * read `NaN(1)2(3)` as payload 123. That is not this, and stays rejected.
     */
    private fun parseNanText(out: LongArray, formatIsBid: Boolean, sign: Boolean, chFirst: Int, str: String, startPos: Int): String? {
        var pos = startPos
        var ch = chFirst
        val hasS = (ch or 0x20) == 's'.code
        val hasQ = (ch or 0x20) == 'q'.code
        if (hasQ or hasS)
            ch = codeAt(str, pos++)
        if (((ch or 0x20) != 'n'.code) ||
            ((codeAt(str, pos++) or 0x20) != 'a'.code) ||
            ((codeAt(str, pos++) or 0x20) != 'n'.code)
        )
            return "failure parsing NaN"
        var payloadDw0 = 0L
        var payloadDw1 = 0L
        ch = codeAt(str, pos++)
        val hasParen = ch == '('.code
        if (hasParen)
            ch = codeAt(str, pos++)
        if (hasParen || ch != -1) {
            var accumDigitCount = 0
            var accum19a = 0L
            var accum19b = 0L
            while (ch >= '0'.code && ch <= '9'.code) {
                val d = ch - '0'.code
                // Count every digit written, leading zeros included. The
                // 33-digit limit below is a limit on the payload as spelled,
                // not on its significant digits, so "NaN" followed by forty
                // zeros and a one is over the limit and reads as zero.
                // Leading zeros contribute nothing to the accumulators, so
                // the value of a payload within the limit is unaffected.
                ++accumDigitCount
                if (accumDigitCount <= 19)
                    accum19a = (accum19a * 10L) + d.toLong()
                else
                    accum19b = (accum19b * 10L) + d.toLong()
                ch = codeAt(str, pos++)
            }
            if (accumDigitCount == 0)
                return if (hasParen) "failure parsing NaN" else "non-digit NaN payload character"
            if (hasParen) {
                // the ')' must be present and must end the input
                if (ch != ')'.code)
                    return if (ch == -1) "failure parsing NaN" else "non-digit NaN payload character"
                if (codeAt(str, pos++) != -1)
                    return "failure parsing NaN"
            } else if (ch != -1) {
                return "non-digit NaN payload character"
            }
            if (accumDigitCount > 33) {
                // IEEE754-2019 3.5.2 Encodings
                //  ... as the payload of a NaN). If the value exceeds the maximum,
                //  the significand c is non-canonical and the value used for c is zero.
                payloadDw0 = 0
                payloadDw1 = 0
            } else {
                payloadDw0 = accum19a
                payloadDw1 = 0L
                if (accumDigitCount > 19) {
                    val pow10 = accumDigitCount - 19
                    u128FmaPow10(out, accum19a, pow10, accum19b)
                    payloadDw1 = out[0]
                    payloadDw0 = out[1]
                }
            }
        }
        setNaN(out, formatIsBid, sign, hasS, payloadDw1, payloadDw0)
        return null
    }

    /**
     * Pack a NaN into whichever interchange format the caller asked for.
     *
     * A NaN payload is a coefficient, so the formats diverge here exactly as
     * they do for finite values: BID keeps it as a binary integer, DPD as
     * declets. Infinity by contrast has no coefficient, which is why it is
     * byte-identical in both and needs no such split.
     */
    private fun setNaN(out: LongArray, formatIsBid: Boolean, sign: Boolean,
                       isSignaling: Boolean, payloadDw1: Long, payloadDw0: Long) {
        if (formatIsBid)
            bid128SetNaN(out, sign, isSignaling, payloadDw1, payloadDw0)
        else
            dpd128SetNaN(out, sign, isSignaling, payloadDw1, payloadDw0)
    }

    private fun bid128SetNaN(out: LongArray, sign: Boolean,
                             isSignaling: Boolean, payloadDw1: Long, payloadDw0: Long) {
        val withoutPayload =
            (if (isSignaling) SNAN_PATTERN_HI else QNAN_PATTERN_HI) or (if (sign) SIGN_BIT else 0L)
        out[0] = withoutPayload or payloadDw1
        out[1] = payloadDw0
    }

    /**
     * Pack a NaN into DPD128. The payload occupies the whole 110-bit trailing
     * field as eleven declets -- 33 digits, with no leading digit in the
     * combination field, since G0..G4 are fixed at 11111 and G5 carries the
     * signaling flag.
     *
     * [out] doubles as scratch for the two divisions before it is written.
     */
    private fun dpd128SetNaN(out: LongArray, sign: Boolean,
                             isSignaling: Boolean, payloadDw1: Long, payloadDw0: Long) {
        verify { calcDigitLen128(payloadDw1, payloadDw0) <= 33 }

        val r1 = u113DivModPow10Max9(out, payloadDw1, payloadDw0, 9)
        val r2 = u113DivModPow10Max9(out, out[0], out[1], 9)
        val binLo = (r2 * 1_000_000_000L) + r1
        verify { out[0] == 0L } // a 33-digit payload leaves 15 digits up top
        val declets5Hi = decletsFromBin(out[1])
        val declets6Lo = decletsFromBin(binLo)

        val gField = (0b11111 shl 12) or (if (isSignaling) 1 shl 11 else 0)
        val signCombo = ((if (sign) 1 else 0) shl 17) or gField
        out[0] = (signCombo.toLong() shl 46) or (declets5Hi ushr 4)
        out[1] = (declets5Hi shl 60) or declets6Lo
    }

    /**
     * Parse a finite decimal numeral and write the corresponding
     * encoding into [out]. Accepts an optional decimal point, an
     * optional `E`/`e` exponent (with optional `+`/`-` sign), and any
     * number of significant digits.
     *
     * Coefficients with more than 34 significant digits are accepted;
     * leading zeros before a non-zero digit are flushed; the digits past
     * 34 are properly tracked as `residue` for subsequent
     * `roundTiesToEven` rounding. Excess exponent magnitudes are clamped
     * to 9999, which is sufficient for the rounding logic to detect
     * overflow or underflow.
     *
     * Sign is taken from the [sign] parameter (already consumed by the
     * dispatcher); the leading character [chFirst] is the first digit or
     * `.` and is processed by the main loop. Returns `null` on success or
     * a human-readable error string on syntax errors.
     */
    private fun parseFiniteValueText(
        out: LongArray,
        formatIsBid: Boolean,
        sign: Boolean,
        chFirst: Int,
        str: String,
        startPos: Int
    ): String? {
        var pos = startPos
        var residue = EXACT

        var hasCoefficientDigit = false // have we seen any digits at all, including zero
        var significantDigitCount = 0 // does not count leading zeros
        var hasDot = false
        var expSign = false
        // an underscore is a digit separator: legal only between two digits,
        // so it must follow a digit and the next character must be a digit too
        var prevWasDigit = false
        var afterUnderscore = false

        verify { chFirst != -1 }
        var ch = chFirst
        var fractionalDigitCount = 0
        var accum19a = 0L
        var accum19b = 0L
        var exp = 0

        while (ch in '0'.code..'9'.code || ch == '.'.code || ch == '_'.code) {
            when (ch) {
                in '0'.code..'9'.code -> {
                    val d = ch - '0'.code
                    hasCoefficientDigit = true
                    // count while flushing leading zeros
                    significantDigitCount += (-(significantDigitCount or d)) ushr 31
                    when {
                        significantDigitCount <= 19 -> accum19a = (accum19a * 10L) + d.toLong()
                        significantDigitCount <= 34 -> accum19b = (accum19b * 10L) + d.toLong()
                        significantDigitCount == 34 + 1 ->
                            residue = residueFromDecimalDigit(d)

                        else ->
                            residue = residueMerge(residue, residueFromDecimalDigit(d))
                    }
                    if (hasDot)
                        ++fractionalDigitCount
                    prevWasDigit = true
                    afterUnderscore = false
                }

                '_'.code -> when {
                    !prevWasDigit -> return "invalid underscore location"
                    else -> {
                        prevWasDigit = false
                        afterUnderscore = true
                    }
                }

                '.'.code -> when {
                    afterUnderscore -> return "invalid underscore location"
                    hasDot -> return "double radix dot"
                    else -> {
                        hasDot = true
                        prevWasDigit = false
                    }
                }
            }
            ch = codeAt(str, pos++)
        }
        if (afterUnderscore)
            return "invalid underscore location"
        if (!hasCoefficientDigit)
            return "no coefficient digit"
        // this path has at least one digit
        if (ch == 'E'.code || ch == 'e'.code) {
            ch = codeAt(str, pos++)
            if (ch == '+'.code || ch == '-'.code) {
                expSign = ch == '-'.code
                ch = codeAt(str, pos++)
            }
            var hasExpDigit = false
            var expSignificantDigitCount = 0
            prevWasDigit = false
            afterUnderscore = false
            while (ch in '0'.code..'9'.code || ch == '_'.code) {
                when (ch) {
                    '_'.code -> when {
                        !prevWasDigit -> return "invalid underscore location"
                        else -> {
                            prevWasDigit = false
                            afterUnderscore = true
                        }
                    }

                    else -> {
                        hasExpDigit = true
                        val eDigit = ch - '0'.code
                        // count while flushing leading zeros
                        expSignificantDigitCount +=
                            (-(expSignificantDigitCount or eDigit)) ushr 31
                        exp = exp * 10 + eDigit
                        prevWasDigit = true
                        afterUnderscore = false
                    }
                }
                ch = codeAt(str, pos++)
            }
            if (afterUnderscore)
                return "invalid underscore location"
            if (!hasExpDigit)
                return "no exponent digit"
            // clamp exp to 9999 once after the loop
            if (expSignificantDigitCount > 4)
                exp = 9999
        }
        if (ch != -1)
            return "unexpected char"
        // we have at least one digit
        var dw0 = accum19a
        var dw1 = 0L
        if (significantDigitCount > 19) {
            val pow10 = min(34, significantDigitCount) - 19
            u128FmaPow10(out, accum19a, pow10, accum19b)
            dw1 = out[0]
            dw0 = out[1]
        }
        // at this point, our coeff <= precision digits
        // but we need to deal with residue and rounding
        // rounding rollover could affect the exponent
        //
        // when we accept oversize coefficients, then whether the excess
        // digits are to the right or left of the exponent will affect
        // the qExp ...
        val signedExp = if (expSign) -exp else exp
        val qExp = signedExp - fractionalDigitCount + max(0, significantDigitCount - 34)
        roundAndFinalizeFinite(out, formatIsBid, sign, qExp, dw1, dw0, residue)
        return null
    }

    /**
     * Round and finalize a finite parsed coefficient/exponent
     * pair into the encoding stored in [out].
     *
     * Implements `roundTiesToEven` rounding with full handling of the
     * decimal128 boundary conditions: oversized coefficient, underflow to
     * subnormal or zero, overflow to infinity, and the clamped-near-Emax
     * region where a finite value with too-large quantum exponent can be
     * representable by absorbing the excess into the coefficient.
     *
     * Step 1 (fast path): if the input already fits within precision and
     * range and no rounding is required (residue ≤ LT_HALF, or HALF with
     * an even retained low bit), encode and return immediately.
     * This picks up the vast majority of cases.
     *
     * Step 2 (zero coefficient): zeros that are out of qExp range get
     * clamped to the valid range.
     *
     * -Step 3 (special values): Not applicable here — special values
     * are handled by callers before reaching this function.
     *
     * Step 4 (underflow): if the exponent is below Emin, divert to
     * [finalizeUnderflowRegion] for subnormal handling.
     *
     * Step 5 (precision truncation): REMOVED as unreachable from a codec's
     * callers -- see the note in the body, which also states what would have
     * to change for it to be needed again.
     *
     * Step 6 (rounding): apply `roundTiesToEven`. If rounding causes the
     * coefficient to roll over to 10^34, normalize to 10^33 and increment
     * the exponent.
     *
     * Step 7 (overflow / clamping): if the exponent exceeds Emax, either
     * absorb the excess into the coefficient (clamping near Emax) or
     * overflow to infinity.
     */
    private fun roundAndFinalizeFinite(
        out: LongArray,
        formatIsBid: Boolean,
        sign: Boolean,
        qExpIn: Int,
        dw1In: Long,
        dw0In: Long,
        residueIn: Int
    ) {
        // Step 1: Fast path: already in valid decimal128 range ...
        //         ... and no roundTiesToEven rounding
        if (residueIn <= LT_HALF || residueIn == HALF && (dw0In and 1L) == 0L) {
            if (coeffQExpFit(dw1In, dw0In, qExpIn)) {
                setFinite(out, formatIsBid, sign, qExpIn, dw1In, dw0In)
                return
            }
            // Step 2: zero coefficient with out-of-range qExp ... clamp qExp
            if ((dw1In or dw0In) == 0L) {
                val qClamped = max(min(qExpIn, 6111), -6176)
                setFinite(out, formatIsBid, sign, qClamped, 0L, 0L)
                return
            }
        }

        // Step 3: special values ... not applicable ... only Finite

        // Step 5 -- precision truncation -- IS NOT HERE, and this is the reason.
        //
        // This finalize chain descends from one built for an arithmetic engine,
        // whose callers hand it coefficients wider than the 34-digit precision
        // limit: multiply and FMA produce a double-width product, and the engine
        // truncates it here, accumulating the discarded digits into the residue
        // before rounding. A codec has no such caller.
        //
        // [parseFiniteValueText] accumulates at most 19 + 15 = 34 significant
        // digits and turns every digit past them into a residue instead, so the
        // coefficient it hands over is never wider than the limit. The only
        // other entry to this function is the swamped branch of
        // [finalizeUnderflowRegion], which passes a zero coefficient. So the
        // truncation step is unreachable, and rather than carry a path that has
        // never once executed -- and therefore has never been verified to work
        // -- it is removed and the invariant is enforced.
        //
        // Measured: zero
        // executions across the 87k-row shared corpus, 400,000 random
        // numerals, and 76,280 finalize calls driven by inputs of up to 5,000
        // digits, where the widest coefficient ever seen here was 34.
        //
        // This is `check`, not `verify`: it is unconditional, because a build
        // with VERIFY_ENABLED off would otherwise fall through and encode an
        // out-of-range coefficient silently. It cannot fire on a caller's data
        // -- no input reaches it -- only on a future caller that breaks the
        // invariant, which is exactly when a loud failure is wanted. A caller
        // that is not a parser needs the truncation step back, as a multiply
        // or an FMA would.
        check(calcDigitLen128(dw1In, dw0In) <= 34)

        // Step 4: underflow. With no precision truncation to outrank it, the
        // test is simply whether the exponent is below the minimum.
        if (qExpIn < -6176) {
            finalizeUnderflowRegion(out, formatIsBid, sign, qExpIn, dw1In, dw0In, residueIn)
            return
        }

        var totalResidue = residueIn
        var dw1 = dw1In
        var dw0 = dw0In
        var qExp = qExpIn

        // step 6: rounding ... in this world only roundTiesToEven
        val applyRounding = totalResidue == GT_HALF || totalResidue == HALF && (dw0 and 1L) != 0L
        if (applyRounding) {
            // step 6.1: increment
            ++dw0
            dw1 += if (dw0 == 0L) 1L else 0L

            // step 6.2: rollover
            if (dw1 == MAXX_COEFF_34_HI && dw0 == MAXX_COEFF_34_LO) {
                dw1 = MIN_PRECISION_34_COEFF_HI
                dw0 = MIN_PRECISION_34_COEFF_LO
                ++qExp
            }
        }

        // step 7: check final bounds
        verify { qExp >= -6176 }
        verify { calcDigitLen128(dw1, dw0) <= 34 }
        if (qExp > 6111) {
            val qExcess = qExp - 6111
            val digitLen = calcDigitLen128(dw1, dw0)
            if (digitLen + qExcess <= 34)
                finalizeClamping(out, formatIsBid, sign, qExp, dw1, dw0)
            else
                finalizeOverflow(out, sign)
            return
        }
        setFinite(out, formatIsBid, sign, qExp, dw1, dw0)
    }

    /**
     * Pack a finite value into whichever interchange format the caller asked
     * for. BID and DPD are peers: both carry the same sign, the same 14-bit
     * biased exponent and the same 34-digit coefficient, and differ only in
     * how that coefficient is laid out. Everything upstream of this point --
     * rounding, clamping and the underflow region -- is format-neutral.
     */
    private fun setFinite(out: LongArray, formatIsBid: Boolean, sign: Boolean, qExp: Int, dw1: Long, dw0: Long) {
        if (formatIsBid)
            bid128SetFinite(out, sign, qExp, dw1, dw0)
        else
            dpd128SetFinite(out, sign, qExp, dw1, dw0)
    }

    /**
     * Pack a finite value into DPD128. The peer of [bid128SetFinite]: same
     * inputs, same sign bit, same 14-bit biased exponent, and a coefficient
     * of the same 34 digits -- laid out as a leading digit carried in the
     * combination field plus eleven declets.
     *
     * The leading digit cannot be packed independently of the exponent: DPD
     * puts it in the same five-bit prefix as the exponent's top two bits, so
     * unlike BID there is no shared "pack sign and exponent" step to factor out.
     *
     * [out] doubles as scratch for the two divisions before it is written.
     */
    private fun dpd128SetFinite(out: LongArray, sign: Boolean, qExp: Int, dw1: Long, dw0: Long) {
        verify { qExp >= -6176 && qExp <= 6111 }
        verify { calcDigitLen128(dw1, dw0) <= 34 }

        // Split the coefficient into its low 18 digits and its high 16.
        // Two divisions by 10^9 rather than one by 10^18, so the existing
        // 128-bit kernel does the work and no wider machinery is needed.
        val r1 = u113DivModPow10Max9(out, dw1, dw0, 9)
        val r2 = u113DivModPow10Max9(out, out[0], out[1], 9)
        val binLo = (r2 * 1_000_000_000L) + r1
        verify { out[0] == 0L } // the high 16 digits fit in one word
        val binHi = out[1]

        // the top digit rides in the combination field; the other 15 are declets
        val d0 = binHi / TEN_POW_15
        val declets5Hi = decletsFromBin(binHi - (d0 * TEN_POW_15))
        val declets6Lo = decletsFromBin(binLo)
        verify { d0 in 0L..9L }

        val biasedQExp = qExp + 6176
        val expMSB = (biasedQExp shr 12) and 0x3
        val expLSB = biasedQExp and 0x0FFF
        val gPrefix =
            if (d0 <= 7L) (expMSB shl 3) or d0.toInt()          // G0G1 = exponent MSBs
            else 0b11000 or (expMSB shl 1) or (d0.toInt() and 1) // G0G1 = 11, digit is 8 or 9
        val signCombo = ((if (sign) 1 else 0) shl 17) or (gPrefix shl 12) or expLSB

        out[0] = (signCombo.toLong() shl 46) or (declets5Hi ushr 4)
        out[1] = (declets5Hi shl 60) or declets6Lo
    }

    /**
     * Pack the sign, biased quantum exponent, and 113-bit coefficient into
     * the BID128 encoding stored in [out].
     *
     * Always uses the short-form encoding (combination field top bits not
     * `11`), which is correct for any canonical coefficient whose top 3
     * bits fit in 0..7.
     *
     * Preconditions are checked via [verify] and active under compile-time
     * control: the exponent must be in [-6176, 6111] and the coefficient
     * must have at most 34 decimal digits.
     */
    private fun bid128SetFinite(out: LongArray, sign: Boolean, qExp: Int, dw1: Long, dw0: Long) {
        verify { qExp >= -6176 && qExp <= 6111 }
        verify { calcDigitLen128(dw1, dw0) <= 34 }
        val qBiased = qExp + 6176
        val s = if (sign) SIGN_BIT else 0L
        val g = qBiased.toLong() shl (3 + 46)
        out[0] = s or g or dw1
        out[1] = dw0
    }

    /**
     * Encode `Infinity` (or `-Infinity`) into [out].
     */
    private fun finalizeOverflow(out: LongArray, sign: Boolean) {
        out[0] = (if (sign) SIGN_BIT else 0L) or INF_PATTERN_HI
        out[1] = 0L
    }

    /**
     * Encode a finite value whose natural quantum exponent exceeds qMax
     * (6111) but whose coefficient has enough leading capacity to absorb
     * the excess.
     *
     * Multiplies the coefficient by 10^qExcess (where qExcess is qExp
     * minus 6111), reducing the exponent to exactly 6111. This preserves
     * the value while bringing the encoding into representable range.
     *
     * Caller must have verified that the coefficient has enough room
     * (digitLen + qExcess <= 34); this function asserts the invariant
     * and produces the clamped encoding.
     */
    private fun finalizeClamping(out: LongArray, formatIsBid: Boolean, sign: Boolean, qExp: Int, dw1: Long, dw0: Long) {
        val qExcess = qExp - 6111
        verify { qExcess > 0 && qExcess <= 34 - calcDigitLen128(dw1, dw0) }
        val pow10Offset = qExcess shl 1
        val y1 = POW10[pow10Offset + 1]
        val y0 = POW10[pow10Offset]
        val pp00Hi = unsignedMulHi(dw0, y0)
        val pp00Lo = dw0 * y0
        val pp10Lo = dw1 * y0
        val pp01Lo = dw0 * y1
        val dw0Scaled = pp00Lo
        val dw1Scaled = pp00Hi + pp10Lo + pp01Lo
        verify { coeffQExpFit(dw1Scaled, dw0Scaled, 6111) }
        setFinite(out, formatIsBid, sign, 6111, dw1Scaled, dw0Scaled)
    }

    /**
     * Test whether a coefficient/exponent pair fits as a canonical
     * encoding: exponent in [-6176, 6111] and coefficient in [0, 10^34).
     *
     * Fast path uses the leading-zero bit count of the high word to
     * cheaply confirm the coefficient has at most 113 bits, in which case
     * it is necessarily less than 10^34 (since 2^113 > 10^34). Slow path
     * compares against the precomputed POW10[34] constant.
     */
    private inline fun coeffQExpFit(dw1: Long, dw0: Long, qExp: Int): Boolean {
        if (qExp < -6176 || qExp > 6111)
            return false
        if (dw1.countLeadingZeroBits() > 128 - 113)
            return true
        val pow10Offset = 34 shl 1
        val maxxHi = POW10[pow10Offset + 1]
        return unsignedLT(dw1, maxxHi) || dw1 == maxxHi && unsignedLT(dw0, POW10[pow10Offset])
    }

    /**
     * Dispatch underflow handling for a value whose exponent is below
     * Emin (-6176). Three cases:
     *
     *  - Truncation needed exceeds the digit count: result is zero
     *    (the value is below half the smallest subnormal).
     *  - Truncation equals the digit count: boundary case where the
     *    leading digit of the coefficient determines rounding.
     *  - Truncation is less than the digit count: representable as a
     *    subnormal; some precision is preserved, with rounding.
     */
    private fun finalizeUnderflowRegion(
        out: LongArray,
        formatIsBid: Boolean,
        sign: Boolean,
        qExp: Int,
        dw1: Long,
        dw0: Long,
        residue: Int
    ) {
        // IEEE 754-2019 7.5 Underflow -- the subnormal region
        verify { qExp < -6176 }
        val truncationNeeded = -6176 - qExp
        val digitLen = calcDigitLen128(dw1, dw0)
        verify { digitLen <= 34 }
        when {
            truncationNeeded > digitLen -> {
                // Result is swamped - becomes zero with roundTiesToEven
                // This is always inexact
                roundAndFinalizeFinite(out, formatIsBid, sign, -6176, 0L, 0L, LT_HALF)
            }

            truncationNeeded == digitLen ->
                finalizeUnderflowBoundary(out, formatIsBid, sign, dw1, dw0, residue)

            else ->
                finalizeSubnormal(out, formatIsBid, sign, qExp, dw1, dw0, residue)
        }
    }

    /**
     * Handle the underflow boundary case where the most-significant digit
     * of the coefficient sits exactly one position to the right of the
     * representable range.
     *
     * The coefficient value itself determines rounding: comparing the
     * coefficient against `(10^digitLen) / 2` yields the residue class.
     * Combined with any incoming residue, this drives a `roundTiesToEven`
     * decision between zero (always even, the default outcome) and 1 ulp
     * (the smallest subnormal, only when total residue is GT_HALF).
     */
    private fun finalizeUnderflowBoundary(
        out: LongArray, formatIsBid: Boolean, sign: Boolean,
        dw1: Long, dw0: Long, residueIn: Int
    ) {
        val digitLen = calcDigitLen128(dw1, dw0)
        verify { digitLen != 0 }
        val scaleResidue = residueFromValuePow10(dw1, dw0, digitLen)
        val totalResidue = residueMerge(scaleResidue, residueIn)
        // roundTiesToEven ...
        // only if we are GT_HALF since otherwise the result is 0 ... which is even
        setFinite(out, formatIsBid, sign, -6176, 0L, if (totalResidue == GT_HALF) 1L else 0L)
    }

    private fun finalizeSubnormal(
        out: LongArray, formatIsBid: Boolean, sign: Boolean, qExpIn: Int,
        dw1In: Long, dw0In: Long, residueIn: Int
    ) {
        val truncationNeeded = -6176 - qExpIn
        verify { truncationNeeded > 0 && truncationNeeded < calcDigitLen128(dw1In, dw0In) }

        val scaleResidue = u128ScaleDownPow10(out, dw1In, dw0In, truncationNeeded)
        val totalResidue = residueMerge(scaleResidue, residueIn)
        var dw1T = out[0]
        var dw0T = out[1]
        var qExpT = -6176

        val roundUp =
            totalResidue == GT_HALF || totalResidue == HALF && (dw0T and 1L) != 0L
        if (roundUp) {
            // apply rounding
            ++dw0T
            dw1T += if (dw0T == 0L) 1L else 0L
            if (dw1T == MAXX_COEFF_34_HI && dw0T == MAXX_COEFF_34_LO) {
                dw1T = MIN_PRECISION_34_COEFF_HI
                dw0T = MIN_PRECISION_34_COEFF_LO
                ++qExpT
            }
        }
        setFinite(out, formatIsBid, sign, qExpT, dw1T, dw0T)
    }


    /**
     * Residue classification for rounding decisions.
     *
     * When discarding low-order digits during truncation to 34 significant
     * digits, the discarded part falls into one of four categories relative
     * to half a ulp of the retained value:
     *
     *  - [EXACT]   — discarded part is zero; no rounding needed
     *  - [LT_HALF] — strictly less than half; round down (toward zero)
     *  - [HALF]    — exactly half; tie-break per active rounding direction
     *  - [GT_HALF] — strictly greater than half; round up (away from zero)
     *
     * For `roundTiesToEven`, [HALF] rounds to the nearest even retained
     * digit; [LT_HALF] and [GT_HALF] are unambiguous; [EXACT] is a no-op.
     */
    private const val EXACT = 0
    private const val LT_HALF = 1
    private const val HALF = 2
    private const val GT_HALF = 3

    /**
     * Lookup table mapping a discarded decimal digit (0..9) to its residue
     * class. Two bits per digit, packed low-to-high:
     *
     *  - 0       → [EXACT]   (discarded digit is zero)
     *  - 1..4    → [LT_HALF] (digit < 5)
     *  - 5       → [HALF]    (digit is exactly 5)
     *  - 6..9    → [GT_HALF] (digit > 5)
     */
    private const val DIGIT_MAP = 0b11_11_11_11_10_01_01_01_01_00

    // ---- DPD declet conversion -------------------------------------------
    //
    // A declet is 10 bits holding three decimal digits, 0..999. This is the
    // one place BID and DPD genuinely differ: BID stores the 34-digit
    // coefficient as a binary integer, DPD stores it as a leading digit plus
    // eleven declets.
    //
    // Both directions are pure boolean arithmetic (Cowlishaw's dpd2bcd and
    // bcd2dpd) rather than lookup tables. Measured on this codebase, the
    // arithmetic compiles to 2,814 bytes against 16,784 for the equivalent
    // shortArrayOf tables -- the JVM has no array-literal constant, so a
    // table becomes per-element initialisation bytecode -- and it allocates
    // nothing at runtime, and behaves identically on every Kotlin target.

    private const val TEN_POW_15 = 1_000_000_000_000_000L
    private const val TEN_POW_18 = 1_000_000_000_000_000_000L

    /**
     * Decode up to seven packed DPD declets into a binary integer.
     *
     * Declets are ten bits each, least significant first. The topmost may be
     * narrower than ten bits, which is how the coefficient's leading digit is
     * carried: 0..9 encodes to itself as a declet.
     */
    private fun binFromDeclets(declets: Long): Long {
        var bin = 0L
        var rest = declets
        var multiplier = 1L
        while (rest != 0L) {
            bin += dpdDecodeDeclet(rest and 0x3FFL) * multiplier
            // The last iteration multiplies past 10^18 and the product is never
            // used, since rest is zero by then, so the wrap is harmless.
            multiplier *= 1000L
            rest = rest ushr 10
        }
        return bin
    }

    /**
     * Encode a binary integer of at most 18 digits into six packed declets,
     * least significant first. The inverse of [binFromDeclets] over that range.
     */
    private fun decletsFromBin(bin: Long): Long {
        verify { bin >= 0L && bin < TEN_POW_18 }
        var rest = bin
        var declets = 0L
        var shift = 0
        while (rest != 0L) {
            // reciprocal-multiply divide by 1000
            val q = unsignedMulHi(rest, 0x020C49BA5E353F7DL) ushr 3
            declets = declets or (dpdEncodeDeclet(rest - (q * 1000L)) shl shift)
            shift += 10
            rest = q
        }
        return declets
    }

    /**
     * Decode a 10-bit DPD declet (bits 9..0) into `0..999`.
     *
     * Mapping: bit9..bit0 as `p q r s t u v w x y`. Accepts the 24
     * non-canonical declets as well as the 1000 canonical ones, per
     * Cowlishaw's dpd2bcd.
     */
    private fun dpdDecodeDeclet(declet: Long): Long {
        verify { declet in 0..1023 }

        if (declet == 0L)
            return 0L

        // Unpack to bits p..y
        val p = (declet ushr 9) and 1
        val q = (declet ushr 8) and 1
        val r = (declet ushr 7) and 1
        val s = (declet ushr 6) and 1
        val t = (declet ushr 5) and 1
        val u = (declet ushr 4) and 1
        val v = (declet ushr 3) and 1
        val w = (declet ushr 2) and 1
        val x = (declet ushr 1) and 1
        val y = declet and 1

        val ns = s xor 1
        val nx = x xor 1
        val nv = v xor 1
        val nw = w xor 1
        val nt = t xor 1

        val a = (v and w) and (ns or t or nx)
        val b = p and (nv or nw or (s and t.inv() and x))
        val c = q and (nv or nw or (s and t.inv() and x))
        val d = r

        val e = v and ((nw and x) or (t.inv() and x) or (s and x))
        val f = (s and (nv or nx)) or (p and ns and t and v and w and x)
        val g = (t and (nv or nx)) or (q and ns and t and w)
        val h = u

        val i = v and ((nw and nx) or (w and x and (s or t)))
        val j = ((nv and w)
                or (s and v and nw and x)
                or (p and w and (nx or (ns and t.inv()))))
        val k = (nv and x) or (t and nw and x) or (q and v and w and (nx or (ns and nt)))
        val m = y

        // Pack BCD digit bits → three digits
        val d2 = (a shl 3) or (b shl 2) or (c shl 1) or d
        val d1 = (e shl 3) or (f shl 2) or (g shl 1) or h
        val d0 = (i shl 3) or (j shl 2) or (k shl 1) or m


        return d2 * 100 + d1 * 10 + d0
    }

    /**
     * Encode `0..999` into a 10-bit DPD declet, per Cowlishaw's bcd2dpd.
     *
     * The inverse of [dpdDecodeDeclet] over canonical declets. Non-canonical
     * declets decode to a value that re-encodes to the canonical declet for
     * that value, so encode(decode(x)) == x only for canonical x.
     */
    private fun dpdEncodeDeclet(n: Long): Long {
        verify { n in 0..999 }

        if (n == 0L)
            return 0L

        // reciprocal-multiply digit extraction (optimized for n <= 999)
        val q10 = (n * 205) ushr 11      // n / 10
        val q100 = (n * 41) ushr 12      // n / 100

        val d2 = q100
        val d1 = q10 - (q100 * 10)
        val d0 = n - (q10 * 10)

        // BCD bits (MSB→LSB: 8,4,2,1)
        val a = (d2 shr 3) and 1
        val b = (d2 shr 2) and 1
        val c = (d2 shr 1) and 1
        val d = d2 and 1

        val e = (d1 shr 3) and 1
        val f = (d1 shr 2) and 1
        val g = (d1 shr 1) and 1
        val h = d1 and 1

        val i = (d0 shr 3) and 1
        val j = (d0 shr 2) and 1
        val k = (d0 shr 1) and 1
        val m = d0 and 1

        val na = a xor 1
        val ne = e xor 1
        val ni = i xor 1

        val p = b or ((a and j) or ((a and f) and i))
        val q = c or ((a and k) or ((a and g) and i))
        val r = d
        val s = (f and (na or ni)) or ((na and (e and j)) or (e and i))
        val t = g or ((na and (e and k)) or (a and i))
        val u = h
        val v = a or (e or i)
        val w = a or ((e and i) or (ne and j))
        val x = e or ((a and i) or (na and k))
        val y = m

        return (p shl 9) or (q shl 8) or (r shl 7) or (s shl 6) or
                (t shl 5) or (u shl 4) or (v shl 3) or (w shl 2) or
                (x shl 1) or y
    }

    /**
     * Classify a single discarded decimal digit (0..9) as [EXACT],
     * [LT_HALF], [HALF], or [GT_HALF] via lookup in [DIGIT_MAP].
     */
    private fun residueFromDecimalDigit(digit: Int): Int = (DIGIT_MAP shr (digit shl 1)) and 0x03

    /**
     * Combine a primary [roundResidue] with a [stickyResidue] from
     * less-significant digits, in the **round-bit-and-sticky-bit** style.
     * The sticky residue is collapsed to a single "nonzero" bit and OR'd
     * into the low bit of the round residue.
     */
    private fun residueMerge(roundResidue: Int, stickyResidue: Int): Int {
        val s = (stickyResidue and 1) or (stickyResidue ushr 1)
        val r = (roundResidue or s) and 0x03
        return r
    }

    /**
     * Classify a 128-bit unsigned value relative to half of `10^pow10`,
     * returning one of [EXACT], [LT_HALF], [HALF], or [GT_HALF].
     *
     * Used in the underflow boundary case where the entire coefficient is
     * being discarded by truncation: the residue is determined by where
     * the coefficient falls relative to half-of-the-truncation-divisor.
     *
     * Looks up `10^pow10` in the [POW10] table, halves it via a 128-bit
     * shift right by 1, and compares against the input as an unsigned
     * 128-bit value.
     */
    private fun residueFromValuePow10(dw1: Long, dw0: Long, pow10: Int): Int {
        if ((dw1 or dw0) == 0L)
            return EXACT
        val pow10Index = pow10 shl 1
        val pow10Hi = POW10[pow10Index + 1]
        val pow10Lo = POW10[pow10Index]

        val halfHi = pow10Hi ushr 1
        val halfLo = (pow10Hi shl 63) or (pow10Lo ushr 1)
        verify { dw1 >= 0 && halfHi >= 0 }
        if (dw1 != halfHi) {
            return if (dw1 < halfHi) LT_HALF else GT_HALF
        }
        if (dw0 != halfLo)
            return if (unsignedLT(dw0, halfLo)) LT_HALF else GT_HALF
        return HALF
    }

    /**
     * Powers of 10 from `10^0` through `10^34` as 128-bit unsigned values
     * in little-endian order: `POW10[2*i]` is the low 64 bits, `POW10[2*i + 1]`
     * the high 64 bits, of `10^i`.
     *
     * Sized to the coefficient bound and indexed directly -- no mask, no
     * padding. Every reachable index is at most 34: the binding case is
     * [calcDigitLen128], whose input is always a coefficient or NaN payload
     * below `10^34`, so `bitLen <= 113` and `(bitLen * 1233) ushr 12 <= 34`.
     * It doubles as the `5^n` source for the divide kernel, since
     * `10^n ushr n` is exactly `5^n`; there is no POW5 table.
     */
    private val POW10 = longArrayOf(
        // little-endian order
        1L, 0L,                            // 1 (0)
        0x000000000000000AuL.toLong(), 0L, // 10 (10**1)
        0x0000000000000064uL.toLong(), 0L, // 100 (10**2)
        0x00000000000003E8uL.toLong(), 0L, // 1000 (10**3)
        0x0000000000002710uL.toLong(), 0L, // 10000 (10**4)
        0x00000000000186A0uL.toLong(), 0L, // 100000 (10**5)
        0x00000000000F4240uL.toLong(), 0L, // 1000000 (10**6)
        0x0000000000989680uL.toLong(), 0L, // 10000000 (10**7)
        0x0000000005F5E100uL.toLong(), 0L, // 100000000 (10**8)
        0x000000003B9ACA00uL.toLong(), 0L, // 1000000000 (10**9)
        0x00000002540BE400uL.toLong(), 0L, // 10000000000 (10**10)
        0x000000174876E800uL.toLong(), 0L, // 100000000000 (10**11)
        0x000000E8D4A51000uL.toLong(), 0L, // 1000000000000 (10**12)
        0x000009184E72A000uL.toLong(), 0L, // 10000000000000 (10**13)
        0x00005AF3107A4000uL.toLong(), 0L, // 100000000000000 (10**14)
        0x00038D7EA4C68000uL.toLong(), 0L, // 1000000000000000 (10**15)
        0x002386F26FC10000uL.toLong(), 0L, // 10000000000000000 (10**16)
        0x016345785D8A0000uL.toLong(), 0L, // 100000000000000000 (10**17)
        0x0DE0B6B3A7640000uL.toLong(), 0L, // 1000000000000000000 (10**18)
        0x8AC7230489E80000uL.toLong(), 0L, // 10000000000000000000 (10**19)
        // minBitCount:64  maxBitCount:128
        0x6BC75E2D63100000uL.toLong(), 0x0000000000000005uL.toLong(), // 10**20
        0x35C9ADC5DEA00000uL.toLong(), 0x0000000000000036uL.toLong(), // 10**21
        0x19E0C9BAB2400000uL.toLong(), 0x000000000000021EuL.toLong(), // 10**22
        0x02C7E14AF6800000uL.toLong(), 0x000000000000152DuL.toLong(), // 10**23
        0x1BCECCEDA1000000uL.toLong(), 0x000000000000D3C2uL.toLong(), // 10**24
        0x161401484A000000uL.toLong(), 0x0000000000084595uL.toLong(), // 10**25
        0xDCC80CD2E4000000uL.toLong(), 0x000000000052B7D2uL.toLong(), // 10**26
        0x9FD0803CE8000000uL.toLong(), 0x00000000033B2E3CuL.toLong(), // 10**27
        0x3E25026110000000uL.toLong(), 0x00000000204FCE5EuL.toLong(), // 10**28
        0x6D7217CAA0000000uL.toLong(), 0x00000001431E0FAEuL.toLong(), // 10**29
        0x4674EDEA40000000uL.toLong(), 0x0000000C9F2C9CD0uL.toLong(), // 10**30
        0xC0914B2680000000uL.toLong(), 0x0000007E37BE2022uL.toLong(), // 10**31
        0x85ACEF8100000000uL.toLong(), 0x000004EE2D6D415BuL.toLong(), // 10**32
        0x38C15B0A00000000uL.toLong(), 0x0000314DC6448D93uL.toLong(), // 10**33
        0x378D8E6400000000uL.toLong(), 0x0001ED09BEAD87C0uL.toLong(), // 10**34
    )

    /**
     * Compute the bit-length of a 128-bit unsigned value: the position of
     * the highest set bit, plus one. Returns 0 for the zero value.
     */
    private inline fun calcBitLen128(dw1: Long, dw0: Long): Int {
        return if (dw1 != 0L) 128 - dw1.countLeadingZeroBits()
        else if (dw0 != 0L) 64 - dw0.countLeadingZeroBits()
        else 0
    }

    /**
     * Compute the decimal digit count of a 128-bit unsigned value.
     *
     * Uses the standard `log10/log2` approximation: for any non-zero
     * `bitLen`, `(bitLen * 1233) >>> 12` is `floor(bitLen * log10(2))`,
     * which is either the exact digit count or one too small. A single
     * unsigned 128-bit compare against the next-larger power of 10 in
     * [POW10] resolves the off-by-one.
     */
    private inline fun calcDigitLen128(dw1: Long, dw0: Long): Int {
        val bitLen = calcBitLen128(dw1, dw0)
        val loDigitCount = (bitLen * 1233) ushr 12
        val hiDigitCount = loDigitCount + 1
        val pow10Offset = (loDigitCount shl 1)
        val p1 = POW10[pow10Offset + 1]
        val p0 = POW10[pow10Offset]
        val cmp1 = unsignedCmp(dw1, p1)
        if (cmp1 != 0)
            return hiDigitCount - (cmp1 ushr 31)
        val cmp0 = unsignedCmp(dw0, p0)
        return hiDigitCount - (cmp0 ushr 31)
    }

    // -------- low level unsigned arithmetic functions --------------------------------------

    /**
     * Unsigned compare of two `Long` values, by flipping the sign bit
     * before doing a signed compare.
     */
    private inline fun unsignedCmp(x: Long, y: Long): Int =
        (x xor Long.MIN_VALUE).compareTo(y xor Long.MIN_VALUE)

    /**
     * Unsigned less-than test for two `Long` values.
     */
    private inline fun unsignedLT(x: Long, y: Long): Boolean =
        (x xor Long.MIN_VALUE) < (y xor Long.MIN_VALUE)

    /**
     * Compute the high 64 bits of the 128-bit unsigned product `x * y`.
     *
     * Splits each operand into 32-bit halves, computes four 32×32 → 64
     * partial products, and assembles the high 64 bits with proper carry
     * propagation through the middle.
     */
    private inline fun unsignedMulHi(x: Long, y: Long): Long {
        val xLo = x and 0xFFFFFFFFL
        val xHi = x ushr 32
        val yLo = y and 0xFFFFFFFFL
        val yHi = y ushr 32

        val pp00 = xLo * yLo
        val pp01 = xHi * yLo
        val pp10 = xLo * yHi
        val pp11 = xHi * yHi

        val mid = pp01 + pp10  // may overflow
        val midCarryShifted = if (unsignedLT(mid, pp01)) (1L shl 32) else 0L

        val midWithLo = (pp00 ushr 32) + (mid and 0xFFFFFFFFL)
        // midWithLo cannot overflow: max is (2^32-1) + (2^32-1) < 2^33

        return pp11 + (mid ushr 32) + midCarryShifted + (midWithLo ushr 32)
    }

    /**
     * Multiply a 128-bit unsigned value by `10^pow10` and add a 64-bit integer `a`,
     * writing the 128-bit result into `out[0]` (high) and `out[1]` (low).
     */
    private inline fun u128FmaPow10(out: LongArray, x: Long, pow10: Int, a: Long) {
        val y = POW10[pow10 shl 1]
        val prodLo = x * y
        val prodHi = unsignedMulHi(x, y)
        val sumLo = prodLo + a
        val sumHi = prodHi + if (unsignedLT(sumLo, prodLo)) 1L else 0L
        out[0] = sumHi
        out[1] = sumLo
    }

    /**
     * Divide a 128-bit unsigned coefficient by `10^precisionTruncationNeeded`,
     * returning the quotient via [out] and the round-and-sticky
     * residue class as the return value. Truncates in chunks of up to 9
     * digits via [u113DivModPow10Max9].
     */
    private fun u128ScaleDownPow10(out: LongArray, dw1: Long, dw0: Long, precisionTruncationNeeded: Int): Int {
        verify { (dw1 or dw0) != 0L }
        verify { precisionTruncationNeeded > 0 && precisionTruncationNeeded < calcDigitLen128(dw1, dw0) }
        var scaleResidue = EXACT
        var dw1T = dw1
        var dw0T = dw0
        var digitsRemaining = precisionTruncationNeeded
        do {
            val stepPow10 = min(digitsRemaining, 9)
            val rem = u113DivModPow10Max9(out, dw1T, dw0T, stepPow10)
            dw1T = out[0]
            dw0T = out[1]
            val stepResidue = residueFromValuePow10(0L, rem, stepPow10)
            scaleResidue = residueMerge(stepResidue, scaleResidue)
            digitsRemaining -= stepPow10
        } while (digitsRemaining > 0)
        return scaleResidue
    }

    /**
     * Divide a 113-bit unsigned coefficient by `10^pow10` for `pow10` in
     * `1..9`. Factors `10^pow10` as `2^pow10 * 5^pow10`: the low `pow10`
     * bits are extracted directly as the low part of the remainder, then
     * the shifted value is divided by `5^pow10` using two hardware
     * `Long` divides.
     */
    private fun u113DivModPow10Max9(quotLongs: LongArray, dwHi: Long, dwLo: Long, pow10: Int): Long {
        verify { pow10 in 1..9 }
        verify { dwHi.countLeadingZeroBits() >= 15 }

        val mask = (1L shl pow10) - 1
        val b = dwLo and mask
        val sHi = dwHi ushr pow10
        val sLo = (dwLo ushr pow10) or (dwHi shl (64 - pow10))

        val k = 50 - pow10
        val maskK = (1L shl k) - 1
        val hi63 = (sHi shl (14 + pow10)) or (sLo ushr k)
        val loK = sLo and maskK
        // remember ... this POW10 table has 2 slots per power, so double the index
        val d = POW10[pow10 shl 1] ushr pow10     // = 5^n
        val q1 = hi63 / d
        val r1 = hi63 % d
        val mid = (r1 shl k) or loK
        val q0 = mid / d
        val r0 = mid % d

        val qLo = (q1 shl k) + q0
        val qHi = q1 ushr (14 + pow10)
        val remainder = (r0 shl pow10) or b

        quotLongs[0] = qHi
        quotLongs[1] = qLo
        return remainder
    }

}
