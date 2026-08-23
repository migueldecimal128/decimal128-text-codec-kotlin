// SPDX-License-Identifier: MIT

package com.decimal128.textcodec

// The one KMP seam in this repo's tests.
//
// The conformance corpus lives in decimal128-resources, mounted as a submodule at
// <repo>/resources -- the same mount every decimal128 port uses. commonTest has
// no portable file access, so reaching it needs an actual per platform family:
// the JVM and native read the filesystem directly, Node reads it through `fs`.
// Each one resolves an absolute base from the DECIMAL128_RESOURCES environment
// variable, which the build sets on every test task; the desktop actuals also
// accept the relative path a Gradle test task's working directory gives them.
//
// fileName is corpus-root-relative with a leading '/', e.g.
// "/text_bid_dpd/text_bid128_dpd128_100.csv.txt". Returns null when the file is
// not there, which for this repo means the submodule is not checked out.
expect fun loadTestResourceAsString(fileName: String): String?

/** Where the loader looked, for an error message that names the fix. */
const val RESOURCES_HINT: String =
    "the decimal128-resources submodule is not checked out: " +
        "run `git submodule update --init --recursive`"
