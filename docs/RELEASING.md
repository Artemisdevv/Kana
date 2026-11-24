# Publishing Kana updates

Kana checks stable public releases from the GitHub Releases API without signing in. It uses Android's numeric `versionCode` to select the highest version above the installed build whose advertised minimum SDK the device satisfies. Automatic checks run in the main activity about every 24 hours, including after a previous unsuccessful check. Settings allows a manual check at any time.

## Release format

| Field | Required value |
| --- | --- |
| Release tag | `vMAJOR.MINOR.PATCH`, for example `v1.0.0`. The APK's `versionName` must equal the tag without the `v`. |
| APK asset | Exactly one `Kana-vMAJOR.MINOR.PATCH-vcCODE-minsdkAPI-release.apk` matching the tag. For example, if the code is 2 and the minimum SDK is 24, use `Kana-v1.0.0-vc2-minsdk24-release.apk`. |
| APK identity | A standalone, universal APK with application ID `com.artemis.ebookreader`, release signing, and the advertised version code and minimum SDK. |
| Release status | Published and stable. Drafts and releases marked as prereleases are skipped. |
| Release notes | A short, plain-language summary or simple bullet list in the GitHub release body. Kana displays the notes as scrollable text. |
| Integrity metadata | GitHub provides the uploaded asset's byte count and may provide a `sha256:<hex>` digest in the Releases API. Kana validates the count and any reported digest. APK signer verification is mandatory. |

Increment `versionCode` above every previously distributed Kana release build. Android compares this integer even if the version names sort differently. Choose the next stable `versionName` separately. Use the same permanent release key for each update. Kana requires the same current signing certificates as the installed app. A signing mismatch on a debug- or performance-signed installation leaves its data intact.

## Build and publish

1. Set `versionCode` and `versionName` in `app/build.gradle.kts` for the intended release. Confirm `minSdk` matches the planned asset name.
2. With the existing permanent key configured through the ignored local `keystore.properties`, run `./gradlew :app:assembleRelease` or `gradlew.bat :app:assembleRelease`. The APK is written under `app/build/outputs/apk/release/`.
3. Use the Android SDK Build Tools to inspect the actual APK. Run `aapt dump badging <apk>` and check the package, version code, version name and SDK. Run `apksigner verify --verbose --print-certs <apk>` and compare the public certificate fingerprint against the previous official build or your trusted release-key fingerprint. Keep the keystore and passwords local.
4. Copy the verified APK to its release asset name. Its filename encodes the updater metadata directly. Compute its SHA-256 for your records with `Get-FileHash -Algorithm SHA256 <apk>` or `sha256sum <apk>`.
5. Create a GitHub release in `Artemisdevv/Kana` with the matching tag, add the notes, and upload the renamed APK. Publish as stable after checking the attachment. The updater ignores attachments with other names.
6. Confirm the unauthenticated `GET https://api.github.com/repos/Artemisdevv/Kana/releases` response lists the release and the uploaded asset. Check the exact name, download URL, byte count and any reported asset digest against the APK. Open Settings on an older release-signed installation and check that the new release is offered.

## Manual end-to-end test without publishing a release

Use a spare device or separate test profile and the same permanent release key for both APKs. Use two unpublished test version codes in increasing order. Keep these artifacts local and return to the intended production version settings before packaging a public release.

1. In a disposable local checkout, build the **higher** standalone release APK first. Verify its certificate and manifest with the commands above. Record its byte count and SHA-256.
2. Create a local Releases API JSON array with one object containing `tag_name`, `draft: false`, `prerelease: false`, `body`, and `assets`. The asset has `name` using the convention above, `state: "uploaded"`, numeric `size`, `digest: "sha256:<lowercase hex>"`, and `browser_download_url: "https://github.com/Artemisdevv/Kana/releases/download/<tag>/<name>"`. The URL must be canonical even though this fixture keeps the APK local.
3. Place the JSON and higher APK in that checkout's temporary `app/src/main/assets/update-fixtures/` as `releases.json` and `update.apk`. For this local fixture build only, replace `GithubUpdateHttp()` in `EBookReaderApplication` with an implementation of `UpdateHttp` that opens `assets.open(if (api) "update-fixtures/releases.json" else "update-fixtures/update.apk")` and passes the stream to `read` inside `use`. Pass `-1L` as the response length and use the calling coroutine context's `ensureActive()` as the cancellation callback. Keep this substitution in the disposable checkout. Switch to the **lower** version code and version name and build its release APK using the same permanent key.
4. In the authorized test profile, install the lower APK. Add a sample book, reading position, highlight and bookmark to check in-place data retention. Verify automatic checking on launch, **Later** dismissal, and a manual check from Settings. Check that opening the reader does not display an update dialog over the book.
5. Tap **Download**. Try cancelling and retrying, rotating the device, and restarting after a completed download. Use a larger fixture or a deliberately slow, cancellation-aware input stream if the local asset is too fast for these checks. To test process interruption, stop Kana during the download, reopen it, and confirm it offers a fresh download.
6. Tap **Install update**. On Android 8.0 or later, leave the per-app permission off and return to verify denial handling. Enable it and return. Tap **Install update** again. Cancel the Android confirmation once, then retry without downloading again. Approve the next confirmation and verify the higher installed version and sample reading data. Reopen Kana and confirm the old version is no longer offered.
7. Repeat with an intentionally wrong fixture digest, a truncated response, or an APK signed differently to check rejection paths. On Android 7.x, check the platform's global unknown-source flow. Run the device checks on the minimum SDK and a current Android version when qualifying a public release.

The private fixture exercises the updater, storage, and installer without a GitHub release. It does not exercise GitHub's APK redirects or the public delivery path. Discard the fixture transport and embedded assets before producing a public APK.

## Download and signer handling

The updater keeps a single verified APK in Kana's private update directory. An interrupted download restarts from the beginning after process death. Completed files are revalidated after restart and immediately before showing Android's installer. The installer receives read access to that APK through a scoped content URI. A cancelled or failed installation keeps the verified file available for another attempt. Future key rotation requires an explicit review of the updater's signer policy.
