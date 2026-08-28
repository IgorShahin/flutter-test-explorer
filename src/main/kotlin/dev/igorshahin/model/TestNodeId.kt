package dev.igorshahin.model

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TestNodeId {
    const val ROOT = "root"

    fun directory(relativePath: String): String = "dir:${encode(relativePath)}"

    fun file(relativePath: String): String = "file:${encode(relativePath)}"

    fun test(
        relativeFilePath: String,
        kind: ExplorerNodeKind,
        logicalPath: List<String>,
        occurrence: Int,
    ): String = buildString {
        append(kind.name.lowercase())
        append(':')
        append(encode(relativeFilePath))
        logicalPath.forEach {
            append('/')
            append(encode(it))
        }
        append('@')
        append(occurrence)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
