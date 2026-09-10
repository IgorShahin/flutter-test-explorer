# Persistent visibility → native filtered execution

Inspected and tested against IDEA 2025.3.5 (253.33514.17), Dart 508.1.0, Flutter 95.0.0. Findings are from the pinned plugin jars (`javap -p -c`), not inferred API names.

## Native APIs and command construction

| Runner | Ordinary targets | Exact subset in one file |
| --- | --- | --- |
| Dart | `DartTestRunConfiguration.runnerParameters`: `GROUP_OR_TEST_BY_NAME`, `FILE`, `FOLDER`; `filePath` and `testName` | `DartTestRunnerParameters.Scope.MULTIPLE_NAMES` with the generated regexp as `testName`. `DartTestRunningState.startProcess` supplies `-n` without escaping the regexp. Ordinary named targets use `-N` (plain substring). Existing `testRunnerOptions` remain. |
| Flutter | `TestConfig.fields`: `TestFields.forTestName`, `forFile`, `forDir` | `TestFields.forFile(path)` with `testName == null` and `additionalArgs` extended by one `--name=<regexp>` token. `TestLaunchState → TestFields.run → FlutterSdk.flutterTest` builds the command using the official runner. |

Flutter's public `useRegexp(true)` is misleading for this use case: `FlutterSdk.flutterTest` calls `StringUtil.escapeToRegexp(testName)` and anchors that **single literal**. Passing a union there would match its punctuation literally. The official additional test arguments field is the supported way to pass the union in this pinned implementation. There is no custom runner, raw shell command, source rewrite, skip injection or after-the-fact result filtering.

Both configuration models accept one file/directory, not a file → individual filter mapping. A single directory regexp could match the same full name in a hidden file, or cross package boundaries. Directory/Run All therefore retain explicit sequential file sessions: one native execution per included file, never one execution per included test.

Run All is orchestration, not one shared RunConfiguration. The same planner handles project/root and directory actions. FULL/PARTIAL/EMPTY is determined independently per entrypoint, even when the overall root is PARTIAL:

| File visibility | Execution plan |
| --- | --- |
| A: group A test 1 included, test 2 excluded | Native file A with an exact filter for `group A test 1` |
| B: group B tests 3 and 4 included | Ordinary native Run File B, no name filter |
| C: all descendants excluded | No configuration or execution |

Several included groups within A still share A's one filter. No group name/filter spans unrelated files. The service waits for a successful `processTerminated` event before starting the next file; failure, cancellation or a failed launch clears the remaining queue. This sequential default applies to integration tests as well.

Native sandbox verification exposed a cancellation bug: Flutter can emit `processWillTerminate(willBeDestroyed=true)` and then exit **successfully**. Treating every destruction as Stop discarded all remaining files. The service now checks exit code plus `ProcessHandler.TERMINATION_REQUESTED` at completion. Inspection of IDEA 253's `ExecutionManagerImpl.Companion.stopProcess` confirms the native Stop path sets that public key before destroying the process. Normal Flutter cleanup no longer cancels the batch; explicit Stop still does, including when the process reports exit code zero.

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

Enabled global argument values and parsed native template arguments are merged as an ordered token list before adding the trusted generated selector. Disabled entries stay persisted but never enter the request or native configuration. User/template name/path selectors remain prohibited. Existing user configurations/templates are never mutated.

Flutter 95 uses `additionalArgs.trim().split(" ")` without quote handling. The adapter therefore places one reversible, whitespace-free transport token per logical value in that native field. A narrowly scoped IntelliJ `CommandLineEnvCustomizer` expands only those tokens into separate `ProcessBuilder.command()` elements immediately before the process starts. This preserves spaces, quotes, Unicode, ordering and duplicates without a shell string while keeping the official Flutter configuration, runner, console and rerun lifecycle. Dart receives `ParametersListUtil.join(tokens)` and its native parser reconstructs the same logical values.

Exact full names are trusted only for supported static descriptions and static enclosing descriptions. The public PSI unquoting API does not decode Dart escapes or triple-string newline rules; non-raw escaped strings, triple strings and dynamic prefixes are conservatively unknown. Partial selection in such a file fails closed. Annotations identify runnable wrappers but cannot prove arbitrary runtime name transformations: wrappers must preserve the standard group/description naming contract; arbitrary dynamic registration/name rewriting is not supported. No runtime discovery or Dart-code evaluation is added.

Files still load and groups register normally under the native runner; name selection prevents excluded **test bodies** from executing, not module-level initialization or shared setup needed by included tests. Native flags such as tags/sharding can further narrow the included names. The sequential queue retains its existing stop-on-failure/cancellation behavior.

## Verification (2026-08-29)

115 automated tests pass. Coverage includes FULL/PARTIAL/EMPTY, nested groups, file/directory/Run All, hidden ancestors, duplicate and similar names, unknown names, full-name whitespace, regex metacharacters, control characters, native tokenization, structured argument editing, persistent settings/XML, temporary-search independence, global arguments, template non-mutation and whole-batch rejection. Multi-file regressions explicitly cover mixed FULL/PARTIAL/EMPTY files, several groups per file, cross-file duplicate names, directory boundaries and root/directory planner parity. Native process-handler tests distinguish successful Flutter-style destruction, ordinary success, failure and explicit Stop with exit code zero. Incremental edit/run coordination is covered separately in [edit-and-run.md](edit-and-run.md).

The separate `tools/sandbox-smoke` plugin opts in only on `test_explorer_filtered_smoke`. Its safe fixture has A/B plus C/D that deliberately fail if run. Real sandbox executions using the installed official runners verified A/B ran, C/D did not, Flutter's global dart-define reached the test body, each runner used one execution and the source bytes were unchanged. This passed first with simple A/B names and then with punctuation, quotes, spaces, Cyrillic and emoji. Backslash/control-character escaping is covered by unit tests; the pinned Flutter name extractor does not recognize raw strings as individual targets. Reports and captured native outputs are under `build/test_explorer_filtered_smoke/`. No application acceptance/integration tests were launched.

The multi-file harness separately opts in on `test_explorer_multifile_smoke`. After the cancellation fix, actual native lifecycle events confirmed exactly two non-overlapping Flutter sessions: filtered file A, then unfiltered file B; excluded file C never launched. Test 1 and tests 3/4 executed with the global define; hidden test 2 and file C's test body did not execute. All three source files remained byte-for-byte unchanged. Root and directory resolved the identical plan. Evidence: `build/test_explorer_multifile_smoke/multifile-result.txt` (`SUCCESS`) and `multifile-output.txt`; `before-fix-progress.txt` records the original first-file-only failure.
