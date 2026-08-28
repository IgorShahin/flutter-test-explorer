package dev.igorshahin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestExplorerFilterTest {
    private val filter = TestExplorerFilter()
    private val model = ExplorerNode(
        ExplorerNodeKind.ROOT,
        "project",
        children = listOf(
            directory("integration_test", test("Приёмка товара по ШК"), test("Отмена выдачи")),
            directory("test", test("logout"), test("mock storage")),
        ),
    )

    @Test
    fun `includes matching tests and their ancestors`() {
        val result = filter.apply(model, "приёмка шк")

        assertEquals(listOf("integration_test"), result.children.map(ExplorerNode::label))
        assertEquals(
            listOf("Приёмка товара по ШК"),
            result.children.single().children.map(ExplorerNode::label),
        )
    }

    @Test
    fun `supports exclusion terms`() {
        val result = filter.apply(model, "!mock")

        assertEquals(2, result.children.size)
        assertTrue(result.children.last().children.none { it.label == "mock storage" })
    }

    private fun directory(name: String, vararg children: ExplorerNode) = ExplorerNode(
        ExplorerNodeKind.DIRECTORY,
        name,
        children = children.toList(),
    )

    private fun test(name: String) = ExplorerNode(ExplorerNodeKind.TEST, name)
}
