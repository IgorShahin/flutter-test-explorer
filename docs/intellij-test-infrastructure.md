# Installed test infrastructure — inspection notes

Inspected with `jar tf` and `javap -p -c` against the actual project dependencies: IDEA 253.33514.17 / 2025.3.5, Dart 508.1.0, Flutter 95.0.0. These notes describe those binaries, not an assumed current upstream implementation.

## Recognition and targets

| Question | Installed implementation and decision |
| --- | --- |
| Dart `test()` / `group()` / nesting | `com.jetbrains.lang.dart.ide.runner.util.TestUtil` checks exact expression spelling and `findTestElement` walks enclosing calls, so spelling alone accepts unrelated methods. The explorer does not classify calls at all: the analysis server's `UNIT_TEST_TEST` / `UNIT_TEST_GROUP` outline decides what a test is, for Dart and Flutter packages alike. |
| Flutter test/widgets/custom wrappers | `io.flutter.run.test.FlutterTestLineMarkerContributor` delegates to `TestLineMarkerContributor` and `TestConfigUtils` / `CommonTestConfigUtils`. Their private `OutlineCache` maps analyzer `UNIT_TEST_TEST` and `UNIT_TEST_GROUP` elements to PSI calls via `DartSyntax.findClosestEnclosingFunctionCall`. Detection does not depend on a custom method-name list. |
| Names | Verified against Dart 3.6.0 on 232 real `UNIT_TEST_*` nodes plus a probe: the analyzer reports `callee("<text>")`, **always double-quoted**, never the raw source spelling. `<text>` is the argument's *value* when it is statically known — escapes, raw strings, triple-quote folding and adjacent-literal concatenation already applied — and the argument's *source text* otherwise. An un-evaluable string literal therefore arrives with its own quotes intact (`test('a $b')` → `test("'a $b'")`), which is the signal that the name is assembled at run time. `FlutterTestOutlineIndex.parseTestName` strips the analyzer's quotes (as Dart-Code does) and treats only that quoted-literal shape as non-exact. A first argument that is not a string literal at all (a variable, a call) is reported as bare source text and **cannot** be told apart from a literal of the same characters; Dart-Code shares that blind spot, and the Hot Restart controller rejects an id absent from the runtime manifest rather than running something else. |
| Gutter configuration | `DartTestRunConfigurationProducer` and `FlutterTestConfigProducer` use the context and SDK/package/test-root checks. Flutter can silently fall back to a file configuration if the active-editor outline cache does not identify a call. Explorer never counts that fallback as evidence that a leaf is runnable. |
| Filenames and roots | In these versions, Dart's producer calls `DartCommandLineRuntimeConfigurationProducer.getRunnableDartFileFromContext` (requires `main`) and `isFileInTestDirAndTestPackageExists` (package test root/dependency). Flutter's `TestConfigUtils.asTestCall` and file producer use `FlutterUtils.isInTestDir`. Neither requires `_test.dart` inside a recognized test directory. Explorer scopes candidates to package test roots and treats **every** Dart file there as a candidate: no `main()` shape and no filename suffix is required, because a file with no analyzer test entities simply contributes nothing. |
| Single/group/file/directory | Dart `DartTestRunnerParameters.Scope`: `GROUP_OR_TEST_BY_NAME`, `FILE`, `FOLDER`. Flutter `TestFields.forTestName`, `forFile`, `forDir`. A name target is built only for a node whose whole logical path is known to be the runtime name; otherwise the node carries no name target and is reached by running its file. |
| Extra arguments | Settings persist ordered `{ value, enabled }` entries and execution snapshots only enabled values as `List<String>`. Flutter's string-only `TestFields.additionalArgs` receives reversible transport tokens; a public IntelliJ command-line customizer restores them as individual process argv elements. Dart receives a quote-aware `DartTestRunnerParameters.testRunnerOptions` value that its native runner parses back to tokens. `arguments`/VM options are not substitutes for test-runner options. |
| Partial subset | Dart `MULTIPLE_NAMES` passes `testName` as raw `-n` regexp. Flutter `useRegexp(true)` **escapes one literal**, so it cannot carry an arbitrary union: use `TestFields.forFile` plus `additionalArgs` containing `--name=<exact union>`. Full names come from static group/test descriptions. Unknown names and indistinguishable duplicates fail closed. See [native execution research](filtered-execution.md). |
| Run All exclusions | Explicit included files, sequential official Run sessions. Partially included files get one exact-name filter each, excluded files are omitted. Named runs overlapping hidden siblings switch to an exact filter. The whole batch is checked before starting. |
| Persistent IDs | Project-relative encoded file/directory path; type + enclosing group identities + name + duplicate occurrence for test nodes. No PSI/offset persistence. Renames produce new IDs; stale entries do not affect discovery. |
| Native checkboxes | `CheckboxTree`, `CheckedTreeNode`, `CheckboxTreeBase.CheckPolicy` and the built-in `ThreeStateCheckBox` renderer. Visibility is a separate immutable model transformation. |

