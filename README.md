# Cloud SD Card (Android / Kotlin)

Cloud SD Card is a native Android Studio starter project for a Google Drive-backed virtual storage manager. It is a real app foundation, not a universal filesystem mount. It can authorize a Google account using Google Identity Services, create/find the app's `Cloud SD Card` folder, list accessible Drive items, create folders, upload a user-selected file, download a file to a user-selected destination, rename (current action appends a label), and delete with confirmation. It includes Compose navigation, Room metadata tables, a dark storage-oriented UI, and a starter test.

> **Implementation status:** This ZIP is an initial functional implementation, not completion of every requirement in the supplied production PRD. See “Known limitations” below. Do not interpret a screen or database table as proof that a feature is fully implemented.

## Requirements

- Android Studio with a JDK 17 runtime.
- Android SDK Platform 35 installed.
- A Google Cloud project with the Google Drive API enabled.
- An Android OAuth client configured for package `com.aprax.csd` and the signing certificate SHA-1/SHA-256 of the installed APK.
- Google Identity Services OAuth consent screen configured for the intended testers/users.

## Open and build

1. Extract this ZIP and open the `CloudSDCard` folder in Android Studio.
2. Allow Gradle sync and install any prompted SDK components.
3. Configure your OAuth Android client in the same Google Cloud project where the Drive API is enabled. Use application ID `com.aprax.csd` and the SHA-1/SHA-256 fingerprint for the APK you install. The GitHub-hosted debug keystore is temporary, so its fingerprint may change between runs; for stable OAuth, configure a stable signing keystore through GitHub Actions secrets and sign builds with it. Do not place a client secret in the APK.
4. Install on a device with Google Play services and an internet connection.
5. Tap **Connect Google Drive** and authorize the requested `drive.file` scope.

The project uses Gradle Kotlin DSL and `gradle/libs.versions.toml`. Dependency versions are pinned in the version catalog. The launcher icon is a custom Cloud SD Card vector/adaptive icon. A reusable SVG logo is also included at `assets/cloud-sd-card-logo.svg`. Gradle wrapper scripts are not bundled in this ZIP; Android Studio can use its configured Gradle installation or generate a wrapper with `gradle wrapper` from the project root.

## Google Drive scope behavior

The app requests `https://www.googleapis.com/auth/drive.file`, a narrower scope intended for files created by this app or explicitly opened/shared with it. It does **not** grant unrestricted access to all files in the account. Consequently, existing Drive files that have not been made accessible to the app may not appear. A production implementation should add a Google Picker-based import flow for users to explicitly select existing files, and should complete Google's applicable OAuth verification requirements before broad distribution.

The app creates a folder named `Cloud SD Card` if it cannot find an accessible matching folder. It does not create a system mount such as `/storage/emulated/0/CloudSD`.

## Current capabilities

- Native Kotlin + Jetpack Compose UI with Home, Cloud Drive, Game Storage, Transfers, and Settings destinations.
- Google authorization flow using Google Identity Services and `drive.file`.
- Real Drive listing with pagination, search by accessible filename, folder creation, rename, delete, and quota retrieval where provided by Drive.
- Streaming selected-file upload and download using bounded 64 KiB buffers rather than loading full files into memory.
- Android Storage Access Framework for selecting upload files and download destinations.
- **CSD system document location** through an Android `DocumentsProvider`: CSD is offered in Android's system file/document picker and compatible third-party file managers, in addition to the app's own Cloud Drive screen.
- Room metadata tables for cloud file index and transfer records.
- User-facing authorization and transfer errors; destructive delete confirmation.

## Known limitations / next implementation phase

- Uploads/downloads currently run while the app process is active. They are not yet scheduled through WorkManager/foreground notifications, and resumable upload/session recovery is not implemented.
- The current upload picker selects one file at a time. Recursive folder upload and preserving folder hierarchy are not implemented.
- Transfer history is session-level UI state; Room transfer entities exist but are not yet wired to a persistent transfer queue.
- The cache database schema is not yet connected to an LRU cache manager. Pinning, freshness validation, cache eviction, and offline cache controls are not implemented.
- Game Storage is an honest starter workflow, not a complete game backup product. Versioned manifests, multi-file backups, checksums, backup comparison, and restore-to-selected-folder are not implemented. The import action uploads a selected file to the current Drive folder.
- Rename currently appends “(renamed)” rather than showing a rename dialog. Move/copy and batch selection are not implemented.
- The account label reports connection status; retrieving/displaying the actual account email is not implemented.
- The DocumentsProvider exposes the app's `Cloud SD Card` Drive folder as a location named `CSD`. Android's system document picker should show it; third-party file-manager support varies by app and Android version. It is not a real mounted `/storage/...` volume and will not appear as a physical SD card in every system screen.
- The provider keeps the short-lived access token in app-private SharedPreferences so other Android document-picker clients can access CSD while the app is not open. Google access tokens expire; if CSD returns an authorization error, open Cloud SD Card and reconnect Google Drive. Production builds should replace this with a robust encrypted token lifecycle.
- Provider file operations support listing, creating folders/files, reading/downloading, writing/uploading, renaming, and deleting. Cloud API operations require an active valid token and internet connection.
- Complete local/cloud reconciliation, account-switch UI, persistent secure token lifecycle, light theme controls, and complete automated test coverage remain future work.
- Drive API requests use a direct HTTPS REST implementation and surface API errors. Production hardening should add structured retry/backoff, request cancellation, stronger transfer state persistence, and additional integration tests.

