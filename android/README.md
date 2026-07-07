# weekly-shop tablet app

Kotlin app for the Onyx BOOX Note Air 2 Plus: a full-screen writing canvas.
Write an item with the pen, tap **Done**, and the strokes go to the server,
which replies with the matched item. Rub a stroke with a finger to erase it.

## Setup

1. Open `android/` in Android Studio (it will download Gradle from the wrapper
   properties on first sync). To build headless instead:
   `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" gradle assembleDebug`
   (any Gradle 8.11+; the repo has wrapper properties but no `gradlew` script).
2. Set the server address in `app/src/main/res/values/strings.xml`
   (`server_url`).
3. Enable developer options + USB debugging on the BOOX (Settings → About →
   tap build number), then install with `adb install -r`.
4. Optional, for cable-free installs once it's on the wall: with USB attached
   run `adb tcpip 5555`, then `adb connect <tablet-ip>:5555`. This resets on
   reboot — re-enable over USB when needed.

## Current state

- **Interaction**: the stylus writes, a finger erases whole strokes — no mode
  toggle. **Undo** removes the last stroke, **Clear** wipes the canvas,
  **Basket** lists the basket (tap an entry to remove it), **Done** submits.
- **Ambiguous matches** open a picker dialog; the choice is posted to
  `POST /basket/{id}/resolve`, which also teaches the server the alias.
- **BOOX Pen SDK** (`onyxsdk-pen`) is integrated behind a
  `Build.MANUFACTURER == "ONYX"` gate, with an adaptive fallback: if the raw
  pen pipeline never delivers callbacks, the stylus permanently falls back to
  the standard MotionEvent render path so writing always works. Committed
  strokes are mirrored into a bitmap so they survive refreshes.

## Known issues / next steps

- **Pen latency**: on the Note Air 2 Plus the SDK's raw drawing layer
  currently never delivers callbacks (`onBeginRawDrawing` never fires even
  though `TouchHelper` opens cleanly), so writing uses the fallback path —
  noticeably more delayed than the native notebook app. Needs investigation:
  SDK version bump, `setRawDrawingRenderEnabled`, firmware pairing, or the
  `EpdController` fast-refresh modes are the likely angles.
- For wall duty: disable the screensaver/auto-sleep in BOOX settings, exempt
  the app from their app-freezing battery settings, and consider setting it
  as the launcher (kiosk mode).
- The ambiguous-match picker hasn't been triggered end-to-end yet (the stub
  recogniser always matches); retest once the Claude recogniser is enabled.
