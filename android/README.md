# weekly-shop tablet app

Kotlin app for the Onyx BOOX Note Air 2 Plus: a full-screen writing canvas.
Write an item with the pen and after a 2 s pause the strokes go to the server
automatically — no submit button. The page IS the list: basketed ink stays
put and gains a small ✓; rubbing an item out with a finger deletes it from
the basket too. Unparsed ink sits inside a dashed highlight until rubbed
out.

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

- **Interaction**: the stylus writes; a finger rub erases whole strokes (a
  double-tap-then-rub gate was tried and reverted — the plain rub is the
  point). No mode toggle, no submit button, no eraser button. Ink auto-sends
  after a 2 s pen-idle pause (`MainActivity.IDLE_MS`);
  offline sends are retried every 8 s with the ink kept on the page. One
  icon: the **basket** (with count badge) opens the basket panel. A small
  status line shows the last event (`✓ item`, `✕ text`, `⚠` offline).
- **The page is the list**: basketed ink stays on the page with a small grey
  ✓ beside it, linked to its basket entry. Rubbing out the last stroke of an
  item deletes its entry (`DELETE /basket/{id}`); deleting from the basket
  panel removes the item's ink from the page. Links live in memory only — an
  app restart leaves the basket intact but unlinked from any ink.
- **Unparsed ink**: when the server returns `status=unmatched` nothing is
  basketed; the ink stays put and the server's `unparsed_regions` bounding
  box is drawn as a dashed rounded rect until the ink is rubbed out.
- **Pages**: edge chevrons flip left/right (a finger swipe would fight the
  finger-eraser, hence buttons). The right chevron appears only once the
  current page has ink; flipping right past the last page creates the next
  one — there is no add-page button. Pages left empty (erased out, or whose
  items were all deleted from the basket panel) collapse, so pages exist
  only where ink is. A `1/2` indicator shows at the bottom centre when there
  is more than one page.
- **Basket panel** is an in-app overlay (not a system dialog): opening it
  suspends the raw pen layer so the pen can't ink over it and closing it
  actually repaints — the old AlertDialog stayed on the e-ink because view
  updates never flush while raw drawing is on. Rows delete with one tap on
  their ✕, no confirmation.
- **Ambiguous matches** open a picker dialog (raw pen layer suspended around
  it for the same reason); the choice is posted to
  `POST /basket/{id}/resolve`, which also teaches the server the alias.
- **Known trade-off**: a server reply flushes the e-ink display by toggling
  the raw layer off/on for a frame; a stroke mid-flight at that exact moment
  can lose its live ink preview (the recorded points are unaffected).
- **BOOX Pen SDK** (`onyxsdk-pen`) is integrated behind a
  `Build.MANUFACTURER == "ONYX"` gate, with an adaptive fallback: if the raw
  pen pipeline never delivers callbacks, the stylus permanently falls back to
  the standard MotionEvent render path so writing always works. Committed
  strokes are mirrored into a bitmap so they survive refreshes.

## Known issues / next steps

- **Pen latency** (fixed, verified on the tablet 2026-07-07): the raw layer
  opening cleanly but never delivering callbacks matched a known failure mode
  on recent firmware. Three changes, mirroring what the Saber app's BOOX
  plugin does: `HiddenApiBypass.addHiddenApiExemptions("")` on Android 11+
  plus `RxManager.Builder.initAppContext(...)` at startup (`App.kt` — both
  are required before the SDK's raw input reader will run), and an SDK bump
  1.4.11 → 1.5.4. The inert-layer fallback now also waits until a full stroke
  completes with no raw callback before abandoning the raw path (the
  callbacks arrive from a background thread, so judging at pen-down could
  falsely abandon a working pipeline — and the probe stroke is kept either
  way; logcat showed the DOWN and the raw callback landing in the same
  millisecond, so the race was real).
- For wall duty: disable the screensaver/auto-sleep in BOOX settings, exempt
  the app from their app-freezing battery settings, and consider setting it
  as the launcher (kiosk mode).
- The ambiguous-match picker hasn't been triggered end-to-end yet (the stub
  recogniser always matches); retest once the Claude recogniser is enabled.
