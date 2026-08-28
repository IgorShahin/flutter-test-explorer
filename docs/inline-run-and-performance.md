# Inline Run and incremental discovery

Target inspected: IDEA 2025.3.5 / build 253.33514.17, Dart 508.1.0, Flutter 95.0.0. The choices below were checked against the installed binaries with `javap`, not assumed from another IDE version.

## 1. Inline action component

`InlineRunTree` combines IntelliJ `Tree`, `ColoredTreeCellRenderer` and `ActionButton` with `ActionButtonLook.INPLACE_LOOK`. There is no `TreeCellRendererWithButton` in the target SDK. The native button is permanently visible on runnable files/groups/tests/widget tests, with a 24 logical-pixel hitbox, hover indication and tooltip. Node labels remain native renderer text; `TreeSpeedSearch` stays installed.

Swing tree renderers are paint stamps, not live button children. The tree therefore handles button-region mouse events before normal JTree processing, resolves the actual clicked path and dispatches its model ID. It never uses the renderer's last painted node. A press/release on the same icon runs once, a second double-click release does not run again, and normal row clicks/double-clicks retain selection/navigation. The keyboard/context-menu paths remain available for accessibility. Swing accessibility must be initialized via `getAccessibleContext()`; a sandbox-discovered expandable-tooltip regression has a dedicated test.

## 2. Shared execution

Toolbar Run All, Run Selected, context menu, Shift+F10 and inline Run all call the panel's `runNode(id)`, then the unchanged `TestExecutionService.run(completeModel, id)`. UI code constructs no CLI commands or configurations. Persistent exclusions, global arguments, stale-target checks, partial-file/group rejection and the sequential run queue apply identically. All five existing toolbar actions remain. No inline directory action is added.

## 3. Review and measured bottlenecks

Before this change, every relevant VFS event and outline notification queued `discoverProject()`: package-root lookup, test-root VFS walks, PSI discovery of every candidate, full immutable tree construction, and replacement of the complete Swing model. Filtering already avoided PSI, but recursively transformed and recreated the Swing tree on EDT. Nested calls also reclassified/resolved parent groups repeatedly. Expansion, selection and scrolling were lost on replacement.

The existing discovery was already a background read action; the problem was repeated global work and EDT tree replacement, not a claim that all PSI parsing ran on EDT. Classification is now memoized within each file's read action.

Sandbox profiling also caught a proposed optimization that was slower: independently traversing the Flutter application's import graph cost about 1 second for this sample. It was removed for Flutter, whose analyzer already supplies dependency-aware outlines. Only Dart-only files use the cached index graph.

## 4. Cache and model update

`DiscoveryCacheState` is an immutable path-keyed snapshot of relative path, source stamp, outline revision, nullable result and dependencies. Loaded documents are authoritative, including unsaved edits; otherwise the VFS modification stamp is used. A save of the same loaded document does not cause a second content invalidation. Empty/unrecognized results are cached, and outline revision changes can turn an empty result into a runnable one without a source edit. No PSI is retained in the cache.

`CachedValuesManager` with the global `PsiModificationTracker` would invalidate unrelated files. An explicit per-file snapshot is a better fit for this mixed document/VFS/analyzer dependency set. All mutation is published transactionally after a successful non-blocking read; canceled retries cannot publish half a model.

`TestModelPatcher` builds changed-file branches in one batch and copies only their ancestors. Unchanged branches retain reference identity. `TestTreeUpdater` sends targeted `DefaultTreeModel` insert/remove/node-change events and skips identical branches. It preserves user expansion/selection IDs, including temporarily filtered-out nodes, and viewport position where meaningful. A rename or changed test identity cannot retain that exact selection. Initial population and genuinely changed/filtered branches still require Swing insertion work.

## 5. Invalidation and scheduling

- One disposable VFS listener handles content/create/delete/copy/rename/move events; both old and new paths are invalidated. Directory changes reconcile just that subtree when the package roots remain known. Hidden/build-directory noise is ignored, except relevant package configuration.
- One disposable editor document listener catches unsaved edits. One project-root listener handles SDK/module/root changes.
- Flutter subscribes to the entire candidate set through the official analyzer. Preparing one cache miss never unsubscribes other files. Distinct outlines carry monotonic local revisions; a duplicate payload/stamp does not queue duplicate discovery. Analyzer reconnects resubscribe and invalidate stale results.
- Dart-only files use reverse transitive import/export/part dependencies. The existing Dart indexes and URL resolver are consulted without helper PSI. Direct dependency results are themselves cached by source stamp. Missing relative imports remain tracked so later creation invalidates their importers.
- `MergingUpdateQueue` coalesces discovery for 350 ms, search for 120 ms. Events arriving during discovery accumulate for the next batch; they do not cancel/restart it merely because another event arrived.
- `ReadAction.nonBlocking().inSmartMode(project).withDocumentsCommitted(project)` postpones index-dependent work without blocking EDT. The old model stays visible in Dumb Mode with a busy indicator. Only a successful batch reaches EDT; failures keep the last model and retry on a later event/Refresh. Both queues, listeners, promises and analyzer subscriptions are tied to panel disposal.

## 6. Reused indexes/APIs

Candidate selection: `FilenameIndex`, `FileTypeIndex`, `GlobalSearchScopesCore.directoryScope` intersected with project scope, and project-content membership. Dart dependency data: `DartImportAndExportIndex`, `DartPartUriIndex`, `DartUrlResolver`. Flutter recognition: the existing official analyzer-outline adapter and configuration validation, unchanged in authority.

