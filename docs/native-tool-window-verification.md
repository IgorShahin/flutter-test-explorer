# Native Tool Window verification — 2026-08-28

Target: IDEA 2025.3.5 / 253.33514.17, Dart 508.1.0, Flutter plugin 95.0.0. The synthetic sandbox project used Flutter SDK 3.27.1.

## Result

- `Tests` is registered through `com.intellij.toolWindow`; its ID and existing content/factory are retained. The factory is now `DumbAware`.
- `AllIcons.Nodes.TestGroup` was verified in the actual Platform binary and rendered successfully as the registered window icon. Run action icon constants are unchanged.
- Native More Tool Windows lists Tests after removal from the sidebar. Invoking its native action restores Tests. Hide, move right/back and auto-hide/pin state changes passed.
- The real analyzer discovered both roots, arbitrary nested directories, nested groups and the nonstandard `checks.dart` entrypoint. Helpers and empty groups/files/directories were absent.
- No project-specific paths/names exist in `src/main`. Candidates are every Dart file under a package test root; the analyzer outline, not a filename or `main()` heuristic, authorizes nodes.
- 61 automatic tests passed; `buildPlugin`, `verifyPluginStructure` and `verifyPluginProjectConfiguration` succeeded. ZIP contents were inspected: only the production plugin JAR, no smoke harness.

## Render regression found by the sandbox check

The initial component render exposed an existing Kotlin/Swing bug: `cellRenderer = RowRenderer()` in the JTree subclass compiled to a direct protected-field write. The native TreeUI kept its cached default renderer and printed `ExplorerNode(...)` with no inline controls. Explicit `setCellRenderer(RowRenderer())` fixes the notification path. A new regression test failed before that one-line fix and passed afterward; both the sandbox renderer-width assertion and visual inspection passed. A UI-update preservation regression also passes.

## Evidence and limits

The reusable check plugin and fixtures are under `tools/sandbox-smoke`. Runtime output is under `build/test_explorer_smoke`: `smoke-result.txt`, `tool-window.png`, `tool-window-icon.png`. The final report ends in `SUCCESS`. On the tiny fixture, the analyzer-backed batch processed 3 candidates in about 19 ms after outlines arrived; this is not a large-project benchmark.

macOS denied Accessibility and display capture access. Native menu/action assertions were therefore executed **inside the real sandbox IDEA**, and the actual Swing component/icon were rendered to PNG for inspection; this was not a manual mouse-click/OS screenshot check. No real application tests or user acceptance suites were executed. Root-only variants, adding/removing `main`, nested package roots and empty-branch pruning are additionally covered by Platform fixture tests.
