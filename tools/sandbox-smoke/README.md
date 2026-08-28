# Development-only native sandbox checks

This is a separate plugin, not a production source set. It only acts on two explicitly named synthetic projects and writes its reports there. `test_explorer_smoke` checks UI/discovery without running tests. **`test_explorer_filtered_smoke` starts real native Flutter and Dart executions of its harmless fixture tests.** Do not install it into your regular IDE or give a real project either name. Fixture names are local to the harness, not runtime discovery rules.

1. Build the main plugin with `./gradlew test buildPlugin`.
2. Copy `project/` into `build/test_explorer_smoke`. Run `flutter pub get --offline` there (or ordinary pub get if dependencies are not cached).
3. Open the copied project in sandbox, configure the Flutter/Dart SDK and content root normally, and trust only that synthetic project if prompted. Ensure `test/` and `integration_test/` are under the content root. Close sandbox before installing the check plugin.
4. Set `IDEA_SDK` to the resolved IDEA 2025.3.5 directory used by Gradle; set `SMOKE_PLUGIN` to `.intellijPlatform/sandbox/flutter-test-explorer/IU-2025.3.5/plugins/test-explorer-smoke`.

From the repository root:

```sh
mkdir -p build/sandbox-smoke-classes/META-INF "$SMOKE_PLUGIN/lib"
javac --release 21 -cp "$IDEA_SDK/lib/*:.intellijPlatform/sandbox/flutter-test-explorer/IU-2025.3.5/plugins/flutter-test-explorer/lib/*" -d build/sandbox-smoke-classes tools/sandbox-smoke/SmokeStartup.java tools/sandbox-smoke/FilteredExecutionSmoke.java
cp tools/sandbox-smoke/plugin.xml build/sandbox-smoke-classes/META-INF/plugin.xml
jar --create --file "$SMOKE_PLUGIN/lib/smoke.jar" -C build/sandbox-smoke-classes .
./gradlew runIde -PtestExplorerDebug --args="$PWD/build/test_explorer_smoke"
```

Wait for `smoke-result.txt` ending in `SUCCESS`. `tool-window.png` is rendered from the actual sandbox Swing component (not a macOS screen capture), and `tool-window-icon.png` from its registered icon. Review these images as well as the assertions. Allow up to two minutes after project startup for indexing/analysis.

The harness removes Tests from the stripe using the New UI's native path, inspects the same list used by `ShowMoreToolWindowsAction`, restores via IntelliJ's own activation action, exercises hide/move/pin/unpin, verifies icon pixels and checks the discovered tree. It also checks that the native TreeUI uses the inline renderer, not a stale default renderer. These implementation APIs are confined to this development harness.

## Filtered execution check

Copy `filtered-project/` to `build/test_explorer_filtered_smoke`, resolve its packages, configure the SDK/content root and trust only that fixture. Launch it with `./gradlew runIde -PtestExplorerDebug --args="$PWD/build/test_explorer_filtered_smoke"`. The harness resets only this synthetic project's saved visibility, waits for four discovered tests, excludes C/D in persistent settings, then calls the production execution service for Flutter and the same production configuration adapter for Dart. C/D deliberately fail if executed. A/B include regexp punctuation, quotes, spaces, Cyrillic and emoji. The harness checks a single native execution per runner, actual A/B stdout, absence of C/D stdout, successful exit, preserved Flutter dart-define and unchanged Dart source bytes. Backslash/control-character escaping is covered separately by unit tests; Flutter's pinned name extractor does not recognize raw strings as individual targets.

Wait for `filtered-execution-result.txt` ending in `SUCCESS`; inspect `flutter-output.txt` and `dart-output.txt` as the native runner evidence. These are real test executions, not configuration-only assertions. The harness must remain installed only for explicit verification.

After verification, stop sandbox and move the `test-explorer-smoke` plugin directory outside `plugins` to disable it. Keep the report/images for inspection. The main distribution contains only `flutter-test-explorer` and never the smoke plugin.
