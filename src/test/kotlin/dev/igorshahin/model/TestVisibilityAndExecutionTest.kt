package dev.igorshahin.model

import dev.igorshahin.execution.TestExecutionPlanner
import dev.igorshahin.filter.SelectionState
import dev.igorshahin.filter.TestVisibility
import org.junit.Assert.*
import org.junit.Test

class TestVisibilityAndExecutionTest {
    private val location = SourceLocation("/project/test/auth_test.dart", 10)
    private val model = TestExplorerTreeBuilder().build(listOf(
        DartTestFile("test/auth_test.dart", location, listOf(DartTestItem(DartTestKind.GROUP, "auth", location, listOf(
            DartTestItem(DartTestKind.TEST, "login", location),
            DartTestItem(DartTestKind.TEST, "logout", location),
        )))),
        DartTestFile("integration_test/other_test.dart", SourceLocation("/project/integration_test/other_test.dart", 0),
            listOf(DartTestItem(DartTestKind.TEST, "other", SourceLocation("/project/integration_test/other_test.dart", 10)))),
    ))
    private val directory = model.children.last()
    private val file = directory.children.single()
    private val group = file.children.single()
    private val login = group.children.first()
    private val logout = group.children.last()

    @Test fun `excludes directory file group or test and prunes empty branches`() {
        listOf(directory, file, group).forEach { excluded ->
            val filtered = TestVisibility.apply(model, setOf(excluded.id))
            assertEquals(listOf("other"), TestVisibility.leaves(filtered).map { it.label })
        }
        val filtered = TestVisibility.apply(model, setOf(login.id))
        assertEquals(listOf("other", "logout"), TestVisibility.leaves(filtered).map { it.label })
        assertEquals(3, TestVisibility.leaves(model).size)
    }

    @Test fun `tri state includes excludes and partially includes ancestors`() {
        assertEquals(SelectionState.INCLUDED, TestVisibility.state(group, emptySet()))
        assertEquals(SelectionState.EXCLUDED, TestVisibility.state(group, setOf(group.id)))
        assertEquals(SelectionState.EXCLUDED, TestVisibility.state(group, setOf(login.id, logout.id)))
        assertEquals(SelectionState.PARTIAL, TestVisibility.state(group, setOf(login.id)))
        assertEquals(SelectionState.PARTIAL, TestVisibility.state(model, setOf(directory.id)))
    }

    @Test fun `stale ids harmless and temporary search cannot change persistent selection`() {
        val excluded = setOf(login.id, "file:removed.dart")
        val visible = TestVisibility.apply(model, excluded)
        assertEquals(2, TestVisibility.leaves(visible).size)
        assertEquals(1, TestVisibility.leaves(TestExplorerFilter().apply(visible, "logout")).size)
        assertEquals(2, TestVisibility.leaves(TestExplorerFilter().apply(visible, "")).size)
        assertEquals(setOf(login.id, "file:removed.dart"), excluded)
    }

    @Test fun `search exclusion removes empty matching parent`() {
        val filtered = TestExplorerFilter().apply(model, "auth !login !logout")
        assertTrue(filtered.children.isEmpty())
    }

    @Test fun `run all plans explicit visible files`() {
        val planner = TestExecutionPlanner()
        assertEquals(2, planner.plan(model, model.id, emptySet()).targets.size)
        val plan = planner.plan(model, model.id, setOf(directory.id))
        assertNull(plan.error)
        assertEquals(listOf("other_test.dart"), plan.targets.map { it.label })
    }

    @Test fun `partially excluded scopes never broaden execution`() {
        val planner = TestExecutionPlanner()
        listOf(group, file, directory, model).forEach {
            val plan = planner.plan(model, it.id, setOf(login.id))
            assertNotNull(plan.error)
            assertTrue(plan.targets.isEmpty())
        }
        assertNull(planner.plan(model, logout.id, setOf(login.id)).error)
        assertNotNull(planner.plan(model, login.id, setOf(login.id)).error)
    }

    @Test fun `ids survive offset changes and distinguish duplicate groups`() {
        fun build(offset: Int) = TestExplorerTreeBuilder().build(listOf(DartTestFile("test/a_test.dart", location,
            (1..2).map { DartTestItem(DartTestKind.GROUP, "same", location, listOf(
                DartTestItem(DartTestKind.TEST, "same", location.copy(offset = offset)))) })))
        val first = TestVisibility.leaves(build(1)).map { it.id }
        assertEquals(first, TestVisibility.leaves(build(100)).map { it.id })
        assertEquals(2, first.toSet().size)
    }

    @Test fun `name collision with hidden test is blocked`() {
        val overlapping = model.copy(children = model.children.map { dir -> dir.copy(children = dir.children.map { f ->
            f.copy(children = f.children.map { g -> g.copy(children = g.children.map { t ->
                if (t.id == login.id) t.copy(runTarget = t.runTarget!!.copy(fullName = "auth logout duplicate")) else t
            }) })
        }) })
        assertNotNull(TestExecutionPlanner().plan(overlapping, logout.id, setOf(login.id)).error)
    }
}
