// SPDX-License-Identifier: MIT

package com.decimal128.textcodec.tests

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv

// Native actual. On desktop native (macOS/Linux) Gradle runs the test task with
// cwd = project dir, so the relative "resources" fallback resolves; the build
// sets DECIMAL128_RESOURCES anyway and that wins. The iOS simulator runs the
// binary in a sandboxed container with a different cwd, where only the absolute
// path works -- the build forwards it there through SIMCTL_CHILD_.
//
// The file is sized and read in one go rather than in chunks. The corpus is
// ASCII in its data rows but its comment headers carry em dashes, and a chunked
// read would decode a UTF-8 sequence straddling the boundary as replacement
// characters.
@OptIn(ExperimentalForeignApi::class)
actual fun loadTestResourceAsString(fileName: String): String? {
    val base = getenv("DECIMAL128_RESOURCES")?.toKString() ?: "resources"
    val file = fopen(base + fileName, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if ((size < 0) || (fseek(file, 0, SEEK_SET) != 0)) return null
        val bytes = ByteArray(size.toInt())
        if (bytes.isEmpty()) return ""
        val read = fread(bytes.refTo(0), 1u, bytes.size.toULong(), file)
        return bytes.decodeToString(0, read.toInt())
    } finally {
        fclose(file)
    }
}