## Analyzer-owned hierarchy

Discovery mirrors Dart-Code / VS Code: the Dart Analysis Server's `FlutterOutline` is the single
source of truth for the test tree.

- `FlutterTestOutlineIndex` subscribes to outlines for the candidate set and is the production
  `TestOutlineProvider`. `collectTestChildren` lifts the `UNIT_TEST_GROUP` / `UNIT_TEST_TEST`
  nesting into the explorer hierarchy; anything else the analyzer reports around a test
  (functions, classes, extensions) is a wrapper, so its test descendants are hoisted.
- Custom wrappers annotated `@isTest` / `@isTestGroup`, tear-offs, and registrations outside a
  particular `main()` shape therefore need no reverse engineering: the analyzer already reports
  them, so no method-name list or syntactic nesting rule exists in this plugin.
- PSI is no longer part of recognition. It supplies the `DartFile` handle the analyzer APIs need,
  validates that a snapshot still matches the current source (`textLength` against the outline
  length, plus a source digest), converts analyzer offsets, and serves navigation. There is
  deliberately **no** PSI discovery fallback: guessing a hierarchy the analyzer has not reported
  would produce entries that cannot be reconciled or safely targeted, so a candidate without an
  outline is marked pending and the run barrier waits for it.
- `DartTestDiscovery` collects candidates and maps outlines to the model. `TestOutlineProvider`
  carries no run-configuration or call-expression contract, keeping discovery and execution apart.

## API stability / fallback policy

**Stable platform-level building blocks:** PSI traversal, `ReadAction.nonBlocking`, `PersistentStateComponent`, `RunManager`, `ProgramRunnerUtil.executeConfiguration`, execution message-bus events, `DialogWrapper`, native action toolbar and checkbox tree. These do not expose a project-wide Dart/Flutter test catalogue.

**Public JVM classes but plugin implementation APIs (no third-party stability promise):** Dart PSI/TestUtil, Dart test producer/configuration/parameters, Flutter test configuration/fields, `FlutterDartAnalysisServer`, `FlutterOutlineListener`, `DartSyntax`, `ActiveEditorsOutlineService`. Their use is isolated in discovery/execution adapters. Changing versions can break binary compatibility or target semantics; the Gradle dependencies are pinned and configuration-generation/sandbox checks are required when upgrading.

**Private API deliberately not accessed:** `CommonTestConfigUtils.OutlineCache` and its map-building routine. Since the official public-facing helper only exposes active-editor data, the adapter subscribes to the same server's outlines for candidate test files and reads the hierarchy straight out of the protocol. It does not map outline elements back onto PSI calls, copy semantic analyzer detection, inspect private fields, or fall back to regex/source-name matching. While the analyzer has no outline for a candidate, that file is explicitly pending rather than reported empty.

### Duplicate protocol classes: verified sandbox issue

