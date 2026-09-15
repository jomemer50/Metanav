# Metanav

Obstacle guidance for Meta glasses. The glasses' camera streams to your phone, an on-device
depth model works out what is actually in your walking path, and the app tells you what it is,
how far away it is, and which way to step. It stays quiet otherwise.

> "Chair ahead, 2 meters. Move left."  ·  "Stop. Person very close."  ·  "Path clear."

Two native apps share one design: **iOS** (SwiftUI, Core ML) and **Android** (Compose, TFLite).
The reasoning core is written twice, in Swift and Kotlin, with the same scenario tests on both.

## How it decides something is an obstacle

Depth models on phones give *relative* depth, and "anything close" would mean the floor. So:

1. **Ground-plane calibration.** The camera is at a known height (your eye height for glasses).
   For every image row below the horizon there is a known ground distance. The bottom of the
   frame is almost always floor, so each frame we fit the model's relative depth to those known
   distances and get depth in meters. If the bottom of the frame is *not* floor (a wall or table
   fills it) the fit is rejected and the previous calibration is kept, which is exactly what makes
   that wall show up as an obstacle.
2. **Closer than the ground = sticks up from it.** A pixel is an obstacle candidate only if it is
   within the warning distance *and* clearly closer than the floor would be at that row.
3. **Columns to blobs.** Enough candidate pixels in a column block that column; adjacent blocked
   columns form a blob with a robust (20th percentile) distance.
4. **Tracking.** Blobs are tracked across frames and must be seen in 3 of the last 5 frames
   before anything is said. One noisy frame can neither trigger nor cancel an alert.
5. **Only the path matters.** Only blobs overlapping the centre corridor (30–70% of the frame)
   count. Things beside the path are shown on screen, not spoken.
6. **Names and sanity checks.** A COCO object detector names the blob ("Person", "Chair") and,
   for objects of known size, cross-checks the distance.
7. **Speaking policy.** Announce a new obstacle once; again if it gets a metre closer or becomes
   urgent (< 1.2 m → "Stop"); remind at most twice if it just sits there; global cooldown; "Path
   clear" once it has gone. Urgent messages interrupt, nothing is ever queued behind stale advice.
8. **Which way.** The freer of the left and right thirds of the view, preferring the side away from
   the obstacle's bulk; "Stop" if both are blocked.

Guidance is spoken through whatever the phone plays audio on. With the glasses connected as a
Bluetooth headset, that is the glasses' open-ear speakers. The phone also vibrates.

## Running on an iPhone

Requirements: macOS with Xcode 16+, an iPhone on iOS 17.2+, the **Meta AI** app with your glasses
paired and **Developer Mode** on (Meta AI › Settings › your glasses › Developer Mode).

```bash
scripts/fetch-models.sh ios          # downloads the Core ML models (~58 MB, once)
brew install xcodegen                # only if you change project.yml
cd ios && xcodegen generate          # regenerates Metanav.xcodeproj (already checked in)
open ios/Metanav.xcodeproj
```

In Xcode: select the Metanav target › Signing & Capabilities › pick your Team, then run on the
phone (the simulator has no Bluetooth). First launch: tap **Connect glasses**, approve the app in
Meta AI, come back, tap **Start with glasses**, approve camera access in Meta AI once more.

The Meta SDK (`meta-wearables-dat-ios` 0.9.0) is pulled in by Swift Package Manager on first open.

To try it without glasses, tap **Use the phone camera instead** and hold the phone at chest
height pointing forward (set *Camera height* to about 1.3 m in Settings).

## Running on an Android phone

Requirements: Android Studio (or just a JDK 17+ and the Android SDK), a phone on Android 11+, the
**Meta AI** app with Developer Mode on as above.

```bash
scripts/fetch-models.sh android      # downloads the TFLite models (~70 MB, once)
cd android
./gradlew installDebug               # with the phone connected over USB debugging
```

Or open `android/` in Android Studio and press Run. The Meta SDK comes from Maven Central; no
tokens needed while Developer Mode is on.

## Verifying the core logic

```bash
cd android && ./gradlew :core:test                       # 16 Kotlin scenario tests
cd ios/MetanavCore && swift run -c release MetanavCoreCheck   # same scenarios in Swift, no Xcode needed
cd ios/MetanavCore && swift test                         # XCTest version (needs Xcode)
```

The scenarios render synthetic depth maps from the same pinhole geometry the reasoner assumes
(flat floor, upright boxes at known distances) and check: nothing is said on an empty floor; a box
in the path is announced once with the right distance and direction; a detector label is used; an
obstacle beside the path is shown but not spoken; flicker is ignored; an approaching obstacle
escalates to "Stop" without nagging; "Path clear" follows; a wall filling the frame still triggers
"Stop"; a persisting obstacle gets at most two reminders.

## Layout

```
scripts/fetch-models.sh        model downloads for both platforms
ios/MetanavCore/               Swift package: the reasoning core + tests + terminal check
ios/Metanav/                   SwiftUI app (DAT SDK, Core ML, AVSpeechSynthesizer)
ios/project.yml                XcodeGen spec for ios/Metanav.xcodeproj
android/core/                  Kotlin/JVM module: the reasoning core + JUnit tests
android/app/                   Compose app (DAT SDK, TFLite, TextToSpeech, foreground service)
```

Models: Depth Anything V2 Small (Apple's Core ML build) and Apple's YOLOv3-tiny on iOS; MiDaS v2.1
small (Qualcomm AI Hub TFLite export) and EfficientDet-Lite0 on Android.

## Settings that matter

- **Camera height**: distances are derived from it. Glasses: your eye height. Phone at the chest:
  about 1.3 m.
- **Warn within**: default 3 m. Larger is earlier and chattier.
- **Units**: meters or feet.

## Known limits

- Distances are estimates (typically within about 20% at 1–3 m). Steps, curbs, and things lower
  than roughly knee height are not detected reliably; this is not a replacement for a cane or a
  guide dog.
- Very dark scenes and reflective floors degrade the depth model.
- On iOS the glasses stream pauses when Meta AI or another glasses app takes over the device; tap
  the glasses' touchpad to resume. Keep the app in the foreground for guaranteed operation (the
  screen stays on; dimming is fine).
- Developer Mode registration uses app ID `0`. For a release channel, set `META_APP_ID` /
  `CLIENT_TOKEN` (iOS build settings) or `META_APP_ID` / `META_CLIENT_TOKEN` env vars (Android).
