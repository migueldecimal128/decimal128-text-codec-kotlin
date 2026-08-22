// SPDX-License-Identifier: MIT
@file:Suppress("NOTHING_TO_INLINE")

package com.decimal128.textcodec

import kotlin.math.max
import kotlin.math.min

/**
 * Thin codec for converting between BID128 binary representations and
 * decimal string form, per IEEE 754-2019 decimal floating point.
 *
 * IEEE 754-2019 specifies two interchange formats for decimal128. This
 * codec implements only the BID (Binary Integer Decimal) format. The
 * alternative DPD (Densely Packed Decimal) format is not supported.
 *
 * BID128 values are represented in this API as a pair of 64-bit longs:
 * `dw1` holds the high 64 bits of the encoding, `dw0` the low 64 bits.
 * Together they form a 128-bit value containing a sign bit, a biased
 * exponent, and a 113-bit binary coefficient that represents up to 34
 * decimal digits.
 *
 * This object provides no arithmetic, no field extraction, and no
 * value-type wrapper — only string conversion in both directions. For
 * the full IEEE 754-2019 decimal128 implementation (arithmetic,
 * comparison, all rounding-direction attributes, IEEE status flags,
 * etc.), see the `decimal128-kotlin` artifact at https://decimal128.com.
 *
 * Rounding, when required, uses the IEEE 754-2019 `roundTiesToEven`
 * rounding-direction attribute. Other rounding directions are not
 * configurable; callers requiring different rounding behavior should
 * use the full `decimal128-kotlin` library.
 *
 * ## Implementation
 *
 * The implementation is fully self-contained in a single file and runs
 * on Kotlin Multiplatform with no external runtime dependencies.
 *
 * ## Round-trip behavior
 *
 * Canonical inputs round-trip exactly: `bid128Parse` followed by
 * `bid128ToString` produces the original string, and `bid128ToString` followed by
 * `bid128Parse` produces the original bits. Three classes of input
 * do not round-trip:
 *
 *  - **Strings with more than 34 significant digits** are accepted
 *    and rounded to 34 digits using `roundTiesToEven`. The rounded
 *    value is encoded; the original input string is not preserved.
 *
 *  - **Strings whose magnitude exceeds the representable range**
 *    overflow to `Infinity` (or `-Infinity`) per IEEE 754-2019.
 *    Strings whose magnitude is below the smallest representable
 *    subnormal underflow to zero.
 *
 *  - **Non-canonical bit patterns** (those whose coefficient field
 *    decodes to 10^34 or greater) decode to a string representation
 *    of zero at the encoded exponent. Re-parsing that string produces
 *    the canonical zero encoding for that exponent, which differs
 *    from the original non-canonical bit pattern. See the next
 *    section for details on non-canonical handling.
 *
 * ## Non-canonical bit patterns
 *
 * Not all 128-bit patterns represent legal BID128 values. A bit pattern
 * whose coefficient field decodes to 10^34 or greater is non-canonical;
 * IEEE 754-2019 specifies that such patterns are read as zero, with
 * the sign and biased exponent of the encoding preserved. `bid128ToString`
 * implements this rule: a non-canonical encoding produces a string
 * representation of zero at the encoded exponent (e.g., `"0E+215"`),
 * not the literal coefficient value.
 *
 * Non-canonical bit patterns do not arise from `bid128Parse` or
 * any other normal use of the codec; they can only appear when BID128
 * values are obtained from external sources such as network or file
 * data.
 *
 * ## Thread safety
 *
 * This object is stateless and all methods are safe for concurrent use.
 *
 * ## License
 *
 * MIT License.
 */
object Decimal128TextCodec {

    // compile-time flag to control verification of state
    private const val VERIFY_ENABLED: Boolean = true

    private inline fun verify(block: () -> Boolean) {
        if (VERIFY_ENABLED)
            check(block())
    }

    /**
     * Convert a BID128 value to its decimal string representation.
     *
     * The output uses the canonical decimal string form per IEEE 754-2019:
     * scientific notation (e.g., `"1.234E+5"`) when the exponent makes
     * scientific form natural, otherwise plain notation (e.g., `"123.45"`).
     * Cohort information is preserved: trailing zeros that are part of the
     * encoded representation appear in the output (`"1.00"` and `"1.0"` are
     * distinct outputs from distinct cohort members of the same value).
     *
     * Special values produce the strings `"Infinity"`, `"-Infinity"`,
     * `"NaN"`, and `"sNaN"`, optionally with a payload suffix (e.g.,
     * `"NaN123"`) and sign prefix.
     *
     * This method cannot fail. Every 128-bit pattern decodes to a valid
     * decimal string: canonical encodings produce their natural string
     * form; non-canonical encodings (coefficient field ≥ 10^34) produce
     * a string for zero at the encoded exponent, per IEEE 754-2019.
     *
     * @param bid128Hi high 64 bits of the BID128 encoding
     * @param bid128Lo low 64 bits of the BID128 encoding
     * @return the decimal string form of the BID128 value
     */
    fun bid128ToString(bid128Hi: Long, bid128Lo: Long): String {
        return decodeBid128toString(bid128Hi, bid128Lo)
    }

