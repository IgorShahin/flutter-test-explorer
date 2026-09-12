package dev.igorshahin.discovery

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.DartTestKind
import dev.igorshahin.model.TestExplorerTreeBuilder

/**
 * Analyzer-outline discovery: the hierarchy comes from the outline the Dart Analysis Server
 * publishes, so these tests feed outlines rather than reverse-engineering Dart syntax. No SDK
 * and no test process is required.
 *
 * Element names use the shape the analysis server actually publishes - `callee("<text>")`,
 * always double-quoted - which [reported] builds.
 */
class DartTestDiscoveryTest : BasePlatformTestCase() {

    fun testBuildsAnalyzerOwnedNestedHierarchyRegardlessOfEnclosingFunction() {
        val file = dartFile("void registerAcceptanceTests() {}", "acceptance_test.dart")
        val leaf = file.text.indexOf("registerAcceptanceTests")
        val items = collect(file, node("FUNCTION", "registerAcceptanceTests", 0, listOf(
            node("UNIT_TEST_GROUP", reported("suite.group", "acceptance"), 1, listOf(
                node("UNIT_TEST_GROUP", reported("tests.group", "goods"), 2, listOf(
                    node("UNIT_TEST_TEST", reported("tests.testCase", "scan barcode"), leaf),
                )),
            )),
        )))

        val result = discovery(items).discoverFile(file, "integration_test/acceptance_test.dart")!!

        val group = result.children.single()
        assertEquals("acceptance", group.name)
        assertEquals("goods", group.children.single().name)
        assertEquals("scan barcode", group.children.single().children.single().name)
        assertEquals(leaf, group.children.single().children.single().location.offset)
    }

    fun testUsesOfficialWrapperAndTearOffOutlineInsteadOfCallSpelling() {
        val file = dartFile("void bootstrap() => registerAll(buildScenario);", "e2e_test.dart")
        val items = collect(file, node("UNIT_TEST_GROUP", reported("acceptanceSuite.group", "checkout"), 0, listOf(
            node("UNIT_TEST_TEST", reported("scenario", "creates order"), 5),
            node("UNIT_TEST_TEST", reported("testCase", "pays by card"), 12),
        )))

        val tests = discovery(items).discoverFile(file)!!.children.single().children

        assertEquals(listOf("creates order", "pays by card"), tests.map { it.name })
        assertTrue(tests.all { it.runnable })
    }

    fun testIgnoresNonTestOutlineNoiseAndPrunesEmptyGroups() {
        val file = dartFile("void helper() {}")
        val items = collect(file,
            node("FUNCTION", "helper", 0),
            node("UNIT_TEST_GROUP", reported("group", "empty"), 1),
            node("CLASS", "Fixture", 2, listOf(node("UNIT_TEST_TEST", reported("test", "real"), 3))),
        )

        assertEquals(listOf("real"), discovery(items).discoverFile(file)!!.children.map { it.name })
    }

    fun testClassifiesTestWidgetsFromTheOutlineWithoutResolvingPsiCalls() {
        val file = dartFile("void anything() {}")
        val items = collect(file,
            node("UNIT_TEST_TEST", reported("testWidgets", "screen"), 0),
            node("UNIT_TEST_TEST", reported("tests.testWidgets", "prefixed"), 1),
            node("UNIT_TEST_TEST", reported("test", "logic"), 2),
        )

        assertEquals(
            listOf(DartTestKind.TEST_WIDGETS, DartTestKind.TEST_WIDGETS, DartTestKind.TEST),
            discovery(items).discoverFile(file)!!.children.map { it.kind },
        )
    }

    fun testNoAnalyzerTestEntitiesMeansNoExplorerFile() {
        val file = dartFile("void test(String name, Object body) {} void helper() { test('fake', () {}); }")
        assertNull(discovery(emptyList()).discoverFile(file))
    }

    /**
     * The analysis server reports `callee("<text>")` where `<text>` is the argument's *value*
     * when it is statically known and its source text otherwise. Shapes verified against Dart
     * 3.6.0 on 232 real `UNIT_TEST_*` nodes plus a purpose-built probe.
     */
    fun testAnalyzerEvaluatedNamesAreExactAndUnevaluatedLiteralsAreNot() {
        // Escapes, raw strings, triple quotes and adjacent literals arrive already evaluated,
        // so the reported text is the name the runner will report.
        val evaluated = listOf(
            "simple literal",
            "Создание коробки картона",
            "raw\\name",
            "escaped\nname",
            "triple folded",
            "adjacent parts",
            "trailing space ",
        )
        evaluated.forEach { text ->
            val parsed = FlutterTestOutlineIndex.parseTestName(reported("test", text))!!
            assertEquals(text, parsed.text)
            assertTrue("'$text' is the runtime name", parsed.isExactRuntimeName)
        }
        // An un-evaluable string literal keeps its own quotes inside the analyzer's payload.
        // That name is assembled at run time and must never become a selector.
        val unevaluated = listOf(
            "'interpolated \$deviceId end'",
            "'braced \${deviceId} end'",
            "\"double quoted \$x\"",
            "r'raw \$x'",
        )
        unevaluated.forEach { text ->
            val parsed = FlutterTestOutlineIndex.parseTestName(reported("test", text))!!
            assertFalse("'$text' is assembled at run time", parsed.isExactRuntimeName)
            assertEquals(text, parsed.text)
        }
        assertNull(FlutterTestOutlineIndex.parseTestName("not an invocation"))
        assertNull(FlutterTestOutlineIndex.parseTestName("test()"))
        assertNull(FlutterTestOutlineIndex.parseTestName(null))
    }

