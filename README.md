# Kana

Kana is a modern, offline-first EPUB reader for Android with a customizable reading experience. Open Library and tap **Import book** to choose an EPUB. Tap the imported book to read it by page or jump through its table of contents. **Continue Reading** on Home and the book's History entry reopen its saved position.

The app keeps its own copy of each imported book. Byte-identical files share one Library record. This milestone supports DRM-free EPUBs and uses Android's system theme for reading.

## Features

- **Offline-first library** — import DRM-free EPUBs through the system file picker; the app keeps its own copy of each imported book
- **Deduplication** — byte-identical files share one Library record
- **Reader** — paginated reading, table of contents navigation, Continue Reading from Home, and History reopening saved positions
- **Customization** — Material 3 theming that follows the system light/dark theme
- **Tests** — unit and Android integration tests for library, reader appearance, and shell navigation

## Technology stack

- Kotlin, Jetpack Compose with Material 3
- Room (SQLite) for library catalog and reading state
- DataStore Preferences for user settings
- Readium Kotlin Toolkit 3.4.0 (BSD-3-Clause) for EPUB parsing and navigation
- AGP 9.5.2, Gradle 9.8.2, Kotlin and Compose compiler 2.4.20
- minSdk 24, targetSdk 36, JDK 17

Third-party components and their licenses are acknowledged in `docs/ARCHITECTURE.md`, `app/src/main/assets/licenses/`, and the bundled font `OFL.txt` files. Room schema exports live in `app/schemas`.

## License

Kana is licensed under the GNU General Public License v3.0 (GPL-3.0-only). Copyright (C) 2026 Artemisdevv.

See [LICENSE](LICENSE) for the complete license text.

Distributed modified versions of Kana must comply with GPLv3: the entire work must be licensed under GPLv3, corresponding source code must be made available to recipients, and modified versions must be marked as changed. This license does not permit incorporating Kana into proprietary programs.

Third-party assets retain their original licenses and are not relicensed by this project:
- Readium Kotlin Toolkit — BSD 3-Clause
- WordNet 3.0 dictionary data — Princeton University license
- OpenDyslexic font — Creative Commons Attribution 3.0
- Bundled fonts (Inter, JetBrains Mono, Lora, Literata, Atkinson Hyperlegible) — SIL Open Font License 1.1

## Build

Use JDK 17 and install Android SDK Platform 37.0 and Android SDK Build-Tools 36.0.0. Set `ANDROID_HOME` to the SDK directory or open this project in Android Studio and let it configure the local SDK path. The app supports API 24 and up and targets API 36.

On Windows, run:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On macOS or Linux, run `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. With a configured device or emulator, run `:app:installDebug` to install it and `:app:connectedDebugAndroidTest` to run the Android integration tests.

Release signing requires a keystore. Create `keystore.properties` (ignored by `.gitignore`) with your signing credentials and wire it into the release `signingConfig` before building release variants.

[Version catalog](gradle/libs.versions.toml) · [Architecture plan](docs/ARCHITECTURE.md) · [Implementation roadmap](docs/ROADMAP.md) · [Data model](docs/DATA_MODEL.md) · [Backup format](docs/BACKUP_FORMAT.md)
