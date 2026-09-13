package dev.igorshahin.execution

import org.junit.Assert.assertEquals
import org.junit.Test

class HotRestartCommandTest {
    @Test
    fun `runs the isolator package executable in IDE protocol mode with exact ids`() {
        assertEquals(
            listOf(
                "run", "flutter_test_isolator", "--device", "macos", "--target", "/p/integration_test/a_test.dart",
                "--flutter", "/sdk/bin/flutter", "--ide-protocol",
                "--test-id=integration_test/a_test.dart#G#A", "--test-id=integration_test/a_test.dart#G#B",
                "--", "--dart-define-from-file=env/test.json",
                "--dart-define=INTEGRATION_TEST_SHOULD_REPORT_RESULTS_TO_NATIVE=false",
            ),
            HotRestartCommand.arguments(
                "macos", "/p/integration_test/a_test.dart", "/sdk/bin/flutter",
                listOf("integration_test/a_test.dart#G#A", "integration_test/a_test.dart#G#B"),
                listOf("--dart-define-from-file=env/test.json"),
            ),
        )
    }

    @Test
    fun `keeps the native results define chosen by the user`() {
        val userDefine = "--dart-define=INTEGRATION_TEST_SHOULD_REPORT_RESULTS_TO_NATIVE=true"

        assertEquals(
            listOf("--", userDefine),
            HotRestartCommand.arguments("macos", "/t.dart", "/f", listOf("id"), listOf(userDefine)).takeLast(2),
        )
    }
}