Dart 508.1.0 and Flutter 95.0.0 both contain `org.dartlang.analysis.server.protocol.FlutterOutline`. A direct Kotlin implementation of `FlutterOutlineListener` caused a loader-constraint violation in sandbox when the explorer resolved the Dart copy but Flutter delivered its own copy. The adapter therefore creates a Java dynamic proxy in the **listener's owning loader**, invokes the outline's **public** `toJson()` method and copies just offset/length/element-kind/children into a loader-independent DTO. Active-editor fallback similarly invokes the public `getIfUpdated(PsiFile)` reflectively without linking its duplicated return type. No private reflection is used. This is a narrowly scoped compatibility boundary, not a second analyzer.

The installed subscription API is a no-op before server connection, so discovery retries when the Dart server reports connection. `removeOutlineListener` looks up listeners by local-file URI; the adapter supplies that key to release its listener. The installed implementation's subscription cleanup re-encodes that key, so a server-side subscription may remain until project closure even after the listener has been released. No PSI or explorer listener is intentionally retained after disposal.

Outline updates have no document-version field. The adapter binds cached data to a SHA-256 digest of current document/source contents and applies the official length check; stale/out-of-order analyzer notifications remain an upstream protocol limitation. Do **not** bind outlines or run locations to `PsiFile.modificationStamp`: IDEA 253's `PsiFileImpl.clearCaches()` increments that counter without editing source, and the analyzer will not publish a replacement outline merely because PSI caches were cleared. Discovery/cache/run guards now share the authoritative loaded-document stamp (including unsaved edits), otherwise the VFS stamp. Run waits for current per-file discovery and automatically re-resolves/replans after a real source change; it never asks for manual Refresh just because an edit raced with Run. See [edit/run coordination](edit-and-run.md). This is not a guarantee that arbitrary Dart code compiles or the test passes—only that the IDE recognizes and can construct a test target.

Dynamic-name groups are retained only as structural parents when their literal-name descendants are runnable via the official short-name target. Their runtime full names are marked unknown. Any named run that might collide with a hidden descendant with an unknown full name is blocked rather than assuming a static runtime prefix.

## Safe verification boundary

Configuration-generation tests instantiate official Flutter/Dart models without starting processes. A manual smoke check should open a configured project, wait for analysis, verify expected entrypoints/custom annotated tests, toggle/reopen the visibility dialog, verify persistence across IDE restart and inspect generated Run sessions on a safe sample project. Do not automatically execute a user's real acceptance suite merely to verify this plugin.

## Native Tool Window and icon (IDEA 253)

`plugin.xml` registers `com.intellij.toolWindow`, ID `Tests`, using the existing factory/content. The factory is `DumbAware`; no custom menu item or imperative registration is added. IntelliJ owns availability, layout and restoration. The New UI's `ShowMoreToolWindowsAction.createPopup` uses `ToolWindowsGroup.getToolWindowActions(project, true)`, omitting windows already on the stripe. Removing Tests from the sidebar makes it available there; the native activation action restores it. Do not force sidebar visibility on every startup.

The inspected `AllIcons.Toolwindows` has Run/Coverage icons but no Tests/Test Explorer constant. Public `AllIcons.Nodes.TestGroup` represents a collection of tests, with Platform-supplied classic/New UI/light/dark variants; it was selected over execution/status icons from `AllIcons.RunConfigurations`. Only the extension's icon changes. Inline and toolbar Run icons remain unchanged. See the official [Tool Windows registration guide](https://plugins.jetbrains.com/docs/intellij/tool-windows.html) and [Platform icons guide](https://plugins.jetbrains.com/docs/intellij/icons.html).

`tools/sandbox-smoke` contains a separate development-only plugin for native manager/menu/icon/tree checks against a safe synthetic project. It is not part of any production source set or the distributable ZIP. Platform unit tests use placeholder icons; pixel rendering is checked in the actual sandbox instead.
