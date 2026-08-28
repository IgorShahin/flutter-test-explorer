<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Flutter-test-explorer Changelog

## [Unreleased]

- Fix Refresh losing all Flutter tests after IntelliJ clears PSI caches: outline freshness now uses source-content SHA-256, not the PSI cache counter.
- Distinguish pending Flutter analysis from a completed discovery with no runnable tests; pending results update on analyzer notifications.

- Add native inline Run buttons for runnable files, groups and tests, preserving all toolbar actions.
- Cache discovery per file and analyzer revision; coalesce targeted document/VFS/outline updates.
- Select candidates with scoped platform indexes and use Dart indexes for Dart-only dependency invalidation.
- Patch changed tree branches, preserve UI state, and filter cached models in the background.
- Add debug performance diagnostics, incremental-discovery regressions and native UI interaction tests.
