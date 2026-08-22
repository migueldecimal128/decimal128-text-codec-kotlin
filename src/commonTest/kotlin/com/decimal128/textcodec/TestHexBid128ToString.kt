package com.decimal128.textcodec

import com.decimal128.decimal.Decimal
import com.decimal128.decimal.toDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class TestHexBid128ToString {

    val verbose = false

    data class TC(val hexStr: String, val expected: String)

    val tcs = arrayOf(
        TC("[7E00000000000000,8000000000000000]", "sNaN9223372036854775808"),

// === Zero with various exponents ===
        TC("[0000000000000000,0000000000000000]", "0E-6176"),       // smallest exponent zero
        TC("[3040000000000000,0000000000000000]", "0"),              // exponent 0 zero (canonical "0")
        TC("[5FFE000000000000,0000000000000000]", "0E+6111"),        // largest exponent zero
        TC("[B040000000000000,0000000000000000]", "-0"),             // negative zero, exponent 0

// === Small integers ===
        TC("[3040000000000000,0000000000000001]", "1"),
        TC("[3040000000000000,000000000000000A]", "10"),
        TC("[3040000000000000,0000000000000064]", "100"),
        TC("[B040000000000000,0000000000000001]", "-1"),

// === Cohort members of "1" (same numerical value, different bit patterns) ===
        TC("[303E000000000000,000000000000000A]", "1.0"),            // coefficient 10, exponent -1
        TC("[303C000000000000,0000000000000064]", "1.00"),           // coefficient 100, exponent -2
        TC("[303A000000000000,00000000000003E8]", "1.000"),          // coefficient 1000, exponent -3

        TC("[3040314DC6448D93,38C15B0A00000000]", "1000000000000000000000000000000000"),
// === Maximum coefficient at exponent 0 ===
        TC("[3041ED09BEAD87C0,378D8E63FFFFFFFF]", "9999999999999999999999999999999999"),  // 34 nines

// === Non-canonical coefficient (≥ 10^34) — IEEE 754 says coefficient becomes zero ===
        TC("[6C7B86F26FC10000,0000000000000000]", "0E+215"),
        TC("[7800000000000000,0000000000000000]", "Infinity"),
        TC("[F800000000000000,0000000000000000]", "-Infinity"),
        TC("[7800000000000000,0000000000000001]", "Infinity"),

// === Powers of 10 ===
        TC("[3040000000000000,0000000000000001]", "1"),              // 10^0
        TC("[30C6000000000000,0000000000000001]", "1E+67"),
        TC("[2FB0000000000000,0000000000000001]", "1E-72"),

// === Largest finite values ===
        TC("[5FFFED09BEAD87C0,378D8E63FFFFFFFF]", "9.999999999999999999999999999999999E+6144"),
        TC("[DFFFED09BEAD87C0,378D8E63FFFFFFFF]", "-9.999999999999999999999999999999999E+6144"),
        // roll over
        TC("[5FFFED09BEAD87C1,0000000000000000]", "0E+6111"),
        TC("[5FFFED09BEAD87C0,378D8E6400000000]", "0E+6111"),

// === Smallest positive normal: 1E-6143 ===
        TC("[0042000000000000,0000000000000001]", "1E-6143"),

// === Smallest positive subnormal: 1E-6176 ===
        TC("[0000000000000000,0000000000000001]", "1E-6176"),
        TC("[8000000000000000,0000000000000001]", "-1E-6176"),

// === Mid-range subnormals ===
        TC("[0020000000000000,0000000000000001]", "1E-6160"),
        TC("[003E000000000000,000000000000000A]", "1.0E-6144"),

// === Specials: Infinity ===
        TC("[7800000000000000,0000000000000000]", "Infinity"),       // +Inf, canonical
        TC("[F800000000000000,0000000000000000]", "-Infinity"),      // -Inf

// === Specials: NaN ===
        TC("[7C00000000000000,0000000000000000]", "NaN"),            // quiet NaN, no payload
        TC("[FC00000000000000,0000000000000000]", "-NaN"),           // negative quiet NaN (sign bit set)
        TC("[7C00000000000000,0000000000000001]", "NaN1"),           // payload = 1
        TC("[7E00000000000000,0000000000000000]", "sNaN"),           // signaling NaN
        TC("[7E00000000000000,0000000000000001]", "sNaN1"),
        TC("[7E00000000000000,7FFFFFFFFFFFFFFF]", "sNaN9223372036854775807"),
        TC("[7E00000000000000,8000000000000000]", "sNaN9223372036854775808"),
        TC("[7E00000000000000,FFFFFFFFFFFFFFFF]", "sNaN18446744073709551615"),
        TC("[7C00314DC6448D93,38C15B09FFFFFFFF]", "NaN999999999999999999999999999999999"), // max payload
        TC("[7C00314DC6448D94,38C15B09FFFFFFFF]", "NaN"),            // non-canonical ... c == 0
        TC("[7C00314DC6448D93,38C15B0A00000000]", "NaN"),            // non-canonical ... c == 0

        TC("[20491165061C532A,535089A5C8F9DA39]", "5.545101757363565785893501553728057E-2011"),
        TC("[812C000000000000,0000000000000000]", "-0E-6026"),
        TC("[2FFCA45894E48295,67D9DA2155555555]", "0.3333333333333333333333333333333333"),
        TC("[a9481e81f1ac7df5,96dcd9baa6738f4a]", "-6.18767515491324263605526166998858E-860"),
        TC("[AFFDB28CFC8BFD25,2B61B866EF8F5F22]", "-0.8813735870195430252326093249797922"),
        )

    @Test
    fun testCases() {
        for (tc in tcs)
            test(tc)
    }

    fun test(tc: TC) {
        if (verbose)
            println(tc)
        val dec = tc.expected.toDecimal()
        val decLongs = LongArray(2)
        dec.encodeBid128(decLongs)
        val decBid128Hi = decLongs[0]
        val decBid128Lo = decLongs[1]

        val (bid128Hi, bid128Lo) = bid128ParseIntelHex(tc.hexStr)
        val observedDecimal = Decimal.decodeBid128(bid128Hi, bid128Lo).toString()
        val observed = Decimal128TextCodec.bid128ToString(bid128Hi, bid128Lo)
        if (observed != tc.expected) {
            if (decBid128Hi != bid128Hi || decBid128Lo != bid128Lo) {
                val decHex = "[${hex16(decBid128Hi)},${hex16(decBid128Lo)}]"
                println("""
                    test case says: ${tc.hexStr}
                   observedDecimal: $observedDecimal
                      decimal says: ${tc.expected} => $decHex
                """.trimIndent())
            }
        }
        assertEquals(tc.expected, observed)
    }

    private fun hex16(v: Long): String =
        v.toULong().toString(16).padStart(16, '0').uppercase()

    private fun bid128ParseIntelHex(str: String): Pair<Long, Long> {
        if (str.length !in 34..35 ||
            str[0] != '[' || str[str.lastIndex] != ']' ||
            str.length == 35 && str[17] != ','
        ) {
            throw IllegalArgumentException("Invalid Intel BID Hex string - overall")
        }
        val (bid128Hi, isValidHi) = parseHexDword(str, 1)
        if (! isValidHi)
            throw IllegalArgumentException("Invalid Intel BID Hex string - first dword")
        val (bid128Lo, isValidLo) = parseHexDword(str, (str.length + 1) shr 1)
        if (! isValidLo)
            throw IllegalArgumentException("Invalid Intel BID Hex string - second dword")
        return bid128Hi to bid128Lo
    }

    private fun parseHexDword(str: String, off: Int): Pair<Long, Boolean> {
        var dw = 0L
        for (i in 0..15) {
            val ch = str[off + i]
            dw = when (ch) {
                in '0'..'9' -> (dw shl 4) or (ch - '0').toLong()
                in 'A'..'F' -> (dw shl 4) or (ch - 'A' + 10).toLong()
                in 'a'..'f' -> (dw shl 4) or (ch - 'a' + 10).toLong()
                else -> {
                    return 0L to false
                }
            }
        }
        return dw to true
    }

}