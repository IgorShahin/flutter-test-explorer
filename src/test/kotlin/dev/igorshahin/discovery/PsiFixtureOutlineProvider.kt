package dev.igorshahin.discovery

import com.intellij.psi.PsiElement
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartExpression
import com.jetbrains.lang.dart.psi.DartFile
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Stands in for the Dart Analysis Server in fixtures that start from Dart source rather than from
 * a hand-written outline.
 *
 * It emits the same [AnalyzerOutline] shape the analyzer publishes - `UNIT_TEST_GROUP` /
 * `UNIT_TEST_TEST` elements named after the invocation spelling, nested by enclosing group - and
 * then hands it to the production mapping. Fixtures therefore exercise the real hierarchy,
 * name-extraction and dynamic-name rules instead of a parallel implementation of them.
 *
 * Recognition is a fixture concern only: the analyzer decides what a test is in production, from
 * `@isTest`/`@isTestGroup` annotations and resolved `package:test` calls that PSI spelling alone
 * cannot tell apart.
 */
internal class PsiFixtureOutlineProvider(
    private val tests: Set<String> = setOf("test", "testCase", "testWidgets"),
    private val groups: Set<String> = setOf("group"),
) : TestOutlineProvider {

    override fun testItems(file: DartFile): List<DiscoveredTestOutline> =
        FlutterTestOutlineIndex.collectTestChildren(file, outline(file).children, file.textLength)

    /** The outline as the analyzer would publish it for [file]. */
    fun outline(file: DartFile): AnalyzerOutline {
        val recognized = LinkedHashMap<DartCallExpression, MutableNode>()
        PsiTreeUtil.findChildrenOfType(file, DartCallExpression::class.java)
            .sortedBy { it.textOffset }
            .forEach { call ->
                val callee = call.expression?.text ?: return@forEach
                val kind = when (callee.substringAfterLast('.')) {
                    in tests -> "UNIT_TEST_TEST"
                    in groups -> "UNIT_TEST_GROUP"
                    else -> return@forEach
                }
                val argument = call.arguments?.argumentList?.expressionList?.firstOrNull()
                    ?: return@forEach
                recognized[call] = MutableNode(call.textOffset, call.textLength, kind,
                    analyzerName(callee, argument))
            }
        val roots = mutableListOf<MutableNode>()
        recognized.forEach { (call, node) ->
            val parent = enclosingGroup(recognized, call, file)
            if (parent == null) roots += node else parent.children += node
        }
        return AnalyzerOutline(0, file.textLength, null, roots.map(MutableNode::freeze))
    }

    /**
     * The element name the analysis server publishes: `callee("<text>")`, where `<text>` is the
     * argument's value when it is statically known and its source text otherwise. Reproducing
     * that rule here is what keeps fixtures honest about the interpolated case.
     */
    private fun analyzerName(callee: String, argument: DartExpression): String {
        val literal = argument as? DartStringLiteralExpression
        val evaluated = literal != null && literal.longTemplateEntryList.isEmpty() &&
            literal.shortTemplateEntryList.isEmpty() && !literal.text.startsWith("r")
        val text = if (evaluated) unquote(literal!!.text) else argument.text
        return callee + "(\"" + text + "\")"
    }

    private fun unquote(source: String): String {
        DELIMITERS.forEach { delimiter ->
            if (source.length >= 2 * delimiter.length &&
                source.startsWith(delimiter) && source.endsWith(delimiter)) {
                return source.substring(delimiter.length, source.length - delimiter.length)
            }
        }
        return source
    }

    private fun enclosingGroup(
        recognized: Map<DartCallExpression, MutableNode>,
        call: DartCallExpression,
        file: DartFile,
    ): MutableNode? {
        var element: PsiElement? = call.parent
        while (element != null && element !== file) {
            val group = (element as? DartCallExpression)?.let(recognized::get)
            if (group != null && group.kind == "UNIT_TEST_GROUP") return group
            element = element.parent
        }
        return null
    }

    private companion object {
        private val DELIMITERS = listOf("'''", "\"\"\"", "'", "\"")
    }

    private class MutableNode(
        val offset: Int,
        val length: Int,
        val kind: String,
        val elementName: String,
        val children: MutableList<MutableNode> = mutableListOf(),
    ) {
        fun freeze(): AnalyzerOutline =
            AnalyzerOutline(offset, length, kind, elementName, children.map(MutableNode::freeze))
    }
}
