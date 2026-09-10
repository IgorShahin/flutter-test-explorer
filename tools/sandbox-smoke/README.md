# Development-only native sandbox checks

This is a separate plugin, not a production source set. It only acts on four explicitly named synthetic projects and writes its reports there. `test_explorer_smoke` checks UI/discovery without running tests. **`test_explorer_filtered_smoke` starts real native Flutter and Dart executions; `test_explorer_multifile_smoke` starts real sequential Flutter file executions; `test_explorer_edit_smoke` intentionally edits fixture documents and launches tests through the real tree actions.** All use harmless fixture tests. Do not install it into your regular IDE or give a real project any of these names. Fixture names are local to the harness, not runtime discovery rules.

1. Build the main plugin with `./gradlew test buildPlugin`.
2. Copy `project/` into `build/test_explorer_smoke`. Run `flutter pub get --offline` there (or ordinary pub get if dependencies are not cached).
3. Open the copied project in sandbox, configure the Flutter/Dart SDK and content root normally, and trust only that synthetic project if prompted. Ensure `test/` and `integration_test/` are under the content root. Close sandbox before installing the check plugin.
4. Set `IDEA_SDK` to the resolved IDEA 2025.3.5 directory used by Gradle; set `SMOKE_PLUGIN` to `.intellijPlatform/sandbox/flutter-test-explorer/IU-2025.3.5/plugins/test-explorer-smoke`.

From the repository root:

```sh
mkdir -p build/sandbox-smoke-classes/META-INF "$SMOKE_PLUGIN/lib"
javac --release 21 -cp "$IDEA_SDK/lib/*:.intellijPlatform/sandbox/flutter-test-explorer/IU-2025.3.5/plugins/flutter-test-explorer/lib/*:.intellijPlatform/sandbox/flutter-test-explorer/IU-2025.3.5/plugins/flutter-intellij/lib/*" -d build/sandbox-smoke-classes tools/sandbox-smoke/SmokeStartup.java tools/sandbox-smoke/FilteredExecutionSmoke.java tools/sandbox-smoke/MultiFileExecutionSmoke.java tools/sandbox-smoke/EditRunSmoke.java
cp tools/sandbox-smoke/plugin.xml build/sandbox-smoke-classes/META-INF/plugin.xml
jar --create --file "$SMOKE_PLUGIN/lib/smoke.jar" -C build/sandbox-smoke-classes .
./gradlew runIde -PtestExplorerDebug --args="$PWD/build/test_explorer_smoke"
```

Wait for `smoke-result.txt` ending in `SUCCESS`. `tool-window.png` is rendered from the actual sandbox Swing component (not a macOS screen capture), and `tool-window-icon.png` from its registered icon. Review these images as well as the assertions. Allow up to two minutes after project startup for indexing/analysis.

The harness removes Tests from the stripe using the New UI's native path, inspects the same list used by `ShowMoreToolWindowsAction`, restores via IntelliJ's own activation action, exercises hide/move/pin/unpin, verifies icon pixels and checks the discovered tree. It also checks that the native TreeUI uses the inline renderer, not a stale default renderer. These implementation APIs are confined to this development harness.

## Filtered execution check

Copy `filtered-project/` to `build/test_explorer_filtered_smoke`, resolve its packages, configure the SDK/content root and trust only that fixture. Launch it with `./gradlew runIde -PtestExplorerDebug --args="$PWD/build/test_explorer_filtered_smoke"`. The harness resets only this synthetic project's saved visibility, waits for four discovered tests, excludes C/D in persistent settings, then calls the production execution service for Flutter and the same production configuration adapter for Dart. C/D deliberately fail if executed. A/B include regexp punctuation, quotes, spaces, Cyrillic and emoji. The harness checks a single native execution per runner, actual A/B stdout, absence of C/D stdout, successful exit, an enabled Flutter dart-define reaching the test body, a persisted disabled dart-define being physically absent, and unchanged Dart source bytes. Backslash/control-character escaping is covered separately by unit tests; Flutter's pinned name extractor does not recognize raw strings as individual targets.

Wait for `filtered-execution-result.txt` ending in `SUCCESS`; inspect `flutter-output.txt` and `dart-output.txt` as the native runner evidence. These are real test executions, not configuration-only assertions. The harness must remain installed only for explicit verification.

## Multi-file orchestration check

Copy `multifile-project/` to `build/test_explorer_multifile_smoke`, resolve dependencies and configure the SDK/content root as above. Launch with `./gradlew runIde -PtestExplorerDebug --args="$PWD/build/test_explorer_multifile_smoke"`. The fixture has a PARTIAL file A (test 1 enabled, test 2 excluded), FULL file B (tests 3/4 enabled) and EMPTY file C. It invokes the actual production Run All service on the root, verifies the directory resolves the identical plan, and checks execution lifecycle events: A must finish before B starts. It inspects the actual native configuration fields (A filtered, B unfiltered), enabled global defines, physical absence of a saved disabled define in both processes, completed output, absence of hidden test/file bodies, and unchanged source bytes. Read `multifile-result.txt` and `multifile-output.txt` for the result.

Run `prepareSandbox -PtestExplorerDebug` before installing this harness: Gradle may remove non-dependency plugins when preparing a changed sandbox. If you change production code, rebuild first and then reinstall the development harness. An up-to-date `runIde` retains it.

## Edit → immediate Run check

Copy `edit-project/` to `build/test_explorer_edit_smoke`, resolve dependencies and configure the SDK/content root as above. Launch with `./gradlew runIde -PtestExplorerDebug --args="$PWD/build/test_explorer_edit_smoke"`. This harness deliberately changes the fixture's actual editor documents. It waits for startup synchronization, makes a 40-edit unsaved burst and invokes the tree's Run action immediately without saving, committing or refreshing. It also edits a queued file during Run All and performs a final unsaved rename without any Run action. `edit-run-result.txt` must end with `SUCCESS`; inspect `edit-run-output.txt` and the per-file discovery metrics in `idea.log`. All writes are scheduled in a non-modal write-safe context. Before repeating, restore **only these two fixture Dart files** from `edit-project/test/`; the previous run intentionally leaves them edited. Never use real project sources for this check.

After verification, stop sandbox and move the `test-explorer-smoke` plugin directory outside `plugins` to disable it. Keep the report/images for inspection. The main distribution contains only `flutter-test-explorer` and never the smoke plugin.
