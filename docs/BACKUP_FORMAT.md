# Portable backup format

Use `.ebookbackup` as the proposed v1 extension. The payload is a ZIP archive with UTF-8 JSON metadata. Use ZIP64 when archive size or counts require it and verify support in the chosen writer and reader. Pick files through the Storage Access Framework and identify backups by their manifest and archive structure. [Android document access](https://developer.android.com/training/data-storage/shared/documents-files)

## Archive contract

| Entry | Contents |
| --- | --- |
| `manifest.json` | Required UTF-8 JSON. Contains `format` (`android-ebook-reader-backup`), integer `version` (`1`), `mode` (`lightweight` or `full`), `createdAt` (UTC epoch milliseconds) and an `entries` list of paths, uncompressed byte lengths and lowercase SHA-256 digests. The list covers every entry except the manifest. |
| `library.json` | Required. App-owned DTOs for books, expected assets, reading states, bookmarks, collections and memberships. Encode relationships by stable IDs and digests. Carry a missing payload declaration for full backups whose source copy is unavailable. |
| `settings.json` | Required. Portable global reading, appearance and library preferences. Use stable keys and JSON types with validated ranges. |
| `books/<sha256>.epub` | One original file per available EPUB in a full backup. The filename, expected asset digest and manifest digest must agree with the verified file bytes. |

Lightweight backups have both JSON datasets and no ebook payloads. Full backups add every available original and list any already missing on the source. Book title, authors, history, progress, bookmarks and collection membership survive either mode. Covers are derived thumbnails and are regenerated from the EPUB. A missing book uses a placeholder until relinked. Archive records exclude database files and journals, provider URIs, absolute paths and device-specific state. [Data model](DATA_MODEL.md)

Verify SHA-256 and byte lengths over the actual uncompressed entry bytes. These hashes detect damage and enforce asset identity. The archive is plaintext and its checksums are unsigned. Explain at export that it can contain reading history, locator text excerpts and, for full backups, original books. Default to standard ZIP without archive encryption to keep v1 portable. [Readium locator specification](https://readium.org/architecture/models/locators/)

Use the archive version independently of the Room schema and app release. Reject unknown versions before changing application state and tell users when an upgrade is needed. Allow additive optional JSON fields within v1 where readers can safely ignore them. Change the version for incompatible or required semantic changes. Future readers should explicitly migrate supported old DTOs in staging and keep round-trip fixtures per format version. Preserve opaque locator payloads as specified by the data model.

## Export

1. Under the shared mutation gate, capture a consistent Room snapshot and DataStore settings and acquire leases on the immutable files referenced by a full export. Release the gate while writing. The leases prevent deletions from breaking the snapshot. Store JSON metadata in bounded staging and compute its manifest hashes before writing.
2. Stream the archive directly to the user-selected SAF output. Use the immutable asset digests in the manifest and verify them as payloads are written. An asset already known to be missing is listed as missing. A read or integrity failure during streaming aborts the export and reports the failure. Treat the export as complete after the ZIP has finalized and the provider stream has closed successfully.
3. Cancellation or provider failure may leave a partial output document. Attempt cleanup if supported by the provider and report an incomplete file when cleanup cannot be confirmed. Release leases on success, cancellation and failure. The restore validator rejects incomplete archives. Direct streaming saves an extra full-size on-device copy while accepting the provider's limits on atomic output. [SAF provider behavior](https://developer.android.com/training/data-storage/shared/documents-files)

## Validation and merge preview

1. Read the archive into bounded internal staging, or stream its entries to staging when the selected ZIP reader can also validate the complete archive. Reject incomplete archives, duplicate paths, unexpected entries, unsafe path forms and unsupported ZIP features. Match entry names to an exact allowlist with digest-shaped payload names. Write only to application-generated files and reject non-regular entries where represented. Apply entry counts, metadata and payload size, actual expanded bytes, nesting limits for JSON, and space budgets before and during staging. Never use an entry path as a filesystem destination. [Android ZIP handling guidance](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)
2. Verify the manifest, file bytes and required fields. Validate IDs, uniqueness, timestamps, range constraints, bounded strings and locator payloads, expected file sizes, relationships and mode-specific payload rules. Reject dangling references and unexpected duplicate identities. Parse and validate EPUB payloads under the same constraints as normal import, including bounds for nested EPUB resources. An invalid archive leaves the current library available and untouched.
3. Summarize additions, duplicate books, relinked and still-missing books, conflicts and settings changes before committing. Recheck the merge under the mutation gate if local data changed after preview and request a refreshed preview when the outcome changes. Finalize disk ceilings after measuring realistic libraries and devices. For the initial test profile, start with a 512 MiB per-book cap, 32 MiB aggregate JSON cap and 10,000 entries, plus a total expanded-bytes budget tied to available internal storage and a fixed tested ceiling. Apply matching export limits and account for staged bytes, headroom and payload promotion.

## Merge rules

Default to merge for clean and populated devices. Add replacement only if a later requirement calls for destructive restore.

| Data | Rule |
| --- | --- |
| Books and files | Match by digest, keep existing book ID and metadata on a match, and remap incoming references. New books keep their incoming IDs. Reject an incoming ID already bound to a different digest and report the collision. Valid incoming bytes repair a missing asset. A missing incoming payload never removes a verified local copy. |
| Reading state | Merge `lastReadAt` independently using the maximum. Choose the newest valid checkpoint by `positionUpdatedAt` and retain its section and progress together. Keep local on timestamp ties. An unsupported locator envelope can be stored as opaque history when it would leave no usable checkpoint. Prefer a usable checkpoint when one exists. Device clock skew can affect which checkpoint wins. |
| Bookmarks | Merge by stable bookmark ID, using the newer `updatedAt` for the same mapped book and keeping local on ties. Reject an ID collision bound to a different mapped book. |
| Collections | Merge by ID. Use the newer name by `updatedAt` and keep local on ties. Take the union of memberships after remapping book IDs. Restoring an older archive can restore memberships previously removed. |
| Settings | Apply the validated backup settings on a fresh install. For an existing install, default to keeping local settings and offer applying the backed-up values in the preview. Backed-up values use current defaults where keys are absent. |

Re-importing the same archive leaves one book per digest and one history row per book. Merge snapshots carry no deletions. Importing a file later with a missing asset's exact digest relinks it and preserves the reading records.

## Commit and crash recovery

1. End or pause active reader writes and acquire the shared mutation gate. Then revalidate the plan against current state, suspend cleanup and promote verified payloads from same-volume staging to immutable digest paths. Reuse verified existing copies and repair missing or corrupt copies safely. New destinations use atomic promotion on the same filesystem, with flushed file data and a read check before commit. A filesystem power-loss guarantee depends on the device. Treat missing live bytes as recoverable through the catalog rather than dropping records.
2. In one Room transaction, merge the catalog and reading data and insert a pending settings record when the plan includes settings changes. A crash or failure before commit leaves the old catalog in place. Unreferenced promoted files can be reclaimed after recovery. A reader reopening after a commit goes through the updated data.
3. Apply the exact validated settings snapshot with one DataStore update, then clear the pending record. If the process stops after the database commit or settings write, replay the pending settings idempotently before enabling conflicting writes. If settings cannot be written, show restore as pending and offer retry. Keep the pending payload so startup can finish it. Room and DataStore commit independently, so a committed catalog may temporarily have pending settings.
4. Run reconciliation after pending restoration completes. Clean abandoned staging and unreferenced payloads under the mutation gate, honoring active leases. Downgrade missing referenced bytes to `MISSING` without losing metadata. Exercise crash points before promotion, after promotion, after Room commit and before clearing the settings record. [Roadmap](ROADMAP.md)
