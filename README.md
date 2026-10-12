# Cloud SD Card (Android / Kotlin)

Cloud SD Card is a native Android Studio starter project for a Google Drive-backed virtual storage manager. It is a real app foundation, not a universal filesystem mount. It can authorize a Google account using Google Identity Services, create/find the app's `Cloud SD Card` folder, list accessible Drive items, create folders, upload a user-selected file, download a file to a user-selected destination, rename (current action appends a label), and delete with confirmation. It includes Compose navigation, Room metadata tables, a dark storage-oriented UI, and a starter test.

> **Implementation status:** This ZIP is an initial functional implementation, not completion of every requirement in the supplied production PRD. See “Known limitations” below. Do not interpret a screen or database table as proof that a feature is fully implemented.

## Requirements

- Android Studio with a JDK 17 runtime.
- Android SDK Platform 35 installed.
- A Google Cloud project with the Google Drive API enabled.
- An Android OAuth client configured for package `com.cloudsdcard.app` and your debug/release signing SHA-1/SHA-256 fingerprints.
- Google Identity Services OAuth consent screen configured for the intended testers/users.

## Open and build

1. Extract this ZIP and open the `CloudSDCard` folder in Android Studio.
2. Allow Gradle sync and install any prompted SDK components.
3. Configure your OAuth Android client in the same Google Cloud project where the Drive API is enabled. Use the exact application ID and signing certificate fingerprints. Do not place a client secret in the APK.
4. Install on a device with Google Play services and an internet connection.
5. Tap **Connect Google Drive** and authorize the requested `drive.file` scope.

The project uses Gradle Kotlin DSL and `gradle/libs.versions.toml`. Dependency versions are pinned in the version catalog. Gradle wrapper scripts are not bundled in this ZIP; Android Studio can use its configured Gradle installation or generate a wrapper with `gradle wrapper` from the project root.

## Google Drive scope behavior

The app requests `https://www.googleapis.com/auth/drive.file`, a narrower scope intended for files created by this app or explicitly opened/shared with it. It does **not** grant unrestricted access to all files in the account. Consequently, existing Drive files that have not been made accessible to the app may not appear. A production implementation should add a Google Picker-based import flow for users to explicitly select existing files, and should complete Google's applicable OAuth verification requirements before broad distribution.

The app creates a folder named `Cloud SD Card` if it cannot find an accessible matching folder. It does not create a system mount such as `/storage/emulated/0/CloudSD`.

## Current capabilities

- Native Kotlin + Jetpack Compose UI with Home, Cloud Drive, Game Storage, Transfers, and Settings destinations.
- Google authorization flow using Google Identity Services and `drive.file`.
- Real Drive listing with pagination, search by accessible filename, folder creation, rename, delete, and quota retrieval where provided by Drive.
- Streaming selected-file upload and download using bounded 64 KiB buffers rather than loading full files into memory.
- Android Storage Access Framework for selecting upload files and download destinations.
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
- DocumentsProvider, complete local/cloud reconciliation, account-switch UI, persistent secure token lifecycle, light theme controls, and complete automated test coverage remain future work.
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

### Build workflow dependency fix

The Google Play services authentication dependency is configured as `com.google.android.gms:play-services-auth:21.4.0`. Earlier project versions incorrectly used `1.1.1`, which does not exist for this artifact and causes Gradle's `checkDebugAarMetadata` task to fail. Push this updated project to GitHub and rerun the **Build Cloud SD Card APK** workflow.
