package dev.igorshahin.discovery

import com.jetbrains.lang.dart.psi.DartExpression
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import com.jetbrains.lang.dart.util.DartPsiImplUtil

/** Same unquoting primitive as the Dart/Flutter name-target infrastructure; no code evaluation. */
internal class DartTestNameResolver {
    fun resolve(expression: DartExpression?): String {
        require(expression is DartStringLiteralExpression)
        require(expression.longTemplateEntryList.isEmpty() && expression.shortTemplateEntryList.isEmpty())
        return DartPsiImplUtil.getUnquotedDartStringAndItsRange(expression.text).first
    }
}
