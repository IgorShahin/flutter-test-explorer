package dev.igorshahin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TestExplorerTreeBuilderTest {
    @Test
    fun `builds directories and preserves source locations`() {
        val testLocation = SourceLocation("/project/test/services/auth_test.dart", 42)
        val fileLocation = SourceLocation("/project/test/services/auth_test.dart", 0)
        val files = listOf(
            DartTestFile(
                relativePath = "test/services/auth_test.dart",
                location = fileLocation,
                children = listOf(
                    DartTestItem(DartTestKind.TEST, "logout", testLocation),
                ),
            ),
            DartTestFile(
                relativePath = "integration_test/delivery_test.dart",
                location = SourceLocation("/project/integration_test/delivery_test.dart", 0),
                children = emptyList(),
            ),
        )

        val root = TestExplorerTreeBuilder().build(files)

        assertEquals(listOf("test"), root.children.map { it.label })
        val testDirectory = root.children.single()
        val services = testDirectory.children.single()
        val file = services.children.single()
        val test = file.children.single()
        assertEquals(ExplorerNodeKind.FILE, file.kind)
        assertEquals(ExplorerNodeKind.TEST, test.kind)
        assertEquals(SourceLocation("/project/test", 0), testDirectory.location)
        assertEquals(SourceLocation("/project/test/services", 0), services.location)
        assertSame(fileLocation, file.location)
        assertSame(testLocation, test.location)
    }

    @Test
    fun `keeps group and test source order`() {
        val location = SourceLocation("/project/test/auth_test.dart", 1)
        val files = listOf(
            DartTestFile(
                "test/auth_test.dart",
                location,
                listOf(
                    DartTestItem(DartTestKind.TEST, "first", location),
                    DartTestItem(
                        DartTestKind.GROUP,
                        "group",
                        location,
                        listOf(DartTestItem(DartTestKind.TEST, "nested", location)),
                    ),
                ),
            ),
        )

        val file = TestExplorerTreeBuilder().build(files)
            .children.single().children.single()

        assertEquals(listOf("first", "group"), file.children.map { it.label })
        assertEquals("nested", file.children[1].children.single().label)
    }
}