`DartComponentIndex`/`DartSymbolIndex`/PSI stubs index declarations, not a complete catalogue of semantic test registration call sites or custom annotated wrapper calls. `PsiSearchHelper` cannot supply that runnability guarantee either. No custom FileBasedIndex/StubIndex is introduced.

The [platform indexing documentation](https://plugins.jetbrains.com/docs/intellij/file-based-indexes.html) and [threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html) support this indexed, cancellable background-work design. Coroutines are a supported alternative; the existing non-blocking read API is retained rather than introducing a second concurrency model into the plugin.

## 7. Remaining full operations

Full **indexed candidate reconciliation** occurs on initial discovery, explicit Refresh, SDK/module/root/package configuration changes, analyzer reconnect, and directory changes outside known test roots that could add/remove packages. Reconciliation alone reuses matching cache entries; explicit Refresh and semantic environment changes force validation. Ordinary test edits, saves and outline updates do not enumerate candidates again.

Search/visibility may traverse the cached model, but do so in the background and never request candidate PSI. A normal run does not rediscover files; the existing service rejects stale targets and asks for refresh. Initial results are delivered as one batch, not streamed file-by-file.

## 8. Reproducible measurements and diagnostics

`IndexedDiscoveryTest.testWarmPsiBenchmarkFullDiscoveryVersusSingleFileCacheUpdate` compares the old-style full pipeline with incremental processing on the same warm PSI: 200 entrypoint files, 1,000 leaf tests, two warmup iterations and the median of five measured iterations. Recognition is injected, so this measures discovery/tree-model work, **not SDK startup, analyzer latency, run-configuration validation or Swing painting**. Timing is reported but not asserted; deterministic work counts are asserted.

The final verification run on this host measured 7.79 ms full versus 0.19 ms incremental; analyzed PSI files fell from 200 to 1. Repeating an unchanged event gives one cache hit and zero analyses. Unrelated Dart edits and filter changes perform zero discovery analyses. Timings vary with host/JIT load; rerun the benchmark to compare changes.

Sandbox smoke check (2026-08-28, 21:58): five indexed candidates, four displayed files, including the acceptance entrypoint recognized as a group plus six tests. After analyzer outlines arrived, the five-file incremental batch took 23.02 ms total and its EDT tree patch 0.27 ms. The first cold batch took 580 ms; analyzer startup itself took approximately 18 seconds from IDE launch and is not part of the warm benchmark. No plugin exception was logged in the final sandbox run. The earlier independently computed Flutter import graph had taken 993 ms for this outline batch, which prompted its removal. These are observed smoke measurements, not latency guarantees.

Final automated verification: 48 tests passed; plugin build, structure and project-configuration verification passed. No real application acceptance tests were launched.

Enable only development logs with `./gradlew runIde -PtestExplorerDebug`, or add `#dev.igorshahin` in IDEA's **Help → Diagnostic Tools → Debug Log Settings**. Normal production logs do not receive per-file/per-refresh messages. Debug output records candidate count, actual discovery calls, hits, invalidations, cumulative full/incremental batches, discovery and total batch duration, filter duration, visited Swing nodes and EDT patch duration. Successful-batch metrics do not include canceled read-action attempts; detailed debug lines reveal those retries.

## 9. Limits and verification boundary

- File discovery is incremental, but an edit to a shared Dart-only dependency can legitimately invalidate several importing test files. Map snapshots and cached-model filtering still have costs proportional to their size; this is not a claim of constant-time processing for every operation.
- Flutter helper changes become visible when the official analyzer publishes the affected file's outline. The upstream outline protocol has no document-version field; existing stamp/length checks and fail-closed recognition remain. Runtime/device readiness and successful compilation are not guaranteed by discovery.
- The Dart-only graph covers URIs exposed by the installed indexes within the opened project. SDK/package configuration changes invalidate all; arbitrary edits inside external dependency caches are not continuously tracked. Use Refresh for such unusual changes.
- Cache and tree state are in-memory for the panel lifetime, not persisted across IDE restarts. Persistent visibility/settings are unchanged. Directory inline buttons, result badges, coverage and progressive initial streaming are outside this increment.
- Native UI fixtures verify button dispatch/selection/navigation, initial tooltip rendering, subtree identity and state preservation. Configuration tests do not launch the user's acceptance suite. A real device/test-process end-to-end run is not claimed.

## Refresh regression follow-up

The 22:05 sandbox log exposed an error not covered by the initial smoke check: Refresh completed in 27–51 ms but recognized zero calls in all five files. `PsiFileImpl.clearCaches()` had invalidated the stored PSI counters even though the Dart source and analyzer outlines remained current. Because the source had not changed, waiting did not produce new outline notifications.

`FlutterOutlineRefreshTest` first reproduced this on the old implementation (one recognized call became zero after `clearCaches()`). The adapter now validates a source-content SHA-256 rather than a PSI cache counter. Tests cover unchanged-source cache clears, five repeated full refreshes, same-length unsaved edits, recovery on a fresh outline and duplicate-outline suppression. Source hashing happens only during outline ingestion/validation, not text filtering or ordinary cache hits. Cached entries also distinguish pending analyzer data from a genuinely empty result, and the panel displays a waiting message that resolves automatically on analyzer updates.