    /**
     * Parse a decimal string into a BID128 value, writing the result into
     * [bid128Longs].
     *
     * On success, `bid128Longs[0]` is set to the high 64 bits (`dw1`) of the
     * encoding, `bid128Longs[1]` is set to the low 64 bits (`dw0`), and this
     * method returns `null`.
     *
     * On failure, `bid128Longs[0]` and `bid128Longs[1]` are set to the
     * canonical positive quiet-NaN bit pattern (`0x7C00000000000000` and
     * `0x0000000000000000`), and this method returns a human-readable error
     * message describing the failure. Writing NaN on failure ensures that
     * a caller who ignores the return value still observes a well-defined
     * IEEE 754 value rather than stale or partial data. The exact wording
     * of error messages is not part of the API contract and may change
     * between versions.
     *
     * Accepted input includes:
     *
     *  - Plain decimal notation: `"0"`, `"-1.5"`, `"123.456"`, `"0.0001"`
     *  - Scientific notation: `"1.5E10"`, `"-2.3e-7"`, `"6.02E+23"`. Both
     *    `E` and `e` are accepted as the exponent separator.
     *  - Special values: `"Infinity"`, `"Inf"`,
     *    `"NaN"`, `"qNaN"`, and `"sNaN"`, optionally with a
     *    leading sign (`"-INF") and a NaN payload (`"NaN123"`, `"-sNaN42"`).
     *    `qNaN` is accepted as a synonym for `NaN`. The `Infinity`,
     *    `Inf`, `NaN`, `qNaN`, and `sNaN` keywords are all matched
     *    case-insensitively.
     *
     * Inputs with more than 34 significant digits are rounded to 34 digits
     * using `roundTiesToEven`. Inputs whose magnitude exceeds the
     * representable range overflow to `Infinity` (or `-Infinity`); inputs
     * smaller than the smallest representable subnormal underflow to zero.
     * These cases are not failures — the method returns `null` and writes
     * the appropriate encoded value.
     *
     * Failures (non-null return) occur when the input is structurally
     * malformed: empty strings, unrecognized characters, missing digits
     * around the decimal point, malformed exponent fields, and similar
     * syntax errors.
     *
     * @param bid128Longs a `LongArray` of size at least 2, into which the
     *                    parsed BID128 value (or NaN, on failure) is written
     * @param str         the decimal string to parse
     * @return `null` on success, or a human-readable error message on failure
     * @throws IllegalArgumentException if `bid128Longs.size < 2`
     */
    fun bid128Parse(bid128Longs: LongArray, str: String): String? {
        require(bid128Longs.size >= 2) { "bid128Longs must have size >= 2, was ${bid128Longs.size}" }
        val errorStr = encodeStringToBid128OrError(bid128Longs, str)
        if (errorStr != null) {
            bid128Longs[0] = 0x7C00000000000000L
            bid128Longs[1] = 0x0000000000000000L
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

    private const val ascii_minus: Byte = 0x2D
    private const val ascii_plus: Byte = 0x2B
    private const val ascii_0: Byte = 0x30
    private const val ascii_dot: Byte = 0x2E
    private const val ascii_E: Byte = 0x45


    /**
     * Decode a BID128 bit pattern to its decimal string form.
     *
     * Dispatches to specialized renderers based on the encoding's class:
     * Infinity, NaN (quiet or signaling, with optional payload), zero
     * (canonical or non-canonical), or finite non-zero. Field extraction
     * follows IEEE 754-2019 §3.5.2.
     */
    private fun decodeBid128toString(bid128Hi: Long, bid128Lo: Long): String {
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
                // w+5=13 w=8 Gw+1=G9 Gw+2=G10
                // the top 10 bits of the combination field represent the biased exponent ...
                val biasedExponentE = combination shr 3
                qExp = biasedExponentE - bias
                // ... and the bottom 3 bits are the most significant bits of the significand
                // coeff/significant is 3 + 50 == 53 ... but that's not all ... see below
                coeffHi = (combination and 0x07).toLong()
            }

            combination shr (w5 - 4) != 0b1111 -> {
                // IEEE754-2019 3.5.2 c) 2) ii) -- p 21
                //  If G0 and G1 together are 11 and G2 and G3 together are one
                //  of 00, 01, or 10, then the biased exponent E is formed from
                //  G2 through Gw+3 and the significand is formed by prefixing
                //  the 4 bits (8 + Gw+4) to T.
                //
                // w+5=13 w=8 Gw+3=G11 Gw+4=G12
                //
                val biasedExponentE = (combination ushr 1) and ((1 shl (w5 - 5 + 2)) - 1)
                qExp = biasedExponentE - bias
                coeffHi = 8L + (combination and 1).toLong()
            }
            // if the top 5 bits are 0b11110 then Infinity
            (combination shr (w5 - 5)) == 0b11110 -> {
                return infString(sign)
            }
            // if the top 5 bits are 0x11111 then NaN
            (combination shr (w5 - 5)) == 0b11111 -> {
                // with the next bit determining signaling NaN
                val payloadHi = coeffTHi
                val payloadLo = bid128Lo
                val isSignaling = ((combination shr (w5 - 6)) and 1) != 0
                return nanString(sign, isSignaling, payloadHi, payloadLo)
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
            return zerString(sign, qExp)
        }
        return fnzString(sign, qExp, dw1, dw0)
    }

    private fun infString(sign: Boolean) =
        if (sign) "-Infinity" else "Infinity"

    private fun nanString(
        sign: Boolean, isSignaling: Boolean,
        payloadHi: Long, payloadLo: Long
    ): String {
        val base =
            if (!isSignaling)
                if (sign) "-NaN" else "NaN"
            else
                if (sign) "-sNaN" else "sNaN"
        if ((payloadHi or payloadLo) == 0L ||
            // non canonical IEEE 754-2019 3.5.2 c) 2)
            unsignedLT(MAX_PAYLOAD_HI, payloadHi) ||
            payloadHi == MAX_PAYLOAD_HI && unsignedLT(MAX_PAYLOAD_LO, payloadLo)
        )
            return base
        val digitLen = calcDigitLen128(payloadHi, payloadLo)
        val ascii = ByteArray(base.length + digitLen)
        for (i in 0..<base.length)
            ascii[i] = base[i].code.toByte()
        u128ToAscii(digitLen, payloadHi, payloadLo, ascii, base.length)
        return ascii.decodeToString()
    }

    private val ZEROS = arrayOf(
        "0", "0.0", "0.00", "0.000", "0.0000", "0.00000", "0.000000", "",
        "-0", "-0.0", "-0.00", "-0.000", "-0.0000", "-0.00000", "-0.000000", "",
    )

    private fun zerString(sign: Boolean, qExp: Int): String {
        if (qExp <= 0 && qExp >= -6) {
            val signBit = if (sign) 1 else 0
            val index = -qExp + (signBit shl 3)
            return ZEROS[index and 0x0F]
        }
        val base = if (qExp > 0) {
            if (sign) "-0E+" else "0E+"
        } else {
            if (sign) "-0E" else "0E"
        }
        return base + qExp.toString()
    }

    /**
     * Format a finite non-zero BID128 value, dispatching to one of three
     * string forms based on the quantum exponent and the value's magnitude:
     *
     *  - integer notation when `qExp == 0`
     *  - fixed-point notation with a decimal point when the value would
     *    naturally express with at most 6 leading zeros after the point
     *  - normalized scientific notation otherwise
     *
     * The 6-zero threshold matches the IEEE 754-2019 recommendation for
     * preferring fixed-point form near unity over scientific form.
     */
    private fun fnzString(sign: Boolean, qExp: Int, dw1: Long, dw0: Long): String {
        val digitLen = calcDigitLen128(dw1, dw0)
        val eExp = qExp + (digitLen - 1)
        return when {
            qExp == 0 -> fnzToIntegerString(sign, dw1, dw0)
            qExp <= 0 && eExp >= -6 -> fnzToDecimalPointString(sign, qExp, digitLen, dw1, dw0)
            else -> fnzToNormalizedScientificString(sign, qExp, digitLen, dw1, dw0)
        }
    }

    /**
     * Format a finite non-zero BID128 value with `qExp == 0` as a plain
     * integer string (no decimal point, no exponent).
     *
     * Fast path: when the value fits in a non-negative `Long`, defers to
     * `Long.toString`. General path: renders via [u128ToAscii] into a byte
     * buffer sized exactly to the result.
     */
    private fun fnzToIntegerString(sign: Boolean, dw1: Long, dw0: Long): String {
        if (dw1 == 0L && dw0 >= 0L) {
            return (if (sign) -dw0 else dw0).toString()
        }
        val digitLen = calcDigitLen128(dw1, dw0)
        val printDigitLen = digitLen + 1 - (-digitLen ushr 31)
        val signBit = if (sign) 1 else 0
        val ascii = ByteArray(signBit + printDigitLen)
        ascii[0] = ascii_minus
        u128ToAscii(printDigitLen, dw1, dw0, ascii, signBit)
        return ascii.decodeToString()
    }

    /**
     * Format a finite non-zero BID128 value in fixed-point notation, with
     * an explicit decimal point and the appropriate number of leading
     * zeros (e.g., `"0.00012345"`).
     *
     * Renders the coefficient digits, then shifts the trailing digits
     * right by one position to insert the decimal point. Leading zeros
     * are written directly into the buffer before the digit rendering.
     */
    private fun fnzToDecimalPointString(
        sign: Boolean, qExp: Int,
        digitLen: Int, dw1: Long, dw0: Long
    ): String {
        val digitsRightOfDecimal = -qExp
        val leadingZeroCount = max(1 + digitsRightOfDecimal - digitLen, 0)
        val signLen = if (sign) 1 else 0
        val decimalPointLen = 1
        val totalLen = signLen + leadingZeroCount + decimalPointLen + digitLen
        val ascii = ByteArray(totalLen)
        ascii[0] = ascii_minus
        for (i in signLen..leadingZeroCount) // there is one extra here
            ascii[i] = ascii_0
        u128ToAscii(digitLen, dw1, dw0, ascii, signLen + leadingZeroCount)
        for (i in totalLen - 1 downTo totalLen - digitsRightOfDecimal)
            ascii[i] = ascii[i - 1]
        ascii[totalLen - digitsRightOfDecimal - 1] = ascii_dot
        return ascii.decodeToString()
    }

    /**
     * Format a finite non-zero BID128 value in normalized scientific
     * notation (e.g., `"1.234E+5"`, `"-9.99E-200"`).
     *
     * Renders the coefficient digits, inserts a decimal point after the
     * leading digit (when there's more than one digit), then appends `E`,
     * the exponent sign, and the absolute exponent value. The sign is
     * always written: `+` for non-negative exponents, `-` for negative,
     * per the IEEE 754-2019 / GDAS `to-scientific-string` form.
     */
    private fun fnzToNormalizedScientificString(
        sign: Boolean, qExp: Int,
        digitLen: Int, dw1: Long, dw0: Long
    ): String {
        verify { digitLen > 0 }
        val eExp = qExp + (digitLen - 1)
        val eExpAbs = (eExp xor (eExp shr 31)) - (eExp shr 31)
        val signLen = if (sign) 1 else 0
        val decimalPointLen = if (digitLen > 1) 1 else 0
        val printedDigitLen = digitLen + 1 - (-digitLen ushr 31)
        val expELen = 1
        val expSignLen = 1
        val expSignByte = if (eExp < 0) ascii_minus else ascii_plus
        val expDigitLen = max(calcDigitLen128(0L, eExpAbs.toLong()), 1)
        val totalLen = signLen + decimalPointLen + printedDigitLen + expELen + expSignLen + expDigitLen
        val ascii = ByteArray(totalLen)
        ascii[0] = ascii_minus
        u128ToAscii(printedDigitLen, dw1, dw0, ascii, signLen + decimalPointLen)
        if (decimalPointLen > 0) {
            ascii[signLen] = ascii[signLen + 1]
            ascii[signLen + 1] = ascii_dot
        }
        val iE = signLen + decimalPointLen + printedDigitLen
        ascii[iE] = ascii_E
        ascii[iE + 1] = expSignByte
        renderTailDigitsBeforeIndex(expDigitLen, eExpAbs.toLong(), ascii, totalLen)
        return ascii.decodeToString()

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
            val divTen = (t * 0xCCCCCCCDL) ushr 35
            val digit = (t - (divTen * 10L)).toInt()
            ascii[--ib] = ('0'.code + digit).toByte()
            t = divTen
            --remainingDigitCount
        }
        verify { offMaxx - ib == renderDigitCount }
    }

