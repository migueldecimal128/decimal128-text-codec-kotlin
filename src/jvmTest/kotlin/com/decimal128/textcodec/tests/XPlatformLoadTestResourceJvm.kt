// SPDX-License-Identifier: MIT

package com.decimal128.textcodec.tests

import java.io.File

// JVM actual: the corpus is read off the filesystem, not the test classpath.
// Nothing is copied into src/jvmTest/resources, so there is no classpath copy to
// go stale -- the submodule working tree is the only version there is.
actual fun loadTestResourceAsString(fileName: String): String? {
    val base = System.getenv("DECIMAL128_RESOURCES") ?: "resources"
    val file = File(base + fileName)
    return if (file.isFile) file.readText() else null
}
