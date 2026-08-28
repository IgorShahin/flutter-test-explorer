package dev.igorshahin.discovery

import com.jetbrains.lang.dart.psi.DartExpression
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import com.jetbrains.lang.dart.util.DartPsiImplUtil

/** Same unquoting primitive as the Dart/Flutter name-target infrastructure; no code evaluation. */
internal class DartTestNameResolver {
    fun isRuntimeNameKnown(expression: DartExpression?): Boolean {
        if (expression !is DartStringLiteralExpression || expression.longTemplateEntryList.isNotEmpty() ||
            expression.shortTemplateEntryList.isNotEmpty()) return false
        val source = expression.text
        val literal = source.removePrefix("r")
        // The plugin's public unquoting API does not evaluate Dart escapes or triple-string
        // leading-newline rules. Do not mistake source spelling for an exact runtime selector.
        return !literal.startsWith("'''") && !literal.startsWith("\"\"\"") &&
            (source.startsWith("r") || '\\' !in literal)
    }

    fun resolve(expression: DartExpression?): String {
        require(expression is DartStringLiteralExpression)
        require(expression.longTemplateEntryList.isEmpty() && expression.shortTemplateEntryList.isEmpty())
        return DartPsiImplUtil.getUnquotedDartStringAndItsRange(expression.text).first
    }
}