    // -- encode bid128 from String --------------------------------------------------------

    /**
     * Top-level dispatcher for parsing a decimal string into a BID128
     * encoding. Reads optional sign, then dispatches by leading character
     * to the finite-value, infinity, or NaN parser.
     *
     * The leading character is also passed through to the dispatched
     * parser so it doesn't have to re-read what the dispatcher already
     * consumed.
     *
     * @return `null` on success (with `bid128Longs` populated), or a
     *         human-readable error string on failure
     */
    private fun encodeStringToBid128OrError(bid128Longs: LongArray, str: String): String? {
        val txt = StringIterator(str)
        val chFirst = txt.nextChar()
        var ch = chFirst
        val sign = ch == '-'
        if (ch == '+' || ch == '-')
            ch = txt.nextChar()
        if (ch >= '0' && ch <= '9' || ch == '.')
            return parseFiniteValueText(bid128Longs, sign, ch, txt)
        val chLower = (ch.code or 0x20).toChar()
        if (chLower == 'i')
            return parseInfinityText(bid128Longs, sign, ch, txt)
        if (chLower == 'n' || chLower == 's' || chLower == 'q')
            return parseNanText(bid128Longs, sign, ch, txt)
        if (chFirst == '\u0000')
            return "empty string"
        return "invalid format"
    }

