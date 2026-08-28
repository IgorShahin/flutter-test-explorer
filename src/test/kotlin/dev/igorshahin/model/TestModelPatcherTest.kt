package dev.igorshahin.model

import org.junit.Assert.*
import org.junit.Test

class TestModelPatcherTest {
    private fun file(path: String, name: String = "test") = DartTestFile(path, SourceLocation("/p/$path", 0),
        listOf(DartTestItem(DartTestKind.TEST, name, SourceLocation("/p/$path", 20))))

    @Test fun `batch update preserves unrelated branches and removes empty parents`() {
        val a = file("test/a_test.dart")
        val b = file("integration_test/folder/b_test.dart")
        val builder = TestExplorerTreeBuilder()
        val initial = builder.build(listOf(a, b))
        val patcher = TestModelPatcher()
        val updated = patcher.replace(initial, mapOf(a.relativePath to file(a.relativePath, "edited")))
        assertSame(initial.children.first(), updated.children.first()) // integration_test
        assertEquals("edited", updated.children.last().children.single().children.single().label)
        val deleted = patcher.replace(updated, mapOf(b.relativePath to null))
        assertEquals(listOf("test"), deleted.children.map { it.label })
        val recreated = patcher.replace(deleted, mapOf(b.relativePath to b))
        assertEquals(builder.build(listOf(file(a.relativePath, "edited"), b)), recreated)
        assertSame(recreated, patcher.replace(recreated, emptyMap()))
    }

    @Test fun `many replacements in one directory match complete build`() {
        val files = (1..500).map { file("test/sub/${it}_test.dart") }
        val builder = TestExplorerTreeBuilder()
        val empty = builder.build(emptyList())
        assertEquals(builder.build(files), TestModelPatcher().replace(empty, files.associateBy { it.relativePath }))
    }

    @Test fun `filters preserve unmodified subtree references`() {
        val root = TestExplorerTreeBuilder().build(listOf(file("test/one_test.dart"), file("test/two_test.dart")))
        assertSame(root, dev.igorshahin.filter.TestVisibility.apply(root, emptySet()))
        val filtered = TestExplorerFilter().apply(root, "one")
        assertSame(root.children.single().children.first(), filtered.children.single().children.single())
    }
}
