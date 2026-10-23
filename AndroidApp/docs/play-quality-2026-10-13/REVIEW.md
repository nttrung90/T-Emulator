# Independent review notes

## Native ownership with PiP

`JniBridge.cpp` has one process-wide `g_emu`, `g_renderer`, and `g_audio` instance. The existing `EmulatorActivity` uses the default standard launch mode. With PiP, the old activity now continues rendering while `GamesFragment.launchGame` can launch another game/activity. Two render loops can then reference the same native core, and closing the old activity calls `stopEmulation` on the new game. The old game's storage lease also remains held.

A reproduction on API 35 loaded the replacement ROM and left one Java render thread, but the native bitmap stopped changing. The older activity can pause/stop the process-wide native core after the replacement begins. Evidence: `device-results/multi-before.txt`.

The fix adds `singleTask` plus explicit `onNewIntent` handling. Switching ROM/session stops the old loop and recreates the single activity, so native teardown and old lease release precede the new ROM load. Empty restore intents preserve the existing game. Pressed controls/rewind are released on PiP entry and stop; destruction saves/stops native emulation before releasing the storage lease.

The standalone instrumentation runner includes `-e multi true` to verify that selecting another game while a PiP activity exists leaves one live render loop and actually loads the new ROM path.

## Regression coverage prepared

- Original synthetic NES ROM animates a palette; PiP tests verify changing native bitmap pixels, beyond a live thread flag.
- Main bottom navigation insets applied once; controls outside bars/cutouts; light icons on dark backgrounds.
- PiP entry, hidden controls, restore, no duplicate render loop, pinned close, paused menu + Home.
- AVDs: emulator-5556 (API 35), emulator-5558 (API 36), emulator-5560 (API 34). All booted.
- APK tests signed with the existing Android debug key, with no added production app entry point.

UI control of Antigravity temporarily failed with `GetCursorPos failed: Access is denied. (0x80070005)`; device and filesystem verification can continue independently.

## Release configuration

Normal `validateReleaseConfig` currently rejects five unset project/environment fields: GOOGLE_WEB_CLIENT_ID, BACKEND_VERIFY_URL, FIREBASE_API_KEY, FIREBASE_APPLICATION_ID, FIREBASE_PROJECT_ID. Automatic approval review rejected extracting potentially sensitive values from the old generated release class and storing them in a temporary plaintext JSON file. No such JSON was written. Credential recovery remains blocked pending approval or user-provided build environment configuration; the production validation task remains in place.

## Automatic PiP and lifecycle review

Code review found redundant PiP entry paths: `onUserLeaveHint()` explicitly entered PiP on Android 12+ while `setAutoEnterEnabled(true)` also delegated entry to the system. Explicit Home entry is now limited to API <31; the dedicated PiP button still works on every supported API. Early Home/restore tests failed on API 34/35 (see `auto-pip-api34-before.txt` and `auto-pip-api35-before.txt`), but later system logs also identified the harness sending restore before the Home/PiP animation settled. Those failures are not attributed solely to application code.

For automatic PiP, `onPause()` can precede the mode callback. A running, unpaused game eligible for automatic PiP therefore defers shutdown until `onStop()`. Paused menus and devices without PiP still stop on pause. This follows the Android PiP lifecycle guidance and preserves immediate shutdown when a paused menu goes to the background.

The standalone harness checks Home entry, actual advancing frames, restoration, a single render thread, and paused-menu Home behavior. View lookup runs on the UI thread to avoid racing layout reinflation; it waits for mode state plus entry animation stabilization, and destruction uses a bounded callback wait. Final results: all 18 device scenarios pass on the final artifacts, with no fatal runtime logs. Production configuration/signing is outside the user's final code-and-testing scope.
