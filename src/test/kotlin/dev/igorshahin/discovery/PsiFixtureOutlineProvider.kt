package dev.igorshahin.discovery

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartFile
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import dev.igorshahin.model.DartTestKind

/** Test-only stand-in for already-classified analyzer outline nodes. */
internal class PsiFixtureOutlineProvider : TestOutlineProvider {
    private val names = DartTestNameResolver()

    override fun testItems(file: DartFile): List<DiscoveredTestOutline> {
        val calls = PsiTreeUtil.findChildrenOfType(file, DartCallExpression::class.java)
            .sortedBy { it.textOffset }
        val items = calls.mapNotNull { call ->
            val kind = when (call.expression?.text?.substringAfterLast('.')) {
                "group" -> DartTestKind.GROUP
                "test" -> DartTestKind.TEST
                "testCase" -> DartTestKind.TEST
                "testWidgets" -> DartTestKind.TEST_WIDGETS
                else -> return@mapNotNull null
            }
            val argument = call.arguments?.argumentList?.expressionList?.firstOrNull()
                as? DartStringLiteralExpression ?: return@mapNotNull null
            val known = argument.longTemplateEntryList.isEmpty() && argument.shortTemplateEntryList.isEmpty()
            call to MutableOutline(kind, names.resolve(argument), call.textOffset, known)
        }.toMap(LinkedHashMap())
        val roots = mutableListOf<MutableOutline>()
        items.forEach { (call, item) ->
            var parent = call.parent
            var parentItem: MutableOutline? = null
            while (parent != null && parent !== file) {
                if (parent is DartCallExpression && items[parent]?.kind == DartTestKind.GROUP) {
                    parentItem = items[parent]
                    break
                }
                parent = parent.parent
            }
            if (parentItem == null) roots += item else parentItem.children += item
        }
        return roots.map(MutableOutline::freeze)
    }

    private class MutableOutline(
        val kind: DartTestKind,
        val name: String,
        val offset: Int,
        val known: Boolean,
        val children: MutableList<MutableOutline> = mutableListOf(),
    ) {
        fun freeze(): DiscoveredTestOutline = DiscoveredTestOutline(
            kind, name, offset, children.map(MutableOutline::freeze), known, known,
        )
    }
}
