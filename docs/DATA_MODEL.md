# Library and reading data model

Room is the source of truth for the catalog and reading state. Bytes live in app-owned storage. Preferences live in Preferences DataStore. Book IDs survive export and restore. File identity comes from the content digest. [Room](https://developer.android.com/training/data-storage/room) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

## Room entities

| Entity | Principal fields and constraints |
| --- | --- |
| `Book` | `bookId` UUID primary key, `assetSha256` unique and required FK, title, ordered author names, optional language, publisher, embedded identifier, optional source display filename, `importedAt`. Use a filename-derived fallback title. Treat publisher identifiers as metadata. |
| `ImportedFile` | `sha256` primary key, detected format, verified byte length, state (`PRESENT` or `MISSING`). Create a row even when a lightweight restore supplies only expected asset metadata. Derive the file path from validated digest and format. Verify that a present row has its payload when opening. |
| `ReadingState` | `bookId` primary key and FK, nullable `lastReadAt`, nullable `positionUpdatedAt`, nullable locator envelope, section label and nullable normalized overall progression. Owns the bookmark-independent resume checkpoint and the history timestamp. |
| `Bookmark` | `bookmarkId` UUID primary key, indexed `bookId` FK, locator envelope, optional label and section snapshot, `createdAt` and `updatedAt`. |
| `Collection` | `collectionId` UUID primary key, display name, `createdAt` and `updatedAt`. Use collection ID to identify a collection when restoring. Two devices can independently create collections with the same label. |
| `CollectionBook` | Composite primary key (`collectionId`, `bookId`) with FKs and a reverse index on `bookId`. A book can belong to multiple collections. |

Use UTC epoch milliseconds for timestamps and store percentages as finite fractions between 0 and 1. Format timestamps and percentages for display. Use deterministic ordering with a book ID tie-breaker. Index `ReadingState(lastReadAt, bookId)` and frequently sorted catalog columns as needed. Start with a title/author search on normalized comparison fields and use parameterized queries with literal wildcard handling. Benchmark representative libraries before adding FTS. Collection membership, reading state and bookmarks cascade when the user explicitly deletes a book and its records. Removing a file while keeping records sets `MISSING`; deleting a collection removes its memberships. Treat the internal cover thumbnail as a regenerable derivative keyed by digest. [Android database storage](https://developer.android.com/training/data-storage/room)

### Identity, availability and duplicates

- One logical book per exact file in v1. A repeated SHA-256 returns the existing book and preserves its data. Let the unique constraint arbitrate races as well as checking early to avoid repeated parsing. Identical titles or metadata can belong to different editions.
- Importing bytes that match a missing digest repairs the existing book and regenerates its cover. Explicit relink verifies both digest and readable format. A modified edition imports as a separate book with its own locator namespace. Preserve the missing record and explain the mismatch if relink receives a different edition.
- Keep missing books visible in Library and in the history and Home queries they qualify for. Show stored progress and offer relink there. A missing or corrupt thumbnail falls back to a placeholder. Keep availability reconciliation out of scrolling list queries.

## Reading state and query semantics

- On successful initial display, upsert by `bookId` and refresh `lastReadAt` even when the user immediately exits. A failed open or cancelled import never creates a history entry. A valid initial location can initialize the checkpoint for a first read. A provisional location cannot overwrite a saved checkpoint.
- Treat the latest checkpoint as a unit containing the locator, `positionUpdatedAt`, section label and total progression. Prefer the locator title or a matching TOC label, with a resource-level label as fallback. Whole-book percentage remains unknown when the engine provides no valid estimate. Reading backward can lower the saved percentage.
- Use a single ordered writer for the active reader and separate activity time from position changes. On real movement or a user-directed jump, update position and activity together. Coalesce rapid changes with a bounded periodic checkpoint plus best-effort background/exit flush. Ignore stale emissions from replaced sessions and reflow transitions. Prevent old in-flight writes from crossing a restore commit by ending or suspending the reader and draining its writer.
- History joins `ReadingState` to `Book` and `ImportedFile` and sorts by `lastReadAt` descending, then `bookId`. It has one row per read book by construction. Home uses the same query with `LIMIT 5`. Library left-joins reading state so unread books remain visible and searches titles/authors within the selected collection or across the library.
- The History trash action atomically clears the timestamp, locator and section, and sets progression to zero. Home and History exclude the reset book until a successful open. Undo restores the full previous reading state if that reset still applies. Book metadata, covers and payloads are independent of this operation.

### Locator envelope

Use an app-owned envelope with `kind` (`readium-epub` for v1), `version` (`1`), `assetSha256` and the full Readium locator JSON. The book's immutable digest binds a checkpoint to the publication that produced it. Preserve the locator payload, including optional fragments, resource-relative progression and text anchors emitted by Readium. Store section and overall progress alongside it for list queries. Never derive a durable checkpoint from screen page indexes or pixel offsets. An unsupported locator version can still retain its opaque bounded payload, display metadata and reading time. Reader recovery then offers a chapter or progression fallback using only recognized values for the matching asset. [Readium locator specification](https://readium.org/architecture/models/locators/) · [Navigator position persistence](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/navigator/#reading-progression)

## Settings and evolution

Reading preferences are global keys in the existing `reader_appearance` Preferences DataStore. The `reader_theme` string and `fullscreen_reading` boolean retain their keys and defaults. The font family, weight, and alignment are stored as enum strings. Font size, line spacing, paragraph spacing, and horizontal margins use integer hundredths. Vertical margins use integer dp. Missing line and paragraph spacing use publisher defaults. `reader_scroll` and `reader_publisher_styles` are booleans. Validation treats unknown tokens and invalid ranges as defaults. Reset removes only the reading keys in one DataStore transaction, leaving the Library preferences, books, and Room reading records intact.

`reading_state.locator` retains its existing versioned JSON envelope. It includes the engine locator, an optional reflow quote scoped to the same EPUB resource, and a viewport signature that includes the effective layout preferences. A changed signature enables quote-based resume after restart. The original engine locator remains the fallback. No Room migration is required.

Version Room migrations, portable backup data and locator envelopes independently. Use exported Room schemas and migration fixtures, with no destructive migration for user data. Backup DTOs represent the logical catalog and maintain stable relationships even when a database migration renames tables or fields. [Backup format](BACKUP_FORMAT.md)
