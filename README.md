# Screenshot App

A lightweight Android screenshot utility. It shows a **draggable floating button** anywhere on screen; tap it to capture the current screen. Captures are saved to the public **`Pictures/Screenshots`** album (visible in your system Gallery) and also browsable, shareable, and deletable inside the app.

## Features
- One-tap screenshot via a draggable floating overlay button
- Saves to `Pictures/Screenshots` through MediaStore (scoped storage) — shows up in the system Gallery
- In-app gallery (2-column grid) with **view**, **share**, and **delete**
- Full-screen image viewer
- Works on Android 9 – 15 (minSdk 28, targetSdk 34)

## How it works
| Component | File | Role |
|---|---|---|
| Main screen | `app/src/main/java/com/example/screenshooter/ui/MainActivity.kt` | Overlay-permission check → MediaProjection consent → starts/stops capture; hosts the gallery |
| Capture service | `app/src/main/java/com/example/screenshooter/overlay/CaptureService.kt` | Foreground service (`mediaProjection` type). Shows the floating button and, on tap, grabs one frame from an `ImageReader`-backed `VirtualDisplay` and saves it |
| Storage | `app/src/main/java/com/example/screenshooter/ScreenshotStore.kt` | Inserts/queries/deletes screenshots in MediaStore (`Pictures/Screenshots`) |
| Gallery / viewer | `ui/ScreenshotAdapter.kt`, `ui/ViewerActivity.kt` | Grid adapter and full-size viewer (both work off content URIs) |

### Capture pipeline
1. User taps **Start Capture Mode** → the system MediaProjection consent dialog appears.
2. The app starts a foreground service and creates a `VirtualDisplay` mirroring the screen into an `ImageReader`.
3. A background thread continuously drains the reader so its buffer pool never fills up.
4. Tapping the floating button reads the latest frame (with retries, since the virtual display needs a moment to produce its first buffer), converts it to a `Bitmap`, and writes a PNG via MediaStore.

## Requirements
- Android 9.0+ (API 28) through Android 15 (API 35)
- No root required

### Permissions
| Permission | Why | When asked |
|---|---|---|
| Display over other apps (overlay) | To draw the floating button | First time you tap *Start Capture Mode* |
| Screen capture consent (MediaProjection) | To read the screen | Every time you start a capture session |
| Notifications | Foreground-service notification | Android 13+ only |
| Read **and** write external storage | Save to and list the public album on **Android 9 only** | Android 9 only (Android 10+ uses MediaStore, no storage permission needed) |

> **Android 9 note:** below Android 10 the app must hold *both* `READ_EXTERNAL_STORAGE`
> and `WRITE_EXTERNAL_STORAGE` to use the public `Pictures/Screenshots` album — reading the
> gallery list needs the read permission, writing a capture needs the write one. Both are
> requested together on first use. (On Android 10+ scoped storage handles this with no
> runtime permission.)

## Building
```bash
# Point the build at your SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build the debug APK
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

> **Note for this workspace:** `/home` is read-only here, so builds also need
> `export GRADLE_USER_HOME=/tmp/gradle-home` and a project-local debug keystore
> (already configured in `app/build.gradle.kts` → `keystore/debug.keystore`).

## Installing & running
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.screenshooter/.ui.MainActivity
```
Then on the device:
1. Open **Screenshot** → tap **Start Capture Mode**
2. Allow overlay + screen capture when prompted
3. Drag the floating button where you like, then **tap it** to capture

## Project structure
```
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/screenshooter/
│       │   ├── ScreenshotStore.kt
│       │   ├── overlay/CaptureService.kt
│       │   └── ui/{MainActivity,ScreenshotAdapter,ViewerActivity}.kt
│       └── res/  (layouts, drawables, values)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties          # SDK location (not committed normally)
└── keystore/debug.keystore   # project-local debug signing key cp ~/.android/debug.keystore keystore
```

## Tech stack
- Kotlin 1.9.22, Android Gradle Plugin 8.2.2, Gradle 8.5
- Material Components, RecyclerView, ConstraintLayout
- MediaProjection + ImageReader + VirtualDisplay for screen capture
- MediaStore (scoped storage) for saving