    fun testUnevaluatedAnalyzerNameGetsNoTargetWhileEvaluatedOnesDo() {
        val file = dartFile("void main() {}")
        val items = collect(file,
            node("UNIT_TEST_GROUP", reported("group", "'group \$suffix'"), 0, listOf(
                node("UNIT_TEST_TEST", reported("test", "child"), 1),
            )),
            node("UNIT_TEST_TEST", reported("test", "raw\\name"), 2),
            node("UNIT_TEST_TEST", reported("test", "plain"), 3),
        )

        val result = discovery(items).discoverFile(file, "test/sample_test.dart")!!
        val leaves = TestVisibility.leaves(TestExplorerTreeBuilder().build(listOf(result)))

        assertEquals(listOf("child", "raw\\name", "plain"), leaves.map { it.label })
        assertNull("A run-time-assembled group name poisons its descendants", leaves[0].runTarget)
        assertEquals("raw\\name", leaves[1].runTarget!!.fullName)
        assertEquals("plain", leaves[2].runTarget!!.fullName)
    }

    fun testInterpolatedAnalyzerNameIsDiscoveredButNeverGivenAnExactRuntimeTarget() {
        val file = dartFile("void register() {}", "device_test.dart")
        val items = collect(file, node("UNIT_TEST_GROUP", reported("group", "'device \$deviceId'"), 0, listOf(
            node("UNIT_TEST_TEST", reported("test", "connect"), 1),
        )))

        val result = discovery(items).discoverFile(file, "integration_test/device_test.dart")!!
        val leaf = TestVisibility.leaves(TestExplorerTreeBuilder().build(listOf(result))).single()

        assertEquals("A dynamic name must stay discoverable", "connect", leaf.label)
        assertNull("A dynamic ancestor must not produce an approximate name target", leaf.runTarget)
    }

    fun testDroppedAnalyzerAncestorDoesNotPromoteDescendantsToExactNames() {
        val file = dartFile("void register() {}")
        val items = collect(file, node("UNIT_TEST_GROUP", "group()", 0, listOf(
            node("UNIT_TEST_TEST", reported("test", "inner"), 1),
        )))

        val result = discovery(items).discoverFile(file, "test/sample_test.dart")!!
        val leaf = TestVisibility.leaves(TestExplorerTreeBuilder().build(listOf(result))).single()

        assertEquals("inner", leaf.label)
        assertNull("A hoisted test still carries its lost group prefix at run time", leaf.runTarget)
    }

    fun testAnalyzerProtocolSnapshotRetainsIdentityFields() {
        val json = com.google.gson.JsonParser.parseString(
            """{"offset":0,"length":12,"children":[
                 {"offset":2,"length":4,"dartElement":{"kind":"UNIT_TEST_TEST","name":"test(\"label\")"}}
               ]}""",
        ).asJsonObject

        val snapshot = AnalyzerOutline.fromJson(json)

        assertEquals(12, snapshot.length)
        val child = snapshot.children.single()
        assertEquals("UNIT_TEST_TEST", child.elementKind)
        assertEquals(reported("test", "label"), child.elementName)
        assertEquals("label", FlutterTestOutlineIndex.parseTestName(child.elementName)!!.text)
    }

    fun testDartSourceFixtureAgreesWithTheAnalyzerOwnedHierarchy() {
        val file = dartFile("""
            void helper() { test('outside main', () {}); }
            void main() {
              group('auth', () { group('nested', () { testWidgets('login', (tester) async {}); }); });
              utility.helper('noise');
            }
        """.trimIndent())

        val result = DartTestDiscovery(project, PsiFixtureOutlineProvider()).discoverFile(file)!!

        assertEquals(listOf("outside main", "auth"), result.children.map { it.name })
        val leaf = result.children[1].children.single().children.single()
        assertEquals("login", leaf.name)
        assertEquals(DartTestKind.TEST_WIDGETS, leaf.kind)
    }

    fun testDiscoveryAsksTheProviderToPrepareOnlyTheFilesItIsDiscovering() {
        val first = myFixture.addFileToProject("test/first_test.dart", "void main() {}") as DartFile
        val second = myFixture.addFileToProject("test/second_test.dart", "void main() {}") as DartFile
        val prepared = mutableListOf<List<String>>()
        val provider = object : TestOutlineProvider {
            override fun prepare(files: List<DartFile>) { prepared += files.map { it.name } }
            override fun testItems(file: DartFile) = emptyList<DiscoveredTestOutline>()
        }
        val discovery = DartTestDiscovery(project, provider)

        discovery.discoverFiles(listOf(first to "test/first_test.dart", second to "test/second_test.dart"))
        discovery.discoverFile(first, "test/first_test.dart")

        // The provider is told which outlines to make available, never which ones to give up:
        // a single cache miss must not be read as the whole candidate set.
        assertEquals(listOf(listOf("first_test.dart", "second_test.dart"), listOf("first_test.dart")), prepared)
    }

    /** The element name shape the analysis server publishes for a `UNIT_TEST_*` node. */
    private fun reported(callee: String, text: String): String = "$callee(\"$text\")"

    private fun collect(file: DartFile, vararg nodes: AnalyzerOutline) =
        FlutterTestOutlineIndex.collectTestChildren(file, nodes.toList(), file.textLength)

    private fun discovery(items: List<DiscoveredTestOutline>) = DartTestDiscovery(
        project,
        object : TestOutlineProvider {
            override fun testItems(file: DartFile) = items
        },
    )

    private fun node(
        kind: String,
        name: String,
        offset: Int,
        children: List<AnalyzerOutline> = emptyList(),
    ) = AnalyzerOutline(offset, 1, kind, name, children)

    private fun dartFile(text: String, name: String = "sample_test.dart"): DartFile =
        myFixture.configureByText(name, text) as DartFile
}
