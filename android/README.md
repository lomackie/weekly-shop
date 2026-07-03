# weekly-shop tablet app

Kotlin app for the Onyx BOOX Note Air 2 Plus: a full-screen writing canvas.
Write an item, tap **Done**, and the strokes go to the server, which replies
with the matched item.

## Setup

1. Open `android/` in Android Studio (it will download Gradle from the wrapper
   properties on first sync).
2. Set the server address in `app/src/main/res/values/strings.xml`
   (`server_url`).
3. Enable developer options + USB debugging on the BOOX (Settings → About →
   tap build number), then run the app on it from Android Studio.

## Current state / next steps

- Stroke capture works with stylus or finger via the standard Android render
  path. On e-ink this has visible lag — the fix is the **BOOX Pen SDK**
  (`onyxsdk-pen`), which draws directly on the e-ink layer. The dependency and
  repo are stubbed in comments in `app/build.gradle.kts` and
  `settings.gradle.kts`; the swap is confined to `InkCanvasView`.
- Ambiguous matches currently just show the candidate names — the next client
  feature is a picker that posts the choice to `POST /basket/{id}/resolve`.
- For wall duty: disable the screensaver/auto-sleep in BOOX settings, exempt
  the app from their app-freezing battery settings, and consider setting it
  as the launcher (kiosk mode).
