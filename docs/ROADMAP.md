# Implementation roadmap

## Completed · Android project and UI foundation

The single-module Android app launches to Home. Library and History have empty states, and Settings lists its planned categories. The Material 3 shell follows the system theme and handles system-bar insets.

Gradle dependency resolution, the debug APK and lint passed. On an API 36 Moto phone, installation, launch, tab switching, Settings Up, system Back and state restoration during a light/dark theme change passed. The unit-test task found no test sources.

The foundation uses AGP 9.1.1, Gradle 9.7.1 and Kotlin 2.3.21. The planned AGP 9.6.0 artifact was unavailable during setup. The planned SDK levels remain in place. [Build instructions](../README.md)

## 1. Prove EPUB import and resume

Build a single vertical slice with a file picker, a minimal reader and one durable reading checkpoint. Qualify Readium and the new dependencies against the pinned toolchain, build debug and minified release variants, and launch on API 24 and API 37. Import through SAF into internal storage with a streamed SHA-256 digest. Open the owned file with Readium, persist one Room locator, and wire a persisted global font-size setting through to the navigator. Use the fragment recovery path from the start. [Architecture and candidate toolchain](ARCHITECTURE.md)

Exit when a reader can select an EPUB in airplane mode, turn to a marked passage, change font size substantially and reopen with that passage still visible. Repeat in paginated and vertical scroll modes, after rotation and after killing the app process while it is backgrounded. Confirm that a provisional first-page emission and a reflow transition cannot replace the good checkpoint. Test the app-private copy after removing the original source. Use fixtures with known passage text and record exact anchor versus chapter or percentage fallback. Set a concrete checkpoint loss window from measurements on an API 24 or comparable low-end device.

Run a reflowable EPUB 2, a reflowable EPUB 3 with strong publisher styling, and at least one fixed-layout EPUB. Include a long chapter and a book with RTL text. Exercise publication links and verify that opening the fixtures produces no remote requests. If the fragment cannot preserve the anchor on the core reflow fixtures, test recreation with the same locator or a released Readium fix and rerun the fixture matrix before expanding the UI. [Readium navigator guide](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/navigator/) · [Upstream locator issue](https://github.com/readium/kotlin-toolkit/pull/819)

## 2. Deliver a usable library

Complete import format checks, metadata and cover extraction, digest uniqueness and app-owned file reconciliation. Build Library grid/list, title and author search, sort and collections. Add remove-file and remove-book actions with their distinct data semantics. Handle permission errors, corrupt/unsupported books, cancellation and low disk space.

Exit when two simultaneous imports of the same bytes produce one row, differing bytes with identical metadata stay separate, and a missing item can relink by exact digest. Exercise interruption between file promotion and Room commit. List scrolling should use cached thumbnails and avoid opening books.

## 3. Complete reading and navigation

Add the home destination with one featured and up to four smaller resume cards, the one-entry-per-book history query, TOC, bookmarks, page gestures and reader controls. Add universal font family, scale, margins, spacing, alignment, theme, reading mode and publisher styling to the settings UI. Test light/dark and contrast, actual settings effectivity, TalkBack, system back, RTL and configuration changes.

Exit when reopening a book bumps its single history record to the top and the latest five appear on Home, with valid chapter, timestamp and progress or an explicit unknown state. Missing books keep their history and relink action. Bookmarks and resume return to their passage on the supported fixture set. Document which controls apply to fixed-layout EPUB and qualify the rendering support before release.

## 4. Ship portable backups

Implement the versioned ZIP contract, starting with lightweight export and restore. Then add verified full payload streaming, merge preview, settings replay and recovery after interruption. Wire export and restore through the document picker. [Backup format](BACKUP_FORMAT.md)

Exit when both modes restore on a clean installation on a second device and can be replayed against a populated catalog without duplicate history. Verify copied progress, bookmarks, collections and settings. Confirm lightweight restore yields missing books that relink to their original state. Exercise truncated archives, integrity errors, unknown versions, storage limits, provider cancellation, transaction failure and restart during settings replay.

## 5. Qualify the EPUB release

Exercise migration, low-space recovery and offline operation on the minimum SDK, the target API and the latest supported Android release. Measure cold open, long-chapter page turns and cover-grid scrolling on a representative lower-end device. Review accessibility, fixed-layout expectations, dependency and font notices, merged manifest, platform backup rules and current Play target requirements. Record a repeatable compatibility matrix and release checks. [Play target requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

Exit when the supported corpus meets the position, rendering and recovery criteria and the documented limits match what the release build enforces.

## 6. Evaluate MOBI in isolation

Qualify offline conversion against MOBI7, KF8 and hybrid samples. Compare layout and metadata fidelity, archive behavior, dependency maintenance, licensing and Android native footprint, including 16 KB page-size support if using native code. Decide whether to store original bytes alongside a deterministic, versioned renderer asset or whether a dedicated reader is justified. Specify how stable locators and portable paths survive converter upgrades before expanding the backup schema. [libmobi](https://github.com/bfabiszewski/libmobi/)

Exit the spike with a supported-variant decision, a license assessment and a regression fixture set. EPUB remains independently releasable.
