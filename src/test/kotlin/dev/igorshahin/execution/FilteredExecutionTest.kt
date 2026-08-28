package dev.igorshahin.execution

import com.intellij.util.execution.ParametersListUtil
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.*
import org.junit.Assert.*
import org.junit.Test

class FilteredExecutionTest {
    private val location = SourceLocation("/project/test/deep/checks.dart", 1)
    private fun test(name: String) = DartTestItem(DartTestKind.TEST, name, location)
    private fun group(name: String, vararg children: DartTestItem) =
        DartTestItem(DartTestKind.GROUP, name, location, children.toList())
    private fun model(vararg items: DartTestItem) = TestExplorerTreeBuilder().build(
        listOf(DartTestFile("test/deep/checks.dart", location, items.toList())))
    private fun find(root: ExplorerNode, label: String): ExplorerNode =
        if (root.label == label) root else root.children.firstNotNullOfOrNull { runCatching { find(it, label) }.getOrNull() }!!
    private val complete = model(group("ПРИЁМКА", test("A"), test("B"), test("C"), test("D")))
    private val excluded = setOf(find(complete, "C").id, find(complete, "D").id)
    private fun plan(root: ExplorerNode, label: String, excluded: Set<String>) =
        TestExecutionPlanner().plan(root, find(root, label).id, excluded)

    @Test fun `full hierarchical scopes keep ordinary native targets with no filters`() {
        listOf("ПРИЁМКА", "checks.dart", "deep", "test", "Tests").forEach { label ->
            val plan = plan(complete, label, emptySet())
            assertNull(plan.error)
            assertEquals(ExecutionScopeState.FULL, plan.scopeState)
            assertNull(plan.targets.single().nameFilter)
            assertEquals(if (label == "ПРИЁМКА") TestRunTargetKind.NAME else TestRunTargetKind.FILE,
                plan.targets.single().target.kind)
        }
    }

    @Test fun `partial group file directory and Run All use one execution for A and B`() {
        listOf("ПРИЁМКА", "checks.dart", "deep", "test", "Tests").forEach { label ->
            val plan = plan(complete, label, excluded)
            assertNull(plan.error)
            assertEquals(ExecutionScopeState.PARTIAL, plan.scopeState)
            val target = plan.targets.single()
            assertEquals(TestRunTargetKind.FILE, target.target.kind)
            assertEquals(location.filePath, target.target.fileOrDirectoryPath)
            val regex = Regex(target.nameFilter!!)
            listOf("ПРИЁМКА A", "ПРИЁМКА B").forEach { assertTrue(regex.containsMatchIn(it)) }
            listOf("ПРИЁМКА C", "ПРИЁМКА D", "ПРИЁМКА AA", "prefix ПРИЁМКА A", "ПРИЁМКА A\n")
                .forEach { assertFalse(regex.containsMatchIn(it)) }
        }
        assertEquals(4, TestVisibility.leaves(complete).size) // original code/model are never rewritten
    }

    @Test fun `empty scopes including an excluded ancestor start nothing`() {
        listOf(setOf(complete.id), setOf(find(complete, "test").id),
            TestVisibility.leaves(complete).map { it.id }.toSet()).forEach { excluded ->
            listOf("Tests", "checks.dart", "ПРИЁМКА", "A").forEach { label ->
                val plan = plan(complete, label, excluded)
                assertEquals(ExecutionScopeState.EMPTY, plan.scopeState)
                assertEquals("No visible tests to run", plan.error)
                assertTrue(plan.targets.isEmpty())
            }
        }
    }

    @Test fun `nested group full names include all parent names without trimming`() {
        val root = model(group("parent ", group(" nested", test("A"), test("B"))))
        val target = plan(root, " nested", setOf(find(root, "B").id)).targets.single()
        val regex = Regex(target.nameFilter!!)
        assertTrue(regex.containsMatchIn("parent   nested A"))
        assertFalse(regex.containsMatchIn("nested A"))
        assertFalse(regex.containsMatchIn("parent nested A"))
    }

    @Test fun `same short name in different groups is separable`() {
        val root = model(group("one", test("same")), group("two", test("same")))
        val selected = find(root, "one")
        val hidden = find(root, "two")
        val filePlan = plan(root, "checks.dart", setOf(hidden.id))
        val regex = Regex(filePlan.targets.single().nameFilter!!)
        assertTrue(regex.containsMatchIn("one same"))
        assertFalse(regex.containsMatchIn("two same"))
        assertNull(TestExecutionPlanner().plan(root, selected.id, setOf(hidden.id)).error)
    }

    @Test fun `identical full names cannot cross visibility boundary`() {
        val root = model(group("suite", test("same"), test("same")))
        val leaves = TestVisibility.leaves(root)
        val plan = plan(root, "checks.dart", setOf(leaves.last().id))
        assertTrue(plan.error!!.contains("identical full names"))
        assertTrue(plan.targets.isEmpty())
    }

