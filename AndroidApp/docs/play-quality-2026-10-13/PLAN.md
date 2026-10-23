# Google Play quality fixes — 2026-10-13

Requested workflow: direct Antigravity to implement and iterate; independently inspect code and build evidence.

## Initial findings

- compile/target SDK 36, min SDK 26.
- AGP 8.1.1, Gradle 8.3, Kotlin Android 1.9.0, Room 2.6.1 with kapt.
- Material 1.11.0; broad keep rules retain unused Material/AndroidX code.
- MainActivity uses fitsSystemWindows plus a root inset listener, without enableEdgeToEdge.
- EmulatorActivity translates controls for insets; portrait bottom and reset-to-zero need review.
- No PiP declaration or implementation; onPause stops rendering and native emulation.
- Existing AVD: Pixel_9a, Android 37.1 preview, x86_64, 16 KB pages. No connected device initially. This AVD cannot substitute for Android 15/16 compatibility testing.

## Acceptance work

1. Call enableEdgeToEdge in both activities; assign each inset to one owner; handle cutouts, orientation and IME without cumulative offsets.
2. Upgrade compatible Material/AndroidX; remove broad keep rules. Inspect optimized release for the three Material warning entry points. Distinguish required legacy compatibility APIs from Android 15 paths.
3. Implement a deliberate PiP entry point, feature detection, content bounds/aspect ratio, hidden controls, and safe resume/pause/stop/native teardown.
4. Migrate to AGP >=9 with matching Gradle and Kotlin/annotation processing configuration. Preserve JNI names and required reflection rules; retain release minification/resource shrinking.
5. Run debug build, unit tests, lint and R8 release bundle; inspect outputs. Run available device checks and document untested cases explicitly.

## Sources

- https://developer.android.com/develop/ui/views/layout/edge-to-edge
- https://developer.android.com/develop/ui/views/picture-in-picture
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://github.com/material-components/material-components-android/releases

Play Console verification requires uploading a new release and Google's subsequent analysis. Local checks alone cannot confirm that the console has cleared a warning.

## Completed scope

The user selected code and testing only for this round. Version 1.1.0 (13) builds with AGP 9.0.1 / Gradle 9.1.0. Strict debug/release lint has zero errors, 68 unit tests pass, and all 18 final device scenarios pass. The three Material warning classes are absent from the final optimized QA DEX. Production signing/configuration and Play upload remain outside this round. See REPORT.md and device-results/status.json for final evidence.
