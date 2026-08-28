package dev.igorshahin.execution

import com.intellij.util.execution.ParametersListUtil

object GlobalTestArguments {
    fun merge(existing: String?, global: String): String =
        listOfNotNull(existing?.trim()?.takeIf { it.isNotEmpty() }, global.trim().takeIf { it.isNotEmpty() })
            .joinToString(" ")

    fun validationError(arguments: String): String? {
        var quote: Char? = null
        var escaped = false
        arguments.forEach { c ->
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                quote != null && c == quote -> quote = null
                quote == null && c == '"' -> quote = c
            }
        }
        if (quote != null) return "Close the quoted argument before applying settings."
        val forbidden = ParametersListUtil.parse(arguments).firstOrNull { token ->
            token == "--" || token == "-n" || token == "-N" ||
                token.startsWith("--name=") || token == "--name" ||
                token.startsWith("--plain-name=") || token == "--plain-name" ||
                (token.startsWith("-n") && !token.startsWith("--")) ||
                (token.startsWith("-N") && !token.startsWith("--")) ||
                (!token.startsWith('-') && token.endsWith(".dart"))
        }
        if (forbidden != null) return "Test target selectors ($forbidden) are managed by Test Explorer to protect hidden tests."
        var expectsValue = false
        ParametersListUtil.parse(arguments).forEach { token ->
            if (expectsValue) {
                expectsValue = false
            } else if (token.startsWith('-')) {
                expectsValue = token in VALUE_OPTIONS
            } else {
                return "Positional test paths are not allowed. For other option values, use --option=value."
            }
        }
        if (expectsValue) return "The last option is missing its value."
        return null
    }

    private val VALUE_OPTIONS = setOf(
        "--dart-define", "--dart-define-from-file", "--device-id", "-d", "--flavor", "--timeout",
        "--concurrency", "-j", "--platform", "-p", "--tags", "-t", "--exclude-tags", "-x",
        "--reporter", "-r", "--file-reporter", "--coverage-path", "--coverage-package",
        "--test-randomize-ordering-seed", "--total-shards", "--shard-index", "--observatory-port",
    )
}