    @Test fun `full group uses exact filtering if native substring would run hidden sibling`() {
        val root = model(group("auth", test("login")), group("auth extended", test("login")))
        val plan = plan(root, "auth", setOf(find(root, "auth extended").id))
        assertEquals(ExecutionScopeState.FULL, plan.scopeState)
        assertNull(plan.error)
        val regex = Regex(plan.targets.single().nameFilter!!)
        assertTrue(regex.containsMatchIn("auth login"))
        assertFalse(regex.containsMatchIn("auth extended login"))
    }

    @Test fun `duplicates all included do not introduce a false exclusion`() {
        val root = model(group("suite", test("same"), test("same"), test("hidden")))
        val plan = plan(root, "suite", setOf(find(root, "hidden").id))
        assertNull(plan.error)
        assertEquals(ExactTestNameFilter.create(listOf("suite same")), plan.targets.single().nameFilter)
    }

    @Test fun `unknown full names fail closed for partial but not full files`() {
        val root = model(group("dynamic", test("A"), test("B")).copy(nameIsStatic = false, runtimeNameKnown = false))
        val partial = plan(root, "checks.dart", setOf(find(root, "B").id))
        assertTrue(partial.error!!.contains("full runtime name is unknown"))
        assertTrue(partial.targets.isEmpty())
        assertNull(plan(root, "checks.dart", emptySet()).targets.single().nameFilter)
    }

    @Test fun `multi file scope keeps full files native filters partial files and omits excluded files`() {
        val otherLocation = SourceLocation("/project/integration_test/active/other_test.dart", 1)
        val hiddenLocation = SourceLocation("/project/integration_test/legacy/old_test.dart", 1)
        val root = TestExplorerTreeBuilder().build(listOf(
            DartTestFile("test/deep/checks.dart", location, listOf(group("suite", test("A"), test("B")))),
            DartTestFile("integration_test/active/other_test.dart", otherLocation,
                listOf(test("A").copy(location = otherLocation))),
            DartTestFile("integration_test/legacy/old_test.dart", hiddenLocation,
                listOf(test("A").copy(location = hiddenLocation))),
        ))
        val plan = plan(root, "Tests", setOf(find(root, "B").id, find(root, "legacy").id))
        assertNull(plan.error)
        assertEquals(2, plan.targets.size)
        assertNull(plan.targets.single { it.source.label == "other_test.dart" }.nameFilter)
        assertNotNull(plan.targets.single { it.source.label == "checks.dart" }.nameFilter)
        assertTrue(plan.targets.none { it.target.fileOrDirectoryPath.contains("legacy") })
    }

    @Test fun `one invalid file rejects the entire batch before anything starts`() {
        val firstLocation = location.copy(filePath = "/project/test/a_test.dart")
        val root = TestExplorerTreeBuilder().build(listOf(
            DartTestFile("test/a_test.dart", firstLocation, listOf(test("valid").copy(location = firstLocation))),
            DartTestFile("test/deep/checks.dart", location, listOf(group("suite", test("same"), test("same")))),
        ))
        val hidden = TestVisibility.leaves(root).last { it.label == "same" }
        val plan = plan(root, "Tests", setOf(hidden.id))
        assertNotNull(plan.error)
        assertTrue(plan.targets.isEmpty())
    }

    @Test fun `temporary text search never changes persistent execution scope`() {
        val searched = TestExplorerFilter().apply(TestVisibility.apply(complete, excluded), "!B")
        assertEquals(1, TestVisibility.leaves(searched).size)
        val plan = plan(complete, "ПРИЁМКА", excluded)
        assertTrue(Regex(plan.targets.single().nameFilter!!).containsMatchIn("ПРИЁМКА B"))
        assertEquals(ExecutionScopeState.FULL, plan(complete, "ПРИЁМКА", emptySet()).scopeState)
    }

    @Test fun `regexp escaping is literal exact and survives native tokenization`() {
        val names = listOf("suite a.b+?*()[]{}^$|", "suite O'Reilly \"quote\" \\ path", "tabs\tline\nend", "😀 кириллица", "")
        val filter = ExactTestNameFilter.create(names)
        val regex = Regex(filter)
        names.forEach { name ->
            assertTrue(name, regex.containsMatchIn(name))
            assertFalse(name, regex.containsMatchIn("prefix$name"))
            assertFalse(name, regex.containsMatchIn("$name\n"))
        }
        assertFalse(regex.containsMatchIn("suite axb+?*()[]{}^$|"))
        assertFalse(filter.any { it.isWhitespace() || it == '\'' || it == '"' })
        assertEquals(listOf("-n", filter), ParametersListUtil.parse("-n \"$filter\""))
        assertEquals(listOf("--name=$filter"), "--name=$filter".split(' '))
    }
}
