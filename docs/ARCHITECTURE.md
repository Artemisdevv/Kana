# Android ebook reader architecture

The workspace inspection on 23 November 2026 found an empty directory, with no Android project or Git checkout. This document proposes the initial design. Documentation establishes the API and toolchain candidates. The reader and build still need on-device and CI verification.

## Application structure

Start with one Gradle application module and feature packages. Use Kotlin, Compose with Material 3, screen-level ViewModels and unidirectional state flow. Expose immutable UI state through `StateFlow` and collect it with lifecycle awareness. Pass book IDs through navigation and load their current data from repositories. Wire dependencies in a small application container. Extract a domain operation when it coordinates multiple stores or has policy of its own, such as import or restore. A separate module earns its place when it has a distinct dependency or test boundary that a package can no longer serve. [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)

| Technology | Decision and tradeoff |
| --- | --- |
| Compose and Material 3 | Use for screens, reader controls and theming. Reader content comes from Readium. Compose interop is confined to the reader. |
| Room | Store library, relationships, bookmarks and current reading state in SQLite. Use transactional DAO operations and observable queries. Export schemas and test migrations. |
| DataStore | Use Preferences DataStore for the small set of global reader and app preferences. Use explicit keys, types, defaults and value validation in a settings repository. A typed serializer becomes worthwhile if the preferences become structurally complex. |
| Coroutines and Flow | Use structured concurrency for provider I/O, parsing and archive tasks, and Flow for local state. Start with cancellable foreground import and backup operations. Add a durable scheduler only if users need operations to continue after leaving the app. |
| Readium Kotlin Toolkit | Use the BSD-3-Clause toolkit from Maven Central. Start with shared, streamer and navigator and check the resolved dependencies and release APK size. The documented fragment navigator has an established integration path. The Compose-native web navigators are experimental and can be reassessed later. |
| Storage Access Framework | Use `ACTION_OPEN_DOCUMENT` for import and restore and `ACTION_CREATE_DOCUMENT` for export. Stream provider content into or out of app-owned files. App-internal storage is reliable for offline access and consumes device space. Uninstall clears the local library. |

