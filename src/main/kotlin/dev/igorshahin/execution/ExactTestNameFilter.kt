package dev.igorshahin.execution

/** Dart RegExp syntax (not Java Pattern.quote's unsupported \Q/\E). */
object ExactTestNameFilter {
    fun create(names: List<String>): String {
        require(names.isNotEmpty()) { "No visible tests to run" }
        // The end guard also excludes a trailing newline, regardless of the host regex engine's
        // '$' convention. No multiline flag, substring matching or group-prefix matching.
        return names.distinct().joinToString("|", "^(?:", ")$(?![\\s\\S])", transform = ::escape)
    }

    private fun escape(name: String): String = buildString {
        name.forEach { char ->
            when {
                // Flutter 95 splits additionalArgs on spaces; Dart embeds its regexp in quotes.
                // Encode these characters in the regexp itself so it remains one native token.
                char.isWhitespace() || char.isISOControl() || char in "\"'\\" -> {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                }
                char in "^$.|?*+()[]{}" -> { append('\\'); append(char) }
                else -> append(char)
            }
        }
    }
}
