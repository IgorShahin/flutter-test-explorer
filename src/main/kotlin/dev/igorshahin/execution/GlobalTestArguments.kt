package dev.igorshahin.execution

import com.intellij.util.execution.ParametersListUtil

object GlobalTestArguments {
    /** The only parser for the pre-structured settings value and native template strings. */
    fun parseLegacy(arguments: String): List<String> =
        arguments.takeIf(String::isNotBlank)?.let(ParametersListUtil::parse).orEmpty()

    fun merge(existing: String?, arguments: List<String>): List<String> =
        parseLegacy(existing.orEmpty()) + arguments

    fun renderForDart(arguments: List<String>): String = ParametersListUtil.join(arguments)

    fun validationError(arguments: List<String>): String? {
        if (arguments.any(String::isBlank)) return "Arguments cannot be empty. Remove the empty row or enter a value."
        if (arguments.any { '\u0000' in it }) return "Arguments cannot contain a NUL character."
        val forbidden = arguments.firstOrNull(::isForbiddenSelector)
        if (forbidden != null) return "Test target selectors ($forbidden) are managed by Test Explorer to protect hidden tests."
        return null
    }

    /** Kept only for validating/migrating the legacy free-form value. */
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
        return validationError(parseLegacy(arguments))
    }

    private fun isForbiddenSelector(token: String): Boolean =
        token == "--" || token == "-n" || token == "-N" ||
            token.startsWith("--name=") || token == "--name" ||
            token.startsWith("--plain-name=") || token == "--plain-name" ||
            (token.startsWith("-n") && !token.startsWith("--")) ||
            (token.startsWith("-N") && !token.startsWith("--")) ||
            (!token.startsWith('-') && token.endsWith(".dart"))
}