[Room](https://developer.android.com/training/data-storage/room) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) · [Compose BOM](https://developer.android.com/develop/ui/compose/bom) · [Readium modules and license](https://github.com/readium/kotlin-toolkit/tree/3.4.0) · [Readium web navigators](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/web-navigators/) · [SAF](https://developer.android.com/training/data-storage/shared/documents-files) · [App-specific storage](https://developer.android.com/training/data-storage/app-specific)

Home is the default destination. Query the latest five books with successful reader activity. Render the first as a featured card and up to four smaller cards below it, with cover, title, progress and a resume action. A missing file substitutes a relink action and retains its place in the list. Library supports grid and list, metadata search, sorting and collections. History displays the latest position, chapter, percentage and activity timestamp for each read book. Settings owns global reading preferences, app appearance, library management and backup/restore. The reader applies and persists preference edits through the shared settings store.

## Publication import and storage

Open the selected URI while its grant is valid. Treat MIME type, filename and provider-reported size as hints. Stream into bounded internal staging while computing SHA-256. Check whether the digest is already present, validate the staged EPUB with Readium, then extract metadata and a downsampled cover. A missing or unusable cover yields a placeholder. Reject unrecognized, unreadable or unsupported protected books with separate actionable errors. Validate budget while streaming and release streams and publications on completion or cancellation. Once imported, the app reads its own immutable file even if the source URI disappears. [SAF document access](https://developer.android.com/training/data-storage/shared/documents-files) · [Readium getting started](https://readium.org/kotlin-toolkit/3.4.0/guides/getting-started/)

Store payloads under a digest-derived path in app-private internal storage. Use same-volume staging, synchronize the completed file and promote it before committing Room references. Serialize catalog and file mutations through the same in-process gate, retain open-reader and export leases until use ends, and reconcile abandoned staging files and unreferenced payloads after startup recovery. Validate paths from app-owned identifiers. Keep an imported book's metadata and reading records if its bytes go missing. [Data model](DATA_MODEL.md) · [Backup and recovery](BACKUP_FORMAT.md)

## Reader integration

1. Open a reader destination with a book ID. Resolve its managed asset and load global preferences and the persisted locator. Open the local asset as a Readium `Publication`. Keep the publication in a scoped reader session and close it once the navigator no longer needs it.
2. Default to a dedicated `FragmentActivity` for the Readium integration, with a Compose overlay for controls and a parent fragment hosting `EpubNavigatorFragment` in `FragmentContainerView`. Hosting the fragment in the main Compose destination would keep navigation in one activity and couple reader restoration to that destination. The dedicated host gives the reader explicit control of fragment restoration at the cost of an activity boundary. Use the documented `EpubNavigatorFactory` and install the child fragment factory before parent restoration. On a cold process restore, follow Readium's dummy-factory recovery path, remove the restored navigator, then reopen by saved book ID and build a fresh navigator with the durable locator once the asset loads. Recomposition updates controls and preferences on the same reader session. [Readium navigator guide](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/navigator/) · [Android fragment interop](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose#fragments-in-compose)
3. Pass the saved `Locator` as `initialLocator`. Observe `currentLocator` while started. Use supported navigation APIs for TOC entries and bookmarks, and derive book-wide progress from `totalProgression` where available. Use Readium's directional input support for edge taps and keyboard controls. Let the engine handle pagination and scrolling. Reserve unconsumed center taps for reader chrome, and test text selection, links, RTL, accessibility and Android back gestures together. [Navigation and progression](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/navigator/#reading-progression)
4. Persist app-owned global settings and map them at the reader boundary to `EpubPreferences`. Pass them as `initialPreferences` at navigator creation and send changes via `submitPreferences`. Map font family and scale, page margins, line and paragraph spacing, alignment, reader theme and scroll mode. Use supported font families initially and check script fallback. App theme and reader theme have independent settings. Default to paginated mode with a vertical scrolling option for reflowable books. [Navigator preferences](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/preferences/) · [EPUB font families](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/epub-fonts/)

The reader and Settings → Reading Preferences share the same Appearance, Text, and Layout controls and Preferences DataStore. The reader opens a single modal sheet with the entry point’s tab selected. It hides the reader bars and measures the sheet independently of the EPUB viewport. Theme updates retint the EPUB and the sheet. The fullscreen preference applies on the next reader open. The layout defaults to paginated reading and publisher typography. Disabling publisher formatting enables line spacing, paragraph spacing, and alignment overrides. Vertical margins are additional padding around the EPUB viewport, inside the activity’s system insets. Fixed-layout EPUBs expose appearance settings and keep their authored page layout. Font choices use Android’s generic families and the fonts already distributed by Readium. [Preference constraints](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/preferences/#preference-constraints)

### Reflow and durability

Save the serialized native locator alongside an optional visible text quote and a viewport signature. The reader pins one checkpoint for a series of edits, submits preferences to the active Readium navigator, waits for the new layout, and returns to the quote using `go`. After a restart, the saved quote remains authoritative until the reader navigates. Slider previews coalesce while dragging and the release commits the last value immediately. Readium may rebuild its internal pager when switching reading modes. Startup and reflow discard provisional locator emissions, and a timeout leaves the durable checkpoint available. If a resource cannot provide a text quote, resume falls back to the element or native locator. [Locator model](https://readium.org/architecture/models/locators/)

Use the strongest anchor the engine provides. Resource-relative progression is approximate when richer anchors are unavailable. If initial navigation fails, try the saved resource at its start, then a publication-wide percentage when valid, then the book's opening location. Surface an approximate recovery when relevant and preserve the original checkpoint until the user advances. Persist meaningful changes at a bounded interval while reading and attempt a final flush on exit/backgrounding. Process death can lose the last uncommitted movement. The first milestone measures that window and whether the same passage remains visible. [Reading state semantics](DATA_MODEL.md#reading-state-and-query-semantics)

## Build baseline

| Setting | Recommendation |
| --- | --- |
| `minSdk` | API 24, the minimum documented for Readium 3.4.0. Smoke-test the selected AndroidX pins on API 24. Lowering the floor would mean using and qualifying an older toolkit line. |
| `compileSdk` | API 37, as documented for Readium 3.4.0. |
| `targetSdk` | API 36 initially, the Google Play submission floor for phone apps as of 23 November 2026. Test on API 37, review its target-gated behavior and raise the target once regressions are resolved. Recheck Play policy before release. |
| Kotlin and Java | Start with AGP's built-in Kotlin and Kotlin 2.4.20 per the AGP 9.6 compatibility table. Match the Compose compiler plugin to Kotlin, select a compatible KSP for Room, and use JDK 17 for Gradle and Java/Kotlin JVM target 17. Review core library desugaring when selecting Java APIs for API 24. |
| Build tooling | Candidate AGP 9.6.0 with Gradle Wrapper 9.8.2 and an Android Studio build that explicitly supports the chosen AGP. Use Kotlin DSL, a version catalog for pinned coordinates and a stable Compose BOM. Resolve and build the complete dependency graph in the first milestone before accepting the pins. |

Readium lists its compiler and Gradle versions for source integration. Consume its Maven artifacts and validate metadata compatibility, debug and minified release builds independently. [Readium compatibility table](https://github.com/readium/kotlin-toolkit/tree/3.4.0#minimum-requirements) · [AGP compatibility](https://developer.android.com/build/releases/agp-9-6-0-release-notes) · [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) · [Compose compiler setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) · [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk) · [Play target policy](https://support.google.com/googleplay/android-developer/answer/11926878)

## Proposed project layout

```text
docs/
gradle/                         # wrapper and version catalog
app/
  schemas/                      # versioned Room schema exports
  src/main/
    AndroidManifest.xml
    res/
    kotlin/<app-package>/
      app/                      # activity, navigation, dependency wiring
      ui/theme/
      feature/{home,library,history,reader,settings}/
      data/{library,preferences,storage}/
      publication/              # Readium parsing, navigator bridge, locators
      backup/                   # archive schema and restore orchestration
  src/test/
  src/androidTest/
build.gradle.kts
settings.gradle.kts
```

## Risks and recommended boundaries

| Risk | Plan |
| --- | --- |
| EPUB and WebView behavior varies by book and device | Verify EPUB 2 and 3, publisher CSS, long chapters, mixed images, fonts, RTL and fixed-layout samples. Measure load time and memory on a lower-end device. Keep a single active navigator, downsample covers, avoid whole-file buffering and avoid polling. |
| Imported publications may reference remote or scripted content | Treat imported books as untrusted. Verify local-only resource loading, link policy and toolkit WebView isolation with fixtures. Block publication-initiated external navigation and remote resources using supported boundaries, and verify the merged manifest and offline behavior. Show an explicit unsupported state for cases outside the approved offline policy. |
| Position drift on reflow, recreation or toolkit upgrades | Gate releases on the fixture tests in the roadmap. Test archived locators against candidate upgrades. Use reflow-preserving recreation as a fallback spike only if live changes fail the acceptance gate. |
| Storage grants, space, cancellation and output providers | Read through SAF, validate actual byte counts, stage on internal storage and reconcile crashes. Exercise local, removable and unavailable provider cases. Configure platform backup/transfer rules so the app's portable backup owns the recovery contract and staging, private ebooks and incomplete commits are excluded. [Android Auto Backup](https://developer.android.com/identity/data/autobackup) |
| MOBI differences and licensing | Defer to a separate spike after EPUB release criteria. Readium's supported-format list does not include MOBI. Compare offline conversion to EPUB against a dedicated rendering integration. Default to evaluating conversion first to reuse the EPUB reader, while testing MOBI7, KF8/hybrid fidelity, stable derived locators, native footprint, ABI and 16 KB page-size compatibility. `libmobi` is an Android-buildable LGPL candidate and requires a licensing and maintenance review. Keep DRM-protected Kindle books outside the proposed scope. [Readium supported formats](https://github.com/readium/kotlin-toolkit/tree/3.4.0) · [libmobi](https://github.com/bfabiszewski/libmobi/) |

Open decisions are the practical anchor accuracy on real fixtures, the fixed-layout compatibility bar, and the size limits for large books and backups. The roadmap makes those decisions measurable before feature work expands. [Roadmap](ROADMAP.md)
