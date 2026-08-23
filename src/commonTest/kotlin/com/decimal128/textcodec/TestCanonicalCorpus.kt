// SPDX-License-Identifier: MIT

package com.decimal128.textcodec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The full canonical table: 84,764 rows, read from the same submodule as the
 * rest. It is a separate class only because it is two orders of magnitude bigger
 * than the other four put together, not because it is optional.
 *
 * These rows were out of reach while the corpus was transcribed into Kotlin
 * source -- at that density they would have been some 7 MB of it.
 */
class TestCanonicalCorpus {

    @Test
    fun canonicalTableDecodesAndReEncodes() {
        val rows = Corpus.load(Corpus.CANONICAL)
        assertEquals(84783, rows.size)
        TestCorpusVectors.runDecodeTable(rows, "canonical")
        TestCorpusVectors.runEncodeTable(rows, "canonical")
    }
}