## Android / game-storage limitation

Cloud Drive files are not local files until downloaded. Standard Android application permissions do not let this app access another game's private internal directory, universally redirect game file I/O to Google Drive, or mount Google Drive as a system-wide SD card. Only back up resources the user can select through Android's document/file access APIs or resources exposed by the game itself. Restoring a file does not guarantee that a game will recognize it.

## Privacy and security notes

- No unrelated app server is used; Drive requests go directly to Google over HTTPS.
- The access token is held in memory in this starter and is not written to the Room database. Reconnect after process restart.
- Do not add client secrets to the Android app.
- Deleting a cloud item is permanent from the app's perspective and requires confirmation.
- Disconnecting clears the in-memory connection only and does not delete cloud data.
- Review OAuth configuration, privacy disclosures, Google API policy, and production security before release.

## Project structure

```text
CloudSDCard/
├── app/
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/cloudsdcard/app/MainActivity.kt
│   ├── src/main/java/com/cloudsdcard/app/data/DriveRepository.kt
│   ├── src/main/java/com/cloudsdcard/app/data/CloudDatabase.kt
│   └── src/main/res/values/styles.xml
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

### GitHub Actions APK releases

The workflow `.github/workflows/build-apk.yml` builds `app-debug.apk`, uploads it as a workflow artifact, and publishes the same APK as a GitHub Release asset. Each release tag follows `debug-apk-build-RUN_NUMBER-RUN_ATTEMPT`. The workflow needs repository Actions permissions that allow `GITHUB_TOKEN` to create releases; repository settings may need **Settings → Actions → General → Workflow permissions → Read and write permissions**. Debug releases are for testing only.

### Google authorization troubleshooting

If the app says authorization was cancelled or fails to return a token, first confirm that the Google Drive API is enabled and that the Google Cloud Android OAuth client uses package `com.aprax.csd` and the signing certificate SHA-1 of the APK actually installed. Also check that the OAuth consent screen is configured and your account is listed as a test user if the app is in Testing mode. A wrong package/SHA-1 often appears as an authorization failure rather than a clear explanation. The app now displays more diagnostic guidance instead of always labelling a failed resolution as cancellation. A real user cancellation still must be retried by the user.

### Build workflow dependency fix

The Google Play services authentication dependency is configured as `com.google.android.gms:play-services-auth:21.4.0`. Earlier project versions incorrectly used `1.1.1`, which does not exist for this artifact and causes Gradle's `checkDebugAarMetadata` task to fail. Push this updated project to GitHub and rerun the **Build Cloud SD Card APK** workflow.


## Fixes: CSD file transfer and stable SHA-1

- The DocumentsProvider uses a reliable pipe for downloads, reports cloud download failures through the pipe, and uses chunked streaming for cloud file updates. It also sends Drive PATCH requests using POST plus `X-HTTP-Method-Override` for Android compatibility.
- GitHub Actions installs `signing/csd-debug.keystore` as the debug signing key before building. This keeps the debug APK signing certificate fingerprint stable between builds.
- Persistent debug keystore SHA-1: `66:2F:36:60:62:DE:0A:40:E3:B0:5D:DA:5F:26:42:F8:43:0A:B7:C6`
- Persistent debug keystore SHA-256: `66:D6:1B:BA:CD:37:57:3E:58:BE:9A:F8:6A:86:F3:81:A9:47:6D:4E:10:88:8C:EF:B7:80:97:A9:C6:BE:8D:EA`
- This keystore is for debug/testing builds only. Do not use it for a production app or Play Store release. Because it is bundled in the source ZIP, anyone who gets the project also gets this debug key.
