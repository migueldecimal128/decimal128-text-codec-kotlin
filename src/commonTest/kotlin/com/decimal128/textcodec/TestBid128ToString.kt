package com.decimal128.textcodec

import com.decimal128.decimal.Decimal
import com.decimal128.decimal.toDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class TestBid128ToString {

    val verbose = false

    // simple test ...
    // put only canonical representation in these test cases
    val tcs = arrayOf(
        "9999999999999999999999999999999999",

        "1234567890123456789012345678901234",
        "Infinity",

        "NaN",
        "NaN1",
        "NaN987654321012345678",

        "sNaN",
        "sNaN123456789012345678901234567890123",

        "0",
        "0.0",
        "0.000000",

        "1",
        "999",
        "1234567890123456789012345678901234",
        "9999999999999999999999999999999999",

        "1.0",
        "1.00",
        "1.000",
        "1.0000",
        "1.00000",
        "1.000000",
        "1.0000000",
        "1.00000000",
        "1.000000000",
        "1.0000000000",
        "1.00000000000",
        "1.000000000000",
        "99.44",
        "0.000006",
        "123456789012345678901234567890.1234",
        "1234567890123456789012345678.901234",
        "999999999999999999999999999999999.9",
        "99999999999999999999999999999999.99",
        "9999999999999999999999999999999.999",
        "999999999999999999999999999999.9999",
        "99999999999999999999999999999.99999",
        "9999999999999999999999999999.999999",

        "6.02E23",
        "9.999999999999999999999999999999999",
        "9.999999999999999999999999999999999E6144",
        "9.999999999999999999999999999999999E-6143",

        "1E-6143",
        // subnormals
        "1E-6144",
        "1E-6176",
        // subnormals cannot go to full precision
        "1.2345678901234568E-6160",
    )

    @Test
    fun testCases() {
        for (tc in tcs)
            test(tc)
    }

    fun test(tc: String) {
        if (verbose)
            println("tc:$tc")
        val dec: Decimal = tc.toDecimal()
        val longs = LongArray(2)
        dec.encodeBid128(longs)
        val observed = Decimal128BidTextCodec.bid128ToString(longs[0], longs[1])
        assertEquals(tc, observed)

        val tcNegated = "-$tc"
        val negated = tcNegated.toDecimal()
        negated.encodeBid128(longs)
        val observedNegated = Decimal128BidTextCodec.bid128ToString(longs[0], longs[1])
        assertEquals(tcNegated, observedNegated)
    }
}