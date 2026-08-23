// SPDX-License-Identifier: MIT

package com.decimal128.textcodec

// Node actual. The Node runner's working directory is somewhere under build/, so
// there is no useful relative fallback here: the build hands over an absolute
// corpus root in DECIMAL128_RESOURCES and this reads through `fs`.
//
// Both helpers keep the `js(...)` string to a bare expression and do the
// null-handling in Kotlin. The Kotlin/JS front end parses that string itself and
// does not accept everything Node does -- `??` is rejected outright.
private fun nodeEnv(name: String): String? {
    val env = js("process.env")
    val value = env[name]
    return if ((value == null) || (value == undefined)) null else value as String
}

private fun nodeReadFileOrNull(absPath: String): String? {
    val fs = js("require('fs')")
    return try {
        fs.readFileSync(absPath, "utf8") as String
    } catch (e: Throwable) {
        null
    }
}

actual fun loadTestResourceAsString(fileName: String): String? {
    val base = nodeEnv("DECIMAL128_RESOURCES") ?: return null
    return nodeReadFileOrNull(base + fileName)
}
