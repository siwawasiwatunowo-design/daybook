# Daybook

A personal all-in-one app for planning and reflection, available as a native Android app and a web app. Both use the same Firebase account and stay in sync in real time.

## Features

| Section | What it does |
|---|---|
| **Timebox** | Daily schedule. Pick a day, add time blocks (start, end, title), tick them off. Shows done count and planned hours (web). |
| **Notes** | Personal notes, learning journal, thoughts. Title, type (Journal / Learning / Thoughts / Ideas), body, search. |
| **Dreams** | Dream journal. Date, title, type (Normal / Recurring / Nightmare / Lucid), sleep quality 1-5, text, search. |
| **Bucket list** | Life goals. Title, category (Travel, Growth, Career, Health, Family, Finance, Adventure, Other), status (Dream / Planned / Done), optional target year. Tap the status to cycle it. |

All four sections support add, edit and delete. Both clients follow the system light/dark theme.

## Architecture

```
Android app (Kotlin, Compose) ──┐
                                ├── Firebase Auth (email + password)
Web app (single index.html) ────┘── Cloud Firestore  users/{uid}/entries/{entryId}
```

There is no custom backend. Both clients talk to Firestore directly and are kept in sync by Firestore snapshot listeners (`addSnapshotListener` on Android, `onSnapshot` on web). A change on one device appears on the other within a second or two, with no refresh. The Android SDK also keeps an offline cache and syncs queued writes when the network returns. The web client has no offline persistence enabled.

### Data model

One Firestore collection holds all four sections. The `type` field says which section an entry belongs to.

Path: `users/{uid}/entries/{entryId}`

| Field | Used by | Notes |
|---|---|---|
| `type` | all | `block` (Timebox), `note`, `dream`, `bucket` |
| `title` | all | |
| `body` | note, dream | |
| `date` | block, dream | `YYYY-MM-DD` |
| `start`, `end` | block | `HH:mm` |
| `done` | block | boolean |
| `tag` | note, dream, bucket | note type / dream type / bucket category |
| `status` | bucket | `Dream`, `Planned`, `Done` |
| `year` | bucket | number, `0` means unset |
| `sleep` | dream | 1-5 |
| `upd` | all | last update, epoch ms |

The web client uses different in-memory names (`day`, `cat`, `type` for the dream type) and converts them in `toDoc` / `fromDoc` inside `index.html`. Web writes use `setDoc(..., {merge: true})` so fields only the Android app writes are not overwritten.

### Security rules

Each account can only read and write its own data:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

## Repositories and layout

Two GitHub repositories:

1. **`daybook`** (Android). Keep it private if possible because it contains `google-services.json`.
2. **`daybook-web`** (web). Must be public for free GitHub Pages. Contains only `index.html`.

```
daybook/
  .github/workflows/build.yml     CI: builds the debug APK
  build.gradle.kts                plugin versions
  settings.gradle.kts
  gradle.properties
  app/
    build.gradle.kts              dependencies
    google-services.json          Firebase Android config (from the Firebase console)
    src/main/AndroidManifest.xml
    src/main/java/com/example/daybook/MainActivity.kt   all app code

daybook-web/
  index.html                      whole web app (HTML, CSS, JS)
```

### Android stack

Kotlin 2.0.21, Jetpack Compose with Material 3 (Compose BOM 2024.10.01), Firebase BoM 33.5.1 (Auth + Firestore), Google Services plugin 4.4.2, Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17, minSdk 26, targetSdk/compileSdk 35.

`MainActivity.kt` contains everything: the `Entry` model, the `VM` view model (Firebase Auth and Firestore), the login screen, the four section screens and the add/edit dialog.

### Web stack

One `index.html` with plain JavaScript as an ES module. Firebase JS SDK 10.14.1 is loaded from `gstatic.com`. The Firebase web config (`firebaseConfig`) is embedded in the file. It is meant to be public, and data is protected by the security rules above.

## Setup for a new developer or a new Firebase project

1. Create a Firebase project.
2. **Authentication** > Sign-in method > enable **Email/Password**.
3. **Firestore Database** > create database (production mode), then publish the rules above.
4. Register an **Android app** with package name `com.example.daybook`, download `google-services.json`, put it in `app/`.
5. Register a **Web app**, copy its `firebaseConfig`, and replace the one in `index.html`.
6. **Authentication** > Settings > Authorized domains > add the GitHub Pages domain (for example `<user>.github.io`).

## Build and deploy

**Android.** No local setup is required. Pushing to `daybook` runs the GitHub Actions workflow (`gradle assembleDebug` on Gradle 8.9). Download the `Daybook-APK` artifact from the run, unzip it, and install `app-debug.apk` on a phone (allow installs from unknown sources, and dismiss the Play Protect warning if shown). No Gradle wrapper is committed. You can also open the project in Android Studio (Ladybug or newer, JDK 17).

**Web.** Commit `index.html` to `daybook-web` and enable GitHub Pages (Settings > Pages > Deploy from a branch > `main` / root).

## Known limitations and ideas

- No "forgot password", no email verification, no account deletion.
- Package name is still `com.example.daybook` (Play Store rejects this). Changing it means re-registering the Android app in Firebase and replacing `google-services.json`.
- Only debug APKs are built. There is no signing config, release build or app icon.
- Data from the first local-only version (Room database) was not migrated.
- The web dream form has no "how did it feel" field, to match Android's data model.
- Timebox is a list per day, not an hour-by-hour grid.
- No reminders, widgets, push notifications, export or backup, and no automated tests.
- The code has been fixed through GitHub Actions build logs and tested only lightly. Expect rough edges.

## Publishing to Google Play (notes)

- One-time US$25 developer registration fee.
- Personal accounts created after 13 Nov 2023 need a closed test with at least 12 testers opted in for 14 continuous days before production access (check Google's current rules).
- Before publishing: new package name, signed release build (AAB), icon and screenshots, privacy policy, Data safety form, and an account deletion option.
- The project owner can read all users' data in the Firebase console. Say so in the privacy policy and handle the data carefully.
