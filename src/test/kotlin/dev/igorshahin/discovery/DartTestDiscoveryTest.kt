package dev.igorshahin.discovery

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.DartTestKind

/** PSI pipeline tests inject official recognition results; no SDK or test process is required. */
class DartTestDiscoveryTest : BasePlatformTestCase() {
    private fun discovery(rejected: Set<String> = emptySet()) = DartTestDiscovery(project, object : TestRunnabilityValidator {
        override fun kind(call: DartCallExpression): DartTestKind? = when (call.expression?.text) {
            "test", "tests.testCase" -> DartTestKind.TEST
            "group", "acceptanceSuite.group" -> DartTestKind.GROUP
            "testWidgets" -> DartTestKind.TEST_WIDGETS
            else -> null
        }
        override fun isRunnable(call: DartCallExpression, name: String): Boolean = name !in rejected
    })

    fun testDiscoversRunnableTestAndWidgets() {
        val file = dartFile("void main() { test('login', () {}); testWidgets('screen', (tester) async {}); }")
        val tests = discovery().discoverFile(file)!!.children
        assertEquals(listOf("login", "screen"), tests.map { it.name })
        assertEquals(listOf(DartTestKind.TEST, DartTestKind.TEST_WIDGETS), tests.map { it.kind })
        assertTrue(tests.all { it.runnable })
    }

    fun testDiscoversNestedGroups() {
        val file = dartFile("void main() { group('auth', () { group('nested', () { test('login', () {}); }); }); }")
        val auth = discovery().discoverFile(file)!!.children.single()
        assertEquals("auth", auth.name)
        assertEquals("nested", auth.children.single().name)
        assertEquals("login", auth.children.single().children.single().name)
    }

    fun testPrunesRejectedLeavesAndEmptyGroups() {
        val file = dartFile("void main() { group('empty', () { test('rejected', () {}); }); test('good', () {}); }")
        val tests = discovery(setOf("rejected")).discoverFile(file)!!.children
        assertEquals(listOf("good"), tests.map { it.name })
        assertNull(discovery(setOf("rejected", "good")).discoverFile(file))
    }

    fun testKeepsNonExecutableGroupOnlyAsStructuralParent() {
        val file = dartFile("void main() { group('auth', () { test('login', () {}); }); }")
        val group = discovery(setOf("auth")).discoverFile(file)!!.children.single()
        assertFalse(group.runnable)
        assertTrue(group.children.single().runnable)
    }

    fun testIgnoresHelpersSetupCommentsAndUnrecognizedCalls() {
        val file = dartFile("""
            void helper() { test('helper', () {}); }
            void main() {
              // test('comment', () {});
              setUp(() { test('setup', () {}); });
              utility.test('noise', () {});
              customWrapper('not recognized', () {});
              test('real', () { test('inside body', () {}); });
            }
        """.trimIndent())
        assertEquals(listOf("real"), discovery().discoverFile(file)!!.children.map { it.name })
    }

    fun testIgnoresNonTestFileAndBrokenPsi() {
        assertNull(discovery().discoverFile(dartFile("void helper() { test('noise', () {}); }", "helpers.dart")))
        assertNull(discovery().discoverFile(dartFile("void main() { test('broken', () {")))
    }

    fun testRecognizedEntrypointDoesNotRequireTestFilenameSuffix() {
        val file = dartFile("void main() { test('runnable', () {}); }", "checks.dart")
        val result = discovery().discoverFiles(listOf(file to "test/deep/checks.dart")).single()
        assertEquals("test/deep/checks.dart", result.relativePath)
        assertEquals("runnable", result.children.single().name)
        assertTrue(discovery(setOf("runnable")).discoverFiles(listOf(file to "test/deep/checks.dart")).isEmpty())
    }

    fun testRejectsDynamicNames() {
        val file = dartFile("""
            void main() {
              final name = 'constant';
              test(name, () {});
              test(getName(), () {});
              test('dynamic ${'$'}name', () {});
              test('static', () {});
            }
        """.trimIndent())
        assertEquals(listOf("static"), discovery().discoverFile(file)!!.children.map { it.name })
    }

    fun testAcceptsCustomCallsOnlyWhenBackendRecognizesThem() {
        val entry = dartFile("""
            void main() {
              acceptanceSuite.group('ПРИЁМКА', (tests) {
                tests.testCase('Приёмка товара по ШК', firstCase);
                tests.testCase('Приёмка товара по стикеру', secondCase);
              });
            }
        """.trimIndent(), "acceptance_test.dart")
        val helper = dartFile("void helper() { test('noise', () {}); }", "helper.dart")
        val files = discovery().discoverFiles(listOf(entry to "integration_test/acceptance_test.dart", helper to "test/helper.dart"))
        assertEquals(1, files.size)
        assertEquals(2, files.single().children.single().children.size)
    }

    fun testOfficialOutlineMapIgnoresOtherElementsAndRecognizesCustomCalls() {
        val file = dartFile("void main() { tests.testCase('real', fixture); utility.test('noise', fixture); }")
        fun outline(kind: String, offset: Int) = AnalyzerOutline(offset, 1, kind, emptyList())
        val root = AnalyzerOutline(0, file.textLength, null, listOf(
                outline("UNIT_TEST_TEST", file.text.indexOf("tests.testCase")),
                outline("METHOD", file.text.indexOf("utility.test")),
            ))
        val map = FlutterTestOutlineIndex.mapTestCalls(file, root)
        assertEquals(mapOf(file.text.indexOf("tests.testCase") to DartTestKind.TEST), map)
    }

    fun testProductionValidatorDoesNotInventTargetsWithoutSdkOrRecognition() {
        val outlines = FlutterTestOutlineIndex(project) {}
        try {
            val file = dartFile("void test(String name, Object body) {} void main() { test('fake', () {}); }")
            assertNull(DartTestDiscovery(project, IntelliJTestRunnabilityValidator(project, outlines)).discoverFile(file))
        } finally { outlines.dispose() }
    }

    fun testDynamicGroupIsStructuralButStaticChildRemainsRunnable() {
        val group = discovery().discoverFile(dartFile("void main() { group(getName(), () { test('static', () {}); }); }"))!!.children.single()
        assertFalse(group.runnable)
        assertFalse(group.nameIsStatic)
        assertTrue(group.children.single().runnable)
    }

    fun testAnalyzerProtocolSnapshotDecodesOnlyRelevantFields() {
        val json = com.google.gson.JsonParser.parseString("""{"offset":0,"length":12,"children":[
            {"offset":2,"length":4,"dartElement":{"kind":"UNIT_TEST_TEST","name":"label"}}
        ]}""").asJsonObject
        val snapshot = AnalyzerOutline.fromJson(json)
        assertEquals(12, snapshot.length)
        assertEquals("UNIT_TEST_TEST", snapshot.children.single().elementKind)
    }

    private fun dartFile(text: String, name: String = "sample_test.dart"): DartFile =
        myFixture.configureByText(name, text) as DartFile
}