    /**
     * Forward-only character iterator over a `String`.
     *
     * Returns `'\u0000'` (null character) at end-of-string, used as a
     * sentinel by callers to detect the end without the need for
     * multiple scattered length checks.
     * The choice of `'\u0000'` is safe for parsing decimal numerals because
     * NUL is not a valid character in any of the accepted input formats.
     */
    private class StringIterator(var str: String) {
        private var i = 0

        /** Returns the next character and advances the iterator, or '\u0000' if at end. */
        fun nextChar(): Char = if (i < str.length) str[i++] else '\u0000'

    }

    /**
     * Parse the keyword `Infinity` (or its 3-letter abbreviation `Inf`),
     * case-insensitively, and write the corresponding BID128 encoding into
     * [bid128Longs]. Returns an error string for any other input.
     */
    private fun parseInfinityText(bid128Longs: LongArray, sign: Boolean, chFirst: Char, txt: StringIterator): String? {
        var ch = chFirst
        var chPrevCode = 0
        for (target in "infinity") {
            if (ch.code or 0x20 != target.code) {
                if (ch.code == 0)
                    break
                else
                    return "failure parsing infinity"
            }
            chPrevCode = ch.code or 0x20
            ch = txt.nextChar()
        }
        if (ch.code != 0 || (chPrevCode != 'f'.code && chPrevCode != 'y'.code))
            return "failure parsing Infinity"
        bid128Longs[0] = 0x7800000000000000 or if (sign) Long.MIN_VALUE else 0
        bid128Longs[1] = 0L
        return null
    }

