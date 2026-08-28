package dev.igorshahin.execution

import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.*
import org.junit.Assert.*
import org.junit.Test

class MultiFileExecutionPlannerTest {
    private fun file(path: String, vararg groups: Pair<String, List<String>>): DartTestFile {
        val location = SourceLocation("/project/$path", 0)
        return DartTestFile(path, location, groups.map { (name, tests) ->
            DartTestItem(DartTestKind.GROUP, name, location, tests.map {
                DartTestItem(DartTestKind.TEST, it, location)
            })
        })
    }

    private fun find(root: ExplorerNode, label: String): ExplorerNode =
        if (root.label == label) root else root.children.firstNotNullOfOrNull { child ->
            runCatching { find(child, label) }.getOrNull()
        } ?: error("Missing fixture node: $label")

    private fun fixture() = TestExplorerTreeBuilder().build(listOf(
        file("integration_test/scenarios/file_a_test.dart", "group A" to listOf("test 1", "test 2")),
        file("integration_test/scenarios/file_b_test.dart", "group B" to listOf("test 3", "test 4")),
        file("integration_test/scenarios/file_c_test.dart", "group C" to listOf("test 5")),
    ))

    @Test fun `root and directories independently plan partial full and empty files`() {
        val root = fixture()
        val excluded = setOf(find(root, "test 2").id, find(root, "file_c_test.dart").id)
        val planner = TestExecutionPlanner()
        val rootPlan = planner.plan(root, root.id, excluded)
        listOf(root.id, find(root, "integration_test").id, find(root, "scenarios").id).forEach { selected ->
            val plan = planner.plan(root, selected, excluded)
            assertNull(plan.error)
            assertEquals(ExecutionScopeState.PARTIAL, plan.scopeState)
            assertEquals(rootPlan.targets, plan.targets)
            assertEquals(listOf("file_a_test.dart", "file_b_test.dart"), plan.targets.map { it.source.label })
            val (partial, full) = plan.targets
            assertEquals(TestRunTargetKind.FILE, partial.target.kind)
            assertEquals("/project/integration_test/scenarios/file_a_test.dart", partial.target.fileOrDirectoryPath)
            assertEquals(ExactTestNameFilter.create(listOf("group A test 1")), partial.nameFilter)
            assertEquals(TestRunTargetKind.FILE, full.target.kind)
            assertEquals("/project/integration_test/scenarios/file_b_test.dart", full.target.fileOrDirectoryPath)
            assertNull(full.nameFilter)
            assertNull(full.target.testName)
        }
    }

    @Test fun `several groups in each partial file share exactly one file specific filter`() {
        val root = TestExplorerTreeBuilder().build(listOf(
            file("test/a_test.dart", "one" to listOf("keep A", "hide A"), "two" to listOf("keep B")),
            file("test/b_test.dart", "three" to listOf("keep C", "hide B"), "four" to listOf("keep D")),
        ))
        val plan = TestExecutionPlanner().plan(root, root.id,
            setOf(find(root, "hide A").id, find(root, "hide B").id))
        assertNull(plan.error)
        assertEquals(2, plan.targets.size)
        assertEquals(ExactTestNameFilter.create(listOf("one keep A", "two keep B")), plan.targets[0].nameFilter)
        assertEquals(ExactTestNameFilter.create(listOf("three keep C", "four keep D")), plan.targets[1].nameFilter)
        assertFalse(Regex(plan.targets[0].nameFilter!!).containsMatchIn("three keep C"))
        assertFalse(Regex(plan.targets[1].nameFilter!!).containsMatchIn("one keep A"))
    }

    @Test fun `identical runtime names in another hidden file do not block or broaden a partial file`() {
        val root = TestExplorerTreeBuilder().build(listOf(
            file("test/a_test.dart", "suite" to listOf("same", "hide")),
            file("test/b_test.dart", "suite" to listOf("same")),
        ))
        val plan = TestExecutionPlanner().plan(root, root.id,
            setOf(find(root, "hide").id, find(root, "b_test.dart").id))
        assertNull(plan.error)
        assertEquals("/project/test/a_test.dart", plan.targets.single().target.fileOrDirectoryPath)
        assertEquals(ExactTestNameFilter.create(listOf("suite same")), plan.targets.single().nameFilter)
    }

    @Test fun `full multi file scope uses one unfiltered native file target per entrypoint`() {
        val root = fixture()
        val plan = TestExecutionPlanner().plan(root, root.id, emptySet())
        assertNull(plan.error)
        assertEquals(ExecutionScopeState.FULL, plan.scopeState)
        assertEquals(3, plan.targets.size)
        assertEquals(3, plan.targets.map { it.target.fileOrDirectoryPath }.distinct().size)
        assertTrue(plan.targets.all { it.target.kind == TestRunTargetKind.FILE && it.nameFilter == null })
    }

    @Test fun `empty per file visibility is skipped even when only its leaves are excluded`() {
        val root = fixture()
        val excluded = TestVisibility.leaves(find(root, "file_a_test.dart")).map { it.id }.toSet()
        val plan = TestExecutionPlanner().plan(root, root.id, excluded)
        assertNull(plan.error)
        assertEquals(listOf("file_b_test.dart", "file_c_test.dart"), plan.targets.map { it.source.label })
        assertTrue(plan.targets.all { it.nameFilter == null })
        val empty = TestExecutionPlanner().plan(root, root.id, TestVisibility.leaves(root).map { it.id }.toSet())
        assertEquals(ExecutionScopeState.EMPTY, empty.scopeState)
        assertEquals("No visible tests to run", empty.error)
        assertTrue(empty.targets.isEmpty())
    }

    @Test fun `directory execution never collects files in sibling directories or roots`() {
        val root = TestExplorerTreeBuilder().build(listOf(
            file("integration_test/active/a_test.dart", "one" to listOf("keep", "hide")),
            file("integration_test/other/b_test.dart", "two" to listOf("other")),
            file("test/c_test.dart", "three" to listOf("unit")),
        ))
        val plan = TestExecutionPlanner().plan(root, find(root, "active").id, setOf(find(root, "hide").id))
        assertNull(plan.error)
        assertEquals("/project/integration_test/active/a_test.dart", plan.targets.single().target.fileOrDirectoryPath)
        assertEquals(ExactTestNameFilter.create(listOf("one keep")), plan.targets.single().nameFilter)
    }
}
