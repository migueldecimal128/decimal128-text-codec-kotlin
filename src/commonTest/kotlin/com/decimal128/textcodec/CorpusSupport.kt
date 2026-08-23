// SPDX-License-Identifier: MIT

package com.decimal128.textcodec

import kotlin.test.fail

/**
 * One row of a `text_bid_dpd` table: the text, the two encodings as 32 bare
 * uppercase hex digits, and the `status` column the input-driven tables carry.
 */
class CorpusRow(
    val text: String,
    val bidHex: String,
    val dpdHex: String,
    val status: String,
    val lineNumber: Int,
) {
    val bidHi: Long get() = hi(bidHex)
    val bidLo: Long get() = lo(bidHex)
    val dpdHi: Long get() = hi(dpdHex)
    val dpdLo: Long get() = lo(dpdHex)
}

/** The high word of a 32-digit hex encoding. */
fun hi(h: String): Long = h.substring(0, 16).toULong(16).toLong()

/** The low word of a 32-digit hex encoding. */
fun lo(h: String): Long = h.substring(16, 32).toULong(16).toLong()

/** An encoding rendered the way the corpus spells it: 32 uppercase hex digits. */
fun hex(a: Long, b: Long): String =
    a.toULong().toString(16).padStart(16, '0').uppercase() +
        b.toULong().toString(16).padStart(16, '0').uppercase()

/**
 * The shared conformance corpus, read from `resources/text_bid_dpd` -- the
 * `decimal128-resources` submodule, mounted at the repo root the way every
 * decimal128 port mounts it. Nothing is copied into this repo.
 *
 * These vectors used to be transcribed into Kotlin source, because `commonTest`
 * has no portable file access and a data file would have needed a reader per
 * target. That reader now exists ([loadTestResourceAsString]), so every target
 * reads the tables themselves -- which also brings the 84,764-row `canonical`
 * table within reach, at 10 MB far past what transcription could carry.
 *
 * The row counts are asserted by the tests, and that is not ceremony: the text
 * column of `parse` and `error` is double-quoted with `\\` and `\"` escapes,
 * because those tables must carry the empty string, a leading and a trailing
 * space, an embedded comma, an embedded quote and an embedded backslash. The
 * other three leave it bare. A naive comma split silently corrupts four rows and
 * still passes, since a mangled malformed input fails to parse either way. The
 * row count is what catches it.
 */
object Corpus {

    const val DIRECTORY: String = "/text_bid_dpd"

    const val HUNDRED: String = "text_bid128_dpd128_100"
    const val NON_CANONICAL: String = "text_bid128_dpd128_noncanonical"
    const val PARSE: String = "text_bid128_dpd128_parse"
    const val ERROR: String = "text_bid128_dpd128_error"
    const val CANONICAL: String = "text_bid128_dpd128_canonical"

    /** One table, read through the platform loader. */
    fun load(name: String): List<CorpusRow> {
        val path = DIRECTORY + "/" + name + ".csv.txt"
        val contents = loadTestResourceAsString(path)
            ?: fail(path + " could not be read -- " + RESOURCES_HINT)
        return parseTable(contents, name)
    }

    private fun parseTable(contents: String, name: String): List<CorpusRow> {
        val rows = ArrayList<CorpusRow>()
        var lineNumber = 0
        for (rawLine in contents.split('\n')) {
            ++lineNumber
            var line = rawLine
            while (line.endsWith("\r")) {
                line = line.dropLast(1)
            }
            line = skipSpace(line)
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }

            val text: String
            if (line.startsWith("\"")) {
                val quoted = readQuoted(line)
                text = quoted.first
                line = quoted.second
            } else {
                val bare = readField(line)
                text = bare.first
                line = bare.second
            }
            val bid = readNextField(line)
            val dpd = readNextField(bid.second)
            val status = readNextField(dpd.second)

            val bidHex = normalizeHex(bid.first)
            val dpdHex = normalizeHex(dpd.first)
            if ((bidHex == null) || (dpdHex == null)) {
                fail(name + ".csv.txt line " + lineNumber + ": malformed hex columns <" +
                    bid.first + "> <" + dpd.first + ">")
            }
            rows.add(CorpusRow(text, bidHex, dpdHex, status.first, lineNumber))
        }
        return rows
    }

    /** `0x` + 32 hex digits, or 32 bare ones, normalized to bare uppercase. */
    private fun normalizeHex(field: String): String? {
        val body = if (field.startsWith("0x") || field.startsWith("0X")) field.substring(2) else field
        if (body.length != 32) {
            return null
        }
        for (c in body) {
            if (!((c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F'))) {
                return null
            }
        }
        return body.uppercase()
    }

    // -- the reader, matching the C and Swift ports ---------------------------

    private fun skipSpace(s: String): String {
        var i = 0
        while ((i < s.length) && ((s[i] == ' ') || (s[i] == '\t'))) {
            ++i
        }
        return s.substring(i)
    }

    /**
     * One comma-delimited field, stopping at `,` or `#` or end of line, with
     * surrounding whitespace trimmed. Returns the field and the unconsumed rest.
     */
    private fun readField(s: String): Pair<String, String> {
        val start = skipSpace(s)
        var end = 0
        while ((end < start.length) && (start[end] != ',') && (start[end] != '#')) {
            ++end
        }
        var trim = end
        while ((trim > 0) && ((start[trim - 1] == ' ') || (start[trim - 1] == '\t'))) {
            --trim
        }
        return Pair(start.substring(0, trim), start.substring(end))
    }

    /**
     * Step over a field separator, then read the next field. Yields the empty
     * string when the row has no further column.
     */
    private fun readNextField(s: String): Pair<String, String> {
        val rest = skipSpace(s)
        if (!rest.startsWith(",")) {
            return Pair("", rest)
        }
        return readField(rest.substring(1))
    }

    /** The quoted text column, honouring `\\` and `\"` escapes. */
    private fun readQuoted(s: String): Pair<String, String> {
        val out = StringBuilder()
        var i = 1 // past the opening quote
        while ((i < s.length) && (s[i] != '"')) {
            var c = s[i]
            ++i
            if ((c == '\\') && (i < s.length)) {
                c = s[i]
                ++i
            }
            out.append(c)
        }
        if ((i < s.length) && (s[i] == '"')) {
            ++i
        }
        return Pair(out.toString(), s.substring(i))
    }
}

// ---------------------------------------------------------------------------
// Thin wrappers so the tests read the way the codec's own API does.
// ---------------------------------------------------------------------------

class ParseResult(val hex: String, val error: String?)

fun bidParse(text: String): ParseResult {
    val bits = longArrayOf(0xFEEDL, 0xFACEL)
    val err = Decimal128TextCodec.bid128Parse(bits, text)
    return ParseResult(hex(bits[0], bits[1]), err)
}

fun dpdParse(text: String): ParseResult {
    val bits = longArrayOf(0xFEEDL, 0xFACEL)
    val err = Decimal128TextCodec.dpd128Parse(bits, text)
    return ParseResult(hex(bits[0], bits[1]), err)
}