    /**
     * Parse `NaN`, `qNaN`, or `sNaN` (case-insensitive) with an optional
     * decimal payload, and write the corresponding BID128 encoding into
     * [bid128Longs]. Payloads may be wrapped in `()`, `[]`, or `{}`.
     * Payloads of 34+ significant digits are non-canonical and produce a
     * zero payload per IEEE 754-2019 §3.5.2.
     */
    private fun parseNanText(bid128Longs: LongArray, sign: Boolean, chFirst: Char, txt: StringIterator): String? {
        var ch = chFirst
        val hasS = (ch.code or 0x20) == 's'.code
        val hasQ = (ch.code or 0x20) == 'q'.code
        if (hasQ or hasS)
            ch = txt.nextChar()
        if (((ch.code or 0x20) != 'n'.code) ||
            ((txt.nextChar().code or 0x20) != 'a'.code) ||
            ((txt.nextChar().code or 0x20) != 'n'.code)
        )
            return "failure parsing NaN"
        var payloadDw0 = 0L
        var payloadDw1 = 0L
        ch = txt.nextChar()
        if (ch.code != 0) {
            var accumDigitCount = 0
            var accum19a = 0L
            var accum19b = 0L
            do {
                if (ch >= '0' && ch <= '9') {
                    val d = ch - '0'
                    // flush leading zeros from payload ... don't increment
                    accumDigitCount += (-(accumDigitCount or d)) ushr 31
                    if (accumDigitCount <= 19)
                        accum19a = (accum19a * 10L) + d.toLong()
                    else
                        accum19b = (accum19b * 10L) + d.toLong()
                } else {
                    if (ch != '(' && ch != ')' &&
                        ch != '[' && ch != ']' &&
                        ch != '{' && ch != '}'
                    )
                        return "non-digit NaN payload character"
                }
                ch = txt.nextChar()
            } while (ch.code != 0)
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
                    val (hi, lo) = u128FmaPow10(accum19a, pow10, accum19b)
                    payloadDw1 = hi
                    payloadDw0 = lo
                }
            }
        }
        val withoutPayload =
            (if (hasS) 0x7E00000000000000 else 0x7C00000000000000) or (if (sign) Long.MIN_VALUE else 0L)
        bid128Longs[0] = withoutPayload or payloadDw1
        bid128Longs[1] = payloadDw0
        return null
    }

    /**
     * Parse a finite decimal numeral and write the corresponding BID128
     * encoding into [bid128Longs]. Accepts an optional decimal point, an
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
        bid128Longs: LongArray,
        sign: Boolean,
        chFirst: Char,
        txt: StringIterator
    ): String? {
        var residue = EXACT

        var hasCoefficientDigit = false // have we seen any digits at all, including zero
        var significantDigitCount = 0 // does not count leading zeros
        var hasDot = false
        var expSign = false

        verify { chFirst.code != 0 }
        var ch = chFirst
        var fractionalDigitCount = 0
        var accum19a = 0L
        var accum19b = 0L
        var exp = 0

        while (ch in '0'..'9' || ch == '.') {
            when (ch) {
                in '0'..'9' -> {
                    val d = ch - '0'
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
                }

                '.' -> when {
                    hasDot -> return "double radix dot"
                    else -> hasDot = true
                }
            }
            ch = txt.nextChar()
        }
        if (!hasCoefficientDigit)
            return "no coefficient digit"
        // this path has at least one digit
        if (ch == 'E' || ch == 'e') {
            ch = txt.nextChar()
            if (ch == '+' || ch == '-') {
                expSign = ch == '-'
                ch = txt.nextChar()
            }
            var hasExpDigit = false
            var expSignificantDigitCount = 0
            while (ch in '0'..'9') {
                hasExpDigit = true
                val eDigit = ch - '0'
                // count while flushing leading zeros
                expSignificantDigitCount +=
                    (-(expSignificantDigitCount or eDigit)) ushr 31
                exp = exp * 10 + eDigit
                ch = txt.nextChar()
            }
            if (!hasExpDigit)
                return "no exponent digit"
            // clamp exp to 9999 once after the loop
            if (expSignificantDigitCount > 4)
                exp = 9999
        }
        if (ch.code != 0)
            return "unexpected char"
        // we have at least one digit
        var dw0 = accum19a
        var dw1 = 0L
        if (significantDigitCount > 19) {
            val pow10 = min(34, significantDigitCount) - 19
            val (hi, lo) = u128FmaPow10(accum19a, pow10, accum19b)
            dw1 = hi
            dw0 = lo
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
        bid128RoundAndFinalizeFinite(bid128Longs, sign, qExp, dw1, dw0, residue)
        return null
    }

    /**
     * Round and finalize a finite parsed coefficient/exponent
     * pair into the BID128 encoding stored in [bid128Longs].
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
     * Step 4 (underflow): if the exponent is below Emin and the required
     * range truncation exceeds the precision truncation, divert to
     * [bid128FinalizeUnderflowRegion] for subnormal handling.
     *
     * Step 5 (precision truncation): if the coefficient exceeds 34 digits,
     * scale down by the necessary power of 10, accumulating the
     * truncation residue with the input residue.
     *
     * Step 6 (rounding): apply `roundTiesToEven`. If rounding causes the
     * coefficient to roll over to 10^34, normalize to 10^33 and increment
     * the exponent.
     *
     * Step 7 (overflow / clamping): if the exponent exceeds Emax, either
     * absorb the excess into the coefficient (clamping near Emax) or
     * overflow to infinity.
     */
    private fun bid128RoundAndFinalizeFinite(
        bid128Longs: LongArray,
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
                bid128Set(bid128Longs, sign, qExpIn, dw1In, dw0In)
                return
            }
            // Step 2: zero coefficient with out-of-range qExp ... clamp qExp
            if ((dw1In or dw0In) == 0L) {
                val qClamped = max(min(qExpIn, 6111), -6176)
                bid128Set(bid128Longs, sign, qClamped, 0L, 0L)
                return
            }
        }

        // Step 3: special values ... not applicable ... only Finite

        // Step 4: underflow
        // divert iff range truncation exceeds precision truncation
        val rangeTruncationNeeded = -6176 - qExpIn
        val digitLenIn = calcDigitLen128(dw1In, dw0In)
        val precisionTruncationNeeded = max(digitLenIn - 34, 0)
        if (rangeTruncationNeeded > precisionTruncationNeeded) {
            bid128FinalizeUnderflowRegion(bid128Longs, sign, qExpIn, dw1In, dw0In, residueIn)
            return
        }

        // Step 5: normalize to <= precision, accumulating residue
        var totalResidue = residueIn
        var dw1 = dw1In
        var dw0 = dw0In
        var qExp = qExpIn
        if (precisionTruncationNeeded > 0) {
            val truncationResidue: Int = u128ScaleDownPow10(bid128Longs, dw1, dw0, precisionTruncationNeeded)
            dw1 = bid128Longs[0]
            dw0 = bid128Longs[1]
            totalResidue = residueMerge(truncationResidue, totalResidue)
            qExp += precisionTruncationNeeded
            verify { calcDigitLen128(dw1, dw0) == 34 }
        }

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
                bid128FinalizeClamping(bid128Longs, sign, qExp, dw1, dw0)
            else
                bid128FinalizeOverflow(bid128Longs, sign)
            return
        }
        bid128Set(bid128Longs, sign, qExp, dw1, dw0)
    }

    /**
     * Pack the sign, biased quantum exponent, and 113-bit coefficient into
     * the BID128 encoding stored in [bid128Longs].
     *
     * Always uses the short-form encoding (combination field top bits not
     * `11`), which is correct for any canonical coefficient whose top 3
     * bits fit in 0..7.
     *
     * Preconditions are checked via [verify] and active under compile-time
     * control: the exponent must be in [-6176, 6111] and the coefficient
     * must have at most 34 decimal digits.
     */
    private fun bid128Set(bid128Longs: LongArray, sign: Boolean, qExp: Int, dw1: Long, dw0: Long) {
        verify { qExp >= -6176 && qExp <= 6111 }
        verify { calcDigitLen128(dw1, dw0) <= 34 }
        val qBiased = qExp + 6176
        val s = if (sign) Long.MIN_VALUE else 0
        val g = qBiased.toLong() shl (3 + 46)
        bid128Longs[0] = s or g or dw1
        bid128Longs[1] = dw0
    }

    /**
     * Encode `Infinity` (or `-Infinity`) into [bid128Longs].
     */
    private fun bid128FinalizeOverflow(bid128Longs: LongArray, sign: Boolean) {
        bid128Longs[0] = (if (sign) Long.MIN_VALUE else 0L) or 0x7800000000000000L
        bid128Longs[1] = 0L
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
    private fun bid128FinalizeClamping(bid128Longs: LongArray, sign: Boolean, qExp: Int, dw1: Long, dw0: Long) {
        val qExcess = qExp - 6111
        verify { qExcess > 0 && qExcess <= 34 - calcDigitLen128(dw1, dw0) }
        val (dw1Scaled, dw0Scaled) = umul128xPow10to128(dw1, dw0, qExcess)
        verify { coeffQExpFit(dw1Scaled, dw0Scaled, 6111) }
        bid128Set(bid128Longs, sign, 6111, dw1Scaled, dw0Scaled)
    }

    /**
     * Test whether a coefficient/exponent pair fits as a canonical BID128
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
    private fun bid128FinalizeUnderflowRegion(
        bid128Longs: LongArray,
        sign: Boolean,
        qExp: Int,
        dw1: Long,
        dw0: Long,
        residue: Int
    ) {
        // IEEE 754 7.5 Underflow - handle subnormal region
        verify { qExp < -6176 }
        val truncationNeeded = -6176 - qExp
        val digitLen = calcDigitLen128(dw1, dw0)
        verify { digitLen <= 34 }
        when {
            truncationNeeded > digitLen -> {
                // Result is swamped - becomes zero with roundTiesToEven
                // This is always inexact
                bid128RoundAndFinalizeFinite(bid128Longs, sign, -6176, 0L, 0L, LT_HALF)
            }

            truncationNeeded == digitLen ->
                bid128FinalizeUnderflowBoundary(bid128Longs, sign, dw1, dw0, residue)

            else ->
                bid128FinalizeSubnormal(bid128Longs, sign, qExp, dw1, dw0, residue)
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
    private fun bid128FinalizeUnderflowBoundary(
        bid128Longs: LongArray, sign: Boolean,
        dw1: Long, dw0: Long, residueIn: Int
    ) {
        val digitLen = calcDigitLen128(dw1, dw0)
        verify { digitLen != 0 }
        val scaleResidue = residueFromValuePow10(dw1, dw0, digitLen)
        val totalResidue = residueMerge(scaleResidue, residueIn)
        // roundTiesToEven ...
        // only if we are GT_HALF since otherwise the result is 0 ... which is even
        bid128Set(bid128Longs, sign, -6176, 0L, if (totalResidue == GT_HALF) 1L else 0L)
    }

    private fun bid128FinalizeSubnormal(
        bid128Longs: LongArray, sign: Boolean, qExpIn: Int,
        dw1In: Long, dw0In: Long, residueIn: Int
    ) {
        val truncationNeeded = -6176 - qExpIn
        verify { truncationNeeded > 0 && truncationNeeded < calcDigitLen128(dw1In, dw0In) }

        val scaleResidue = u128ScaleDownPow10(bid128Longs, dw1In, dw0In, truncationNeeded)
        val totalResidue = residueMerge(scaleResidue, residueIn)
        var dw1T = bid128Longs[0]
        var dw0T = bid128Longs[1]
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
        bid128Set(bid128Longs, sign, qExpT, dw1T, dw0T)
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
     * the high 64 bits, of `10^i`. Sized to the BID128 coefficient bound.
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
     *
     * Branchless: a mask derived from `dw1`'s zero-or-not status decides
     * whether `dw0`'s leading-zero count contributes to the result. Avoids
     * a conditional branch on the path through the function.
     */
    private inline fun calcBitLen128(dw1: Long, dw0: Long): Int {
        val dw1IsZeroMask = ((dw1 or -dw1) shr 63).inv().toInt()
        val nlz1 = dw1.countLeadingZeroBits()
        val nlz0 = dw0.countLeadingZeroBits()
        val bitLen = 128 - nlz1 - (nlz0 and dw1IsZeroMask)
        return bitLen
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
    private fun calcDigitLen128(dw1: Long, dw0: Long): Int {
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
    private fun unsignedCmp(x: Long, y: Long): Int =
        (x xor Long.MIN_VALUE).compareTo(y xor Long.MIN_VALUE)

    /**
     * Unsigned less-than test for two `Long` values.
     */
    private fun unsignedLT(x: Long, y: Long): Boolean =
        (x xor Long.MIN_VALUE) < (y xor Long.MIN_VALUE)

    /**
     * Compute the high 64 bits of the 128-bit unsigned product `x * y`.
     *
     * Splits each operand into 32-bit halves, computes four 32×32 → 64
     * partial products, and assembles the high 64 bits with proper carry
     * propagation through the middle.
     */
    private fun unsignedMulHi(x: Long, y: Long): Long {
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
     * Multiply a 128-bit unsigned value by `10^pow10`, returning the low
     * 128 bits. Caller must ensure the product fits.
     */
    private inline fun umul128xPow10to128(dw1: Long, dw0: Long, pow10: Int): Pair<Long, Long> {
        val pow10Offset = pow10 shl 1
        val pow10Dw1 = POW10[pow10Offset + 1]
        val pow10Dw0 = POW10[pow10Offset]
        return umul128x128to128(dw1, dw0, pow10Dw1, pow10Dw0)
    }

    /**
     * Multiply two 128-bit unsigned values, returning the low 128 bits of
     * the 256-bit product. The `x1*y1` partial product is omitted (it
     * contributes only to bits 128 and above). Caller must ensure the
     * product fits.
     */
    private inline fun umul128x128to128(x1: Long, x0: Long, y1: Long, y0: Long): Pair<Long, Long> {
        val pp00Hi = unsignedMulHi(x0, y0)
        val pp00Lo = x0 * y0
        val pp10Lo = x1 * y0
        val pp01Lo = x0 * y1

        val p0 = pp00Lo
        val p1 = pp00Hi + pp10Lo + pp01Lo

        return p1 to p0
    }

    /**
     * Multiply a 128-bit unsigned value by `10^pow10`, returning the low
     * 128 bits.
     *
     * In the context of parsing with controlled digit counts this
     * will not overflow.
     */
    private fun u128FmaPow10(x: Long, pow10: Int, a: Long): Pair<Long, Long> {
        val y = POW10[pow10 shl 1]
        val prodLo = x * y
        val prodHi = unsignedMulHi(x, y)
        val sumLo = prodLo + a
        val sumHi = prodHi + if (unsignedLT(sumLo, prodLo)) 1L else 0L
        return sumHi to sumLo
    }

    /**
     * Divide a 128-bit unsigned coefficient by `10^precisionTruncationNeeded`,
     * returning the quotient via [bid128Longs] and the round-and-sticky
     * residue class as the return value. Truncates in chunks of up to 9
     * digits via [u113DivModPow10Max9].
     */
    private fun u128ScaleDownPow10(bid128Longs: LongArray, dw1: Long, dw0: Long, precisionTruncationNeeded: Int): Int {
        verify { (dw1 or dw0) != 0L }
        verify { precisionTruncationNeeded > 0 && precisionTruncationNeeded < calcDigitLen128(dw1, dw0) }
        var scaleResidue = EXACT
        var dw1T = dw1
        var dw0T = dw0
        var digitsRemaining = precisionTruncationNeeded
        do {
            val stepPow10 = min(digitsRemaining, 9)
            val rem = u113DivModPow10Max9(bid128Longs, dw1T, dw0T, stepPow10)
            dw1T = bid128Longs[0]
            dw0T = bid128Longs[1]
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
