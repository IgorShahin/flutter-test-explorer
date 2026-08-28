# Persistent visibility → native filtered execution

Inspected and tested against IDEA 2025.3.5 (253.33514.17), Dart 508.1.0, Flutter 95.0.0. Findings are from the pinned plugin jars (`javap -p -c`), not inferred API names.

## Native APIs and command construction

| Runner | Ordinary targets | Exact subset in one file |
| --- | --- | --- |
| Dart | `DartTestRunConfiguration.runnerParameters`: `GROUP_OR_TEST_BY_NAME`, `FILE`, `FOLDER`; `filePath` and `testName` | `DartTestRunnerParameters.Scope.MULTIPLE_NAMES` with the generated regexp as `testName`. `DartTestRunningState.startProcess` supplies `-n` without escaping the regexp. Ordinary named targets use `-N` (plain substring). Existing `testRunnerOptions` remain. |
| Flutter | `TestConfig.fields`: `TestFields.forTestName`, `forFile`, `forDir` | `TestFields.forFile(path)` with `testName == null` and `additionalArgs` extended by one `--name=<regexp>` token. `TestLaunchState → TestFields.run → FlutterSdk.flutterTest` builds the command using the official runner. |

Flutter's public `useRegexp(true)` is misleading for this use case: `FlutterSdk.flutterTest` calls `StringUtil.escapeToRegexp(testName)` and anchors that **single literal**. Passing a union there would match its punctuation literally. The official additional test arguments field is the supported way to pass the union in this pinned implementation. There is no custom runner, raw shell command, source rewrite, skip injection or after-the-fact result filtering.

Both configuration models accept one file/directory, not a file → individual filter mapping. A single directory regexp could match the same full name in a hidden file, or cross package boundaries. Directory/Run All therefore retain explicit sequential file sessions: one native execution per included file, never one execution per included test.

## Scope and names

`ExecutionScopeResolver` receives the complete immutable model, selected ID and saved checkbox exclusions. It collects runnable descendant leaves and resolves FULL / PARTIAL / EMPTY. No Swing row state or temporary search string is consulted. Toolbar, inline button, context menu and Run All reach the same service/planner/factory.

- FULL retains the ordinary native target, without a filter. Safety exception: a short-name target that overlaps a hidden same-file sibling switches to exact filtering.
- PARTIAL collects included full names and creates one filtered FILE target. Empty files/branches are omitted from multi-file plans.
- EMPTY starts nothing and reports `No visible tests to run`.

The Dart test runtime prefixes names recursively: `Declarer._prefix(name)` in test_api 0.7.3 is `_name == null ? name : '$_name $name'`. Thus nested group descriptions and the leaf description are joined with one separator space, preserving spaces already present in each description. This is consistent with the public [package:test name-filter documentation](https://pub.dev/packages/test).

For included names `suite A` and `suite B`, the generated filter is:

```text
^(?:suite\u0020A|suite\u0020B)$(?![\s\S])
```

Regexp metacharacters are escaped individually using Dart-compatible syntax (not Java's `\Q…\E`). Whitespace, control characters, quotes and backslashes are encoded as `\uXXXX`. The union has no literal whitespace/quotes and survives both native argument paths. Anchors plus an absolute-end guard prevent prefix, suffix and trailing-newline matches. Duplicate included names are deduplicated; a hidden/unselected test with the same full name makes exact separation impossible, so the entire request fails before launching anything.

## Arguments and safety limits

Global arguments and native template arguments are merged before adding the trusted generated selector. User/template name/path selectors remain prohibited. Existing user configurations/templates are never mutated.

Flutter 95 uses `additionalArgs.trim().split(" ")` without quote handling. The adapter parses user options, normalizes separators to spaces and rejects a logical value containing whitespace rather than allowing it to become extra CLI arguments/targets. Single-token `--dart-define` values, repeated defines, timeout and other existing options are retained. For define values containing spaces, use the native `--dart-define-from-file` option with a whitespace-free path. Dart keeps its native quote-aware options parser. This is a pinned Flutter-plugin limitation, not a limitation of Dart regex matching.

Exact full names are trusted only for supported static descriptions and static enclosing descriptions. The public PSI unquoting API does not decode Dart escapes or triple-string newline rules; non-raw escaped strings, triple strings and dynamic prefixes are conservatively unknown. Partial selection in such a file fails closed. Annotations identify runnable wrappers but cannot prove arbitrary runtime name transformations: wrappers must preserve the standard group/description naming contract; arbitrary dynamic registration/name rewriting is not supported. No runtime discovery or Dart-code evaluation is added.

Files still load and groups register normally under the native runner; name selection prevents excluded **test bodies** from executing, not module-level initialization or shared setup needed by included tests. Native flags such as tags/sharding can further narrow the included names. The sequential queue retains its existing stop-on-failure/cancellation behavior.

## Verification (2026-08-29)

79 automated tests pass. Coverage includes FULL/PARTIAL/EMPTY, nested groups, file/directory/Run All, hidden ancestors, duplicate and similar names, unknown names, full-name whitespace, regex metacharacters, control characters, native tokenization, persistent settings/XML, temporary-search independence, global arguments, template non-mutation and whole-batch rejection.

The separate `tools/sandbox-smoke` plugin opts in only on `test_explorer_filtered_smoke`. Its safe fixture has A/B plus C/D that deliberately fail if run. Real sandbox executions using the installed official runners verified A/B ran, C/D did not, Flutter's global dart-define reached the test body, each runner used one execution and the source bytes were unchanged. This passed first with simple A/B names and then with punctuation, quotes, spaces, Cyrillic and emoji. Backslash/control-character escaping is covered by unit tests; the pinned Flutter name extractor does not recognize raw strings as individual targets. Reports and captured native outputs are under `build/test_explorer_filtered_smoke/`. No application acceptance/integration tests were launched.
