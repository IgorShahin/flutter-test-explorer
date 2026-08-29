# Automatic incremental discovery before Run

## Cause and changes

The old run guard compared `PsiFile.modificationStamp`. IDEA can change that counter merely by clearing PSI caches, without changing source. Run actions also disabled/dropped clicks during discovery, or prepared from the old model before its debounced update ran.

- `TestSourceStamp` unifies discovery, cache and execution versions: cached editor document first, otherwise VFS. Unsaved edits count; PSI-only invalidation does not. Flutter outline digests also read the current document rather than potentially uncommitted PSI text.
- The existing document listener records dirty paths synchronously on EDT. The existing `MergingUpdateQueue` resets its 350 ms debounce on additional edits. Each transaction still uses indexed candidate reconciliation for those paths only, `DiscoveryCache`, and `TestModelPatcher`; ordinary edits do not set `rescan`/`invalidateAll`.
- While official analyzer data is pending, the cache may retain its last branch with `awaitingAnalysis=true`. This preserves tree/selection identity but does **not** authorize launching stale tests.
- If the Flutter analysis service coalesces an editor update without publishing a replacement outline, the adapter re-subscribes that one file once for the current source digest. This asks the official analyzer for current data; it is neither polling nor a project rescan.
- `RunDiscoveryBarrier` reads source/outline versions in a committed, smart-mode non-blocking read action. It reuses in-flight updates, requests only stale paths, and waits for document/analyzer/publication events. No polling, full-refresh shortcut or second discovery implementation is introduced. Unchanged Run needs only a version check, not rediscovery.
- All Run actions remain available while background discovery/indexing is pending. They enter the same execution service/barrier. The selected stable ID is looked up in the current immutable model; no old PSI element/offset is used to construct a target.
- Source is checked again while preparing configurations and immediately before launch. If another edit intervenes, preparation waits/replans again. A queued-file edit also re-plans the remaining scope; completed file paths are not rerun. Persistent visibility and global arguments retain their per-request snapshot.
- Native runners read files on disk. Immediately before native launch, the current target document is saved using `FileDocumentManager.saveDocument` in a write-safe non-modal EDT callback, then rechecked. Discovery alone never saves or rewrites source. A failed save blocks launch.

Manual Refresh still explicitly reconciles and invalidates the whole project. SDK/package/root changes and analyzer reconnection retain their existing full-refresh behavior. None is synthesized for a normal Run.

## Safe failure cases

If a selected test was renamed/deleted or stopped being runnable, its old ID is not redirected to another test or broadened to a file/group run. The tree updates automatically and the user can select the current target. A stalled analyzer/indexer times out the pending Run after 30 seconds with a diagnostic, rather than running stale data or launching later unexpectedly. Closing the project/panel disposes waiting work. Runtime compilation/device failures still belong to the official runners.

## Verification

The 103-test suite covers document versus PSI invalidation, same-length unsaved edits, updated offsets with stable visibility IDs, document loading without edits, clean runs without rediscovery, same-turn pending edits, in-flight batches, analyzer-pending snapshots, unrelated-file isolation, Dart dependency invalidation, async delivery of the current model exactly once, renamed/removed target safety, preserved pending branches and a 40-edit burst resulting in one file update. Existing tree-state, filter, native configuration and multi-file queue tests remain.

The opt-in `EditRunSmoke` harness uses only `build/test_explorer_edit_smoke`. It edits the actual IDE document without saving/committing and immediately invokes the tree's real Run action in the same EDT turn. It checks updated native stdout, fresh source offsets, persistent exclusion state, unchanged neighboring Swing-node identity/expansion, and no hidden test execution. It then edits the next file while Run All's first file is running, checks that the latest second-file body executes without rerunning the completed first file, and verifies a subsequent unsaved rename updates the tree without Run/Refresh. See `edit-run-result.txt`, `edit-run-output.txt` and the debug discovery counters in sandbox `idea.log`. These fixture edits are intentional verification actions, not source rewriting performed by the explorer.
