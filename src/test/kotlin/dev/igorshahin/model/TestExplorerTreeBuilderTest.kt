package dev.igorshahin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `derives Hot Restart identity from relative file and complete logical path`() {
        val location = SourceLocation("/project/integration_test/features/returns/returns_test.dart", 1)
        val root = TestExplorerTreeBuilder().build(
            listOf(
                DartTestFile(
                    "integration_test/features/returns/returns_test.dart",
                    location,
                    listOf(
                        DartTestItem(
                            DartTestKind.GROUP,
                            "ВОЗВРАТЫ",
                            location,
                            listOf(
                                DartTestItem(
                                    DartTestKind.GROUP,
                                    "Коробки возврата",
                                    location,
                                    listOf(DartTestItem(DartTestKind.TEST, "Создание коробки", location)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val test = root.children.single().children.single().children.single()
            .children.single().children.single().children.single().children.single()

        assertEquals(
            "integration_test/features/returns/returns_test.dart#ВОЗВРАТЫ#Коробки возврата#Создание коробки",
            test.runTarget?.hotRestartId,
        )
    }

    @Test
    fun `does not invent Hot Restart identity for a dynamic runtime path`() {
        val location = SourceLocation("/project/integration_test/dynamic_test.dart", 1)
        val root = TestExplorerTreeBuilder().build(
            listOf(
                DartTestFile(
                    "integration_test/dynamic_test.dart",
                    location,
                    listOf(
                        DartTestItem(
                            DartTestKind.GROUP,
                            "dynamic",
                            location,
                            listOf(DartTestItem(DartTestKind.TEST, "test", location)),
                            runtimeNameKnown = false,
                        ),
                    ),
                ),
            ),
        )

        val test = root.children.single().children.single().children.single().children.single()
        assertNull(test.runTarget?.hotRestartId)
    }
}
