# SWC Bridge

Makes the steering wheel next/previous buttons work with Poweramp on the
4x4shop MRW_LEXUS head unit (Lexus IS, MediaTek MT8783T, Android 7.0 / API 24).

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-orange)](./LICENSE)

## The problem it solves

The head unit injects a virtual `KEYCODE_MEDIA_NEXT` / `KEYCODE_MEDIA_PREVIOUS`
with `ACTION_DOWN` and **never sends the matching `ACTION_UP`**. Poweramp waits for
the release to tell single from double from triple from long press, so the press
never completes — from its point of view the button is being held down forever.

Diagnosed in [forum.powerampapp.com thread 20241](https://forum.powerampapp.com/topic/20241-steering-wheel-controls/) (Nov 2020).

This app catches the `ACTION_DOWN` and dispatches something Poweramp can actually act on.

## Status

**v0.9.2 (Oct 2026): works end to end.** It starts itself after the car is switched off and on,
with no user interaction ("lean mode"), and keeps Android's media volume at 100% by topping it up
on each NEXT press.

- What changed in each version: [CHANGELOG.md](CHANGELOG.md)
- Known limitations in daily use, and how to recover: the
  [findings doc](docs/SwcBridge-ColdBoot-Findings.md#known-limitations-and-how-to-recover)
- How it starts itself, as pictures: [overview](docs/infographics/how-swc-bridge-starts-itself.png)
  and [lean mode in detail](docs/infographics/lean-mode-explained.png)

## Install in the car (no adb)

1. Copy the APK to a USB stick.
2. On the head unit: **Settings → Security → Unknown sources** → enable.
3. Plug the stick in, open the file manager, tap the APK, install.
4. Open SWC Bridge once. From then on Android starts it by itself for each wheel press.
   Open it once again after every install or update.

## Recommended settings for this head unit

| Setting | Value |
|---|---|
| Keep bridge running in background | **off** (lean mode) |
| Lean mode: app ends its own process when idle | **on** |
| Input 1 — MEDIA_BUTTON broadcast receiver | **on** |
| Input 2 — own MediaSession | **off** (unnecessary here; frees the media session) |
| Session re-assert | off (irrelevant with input 2 off) |
| Act on ACTION_DOWN | **on** |
| Swap next / previous | **on** |
| Only act while Poweramp is playing | off, unless the wheel starts stealing focus from radio/CarPlay |
| Output mode | **A** |
| Debounce | 400 ms |
| Play/pause gesture | Double-press PREVIOUS (the default) |
| Top up Android volume on every NEXT press | **on** |
| Force Android volume at startup | optional; it can be off, the NEXT top-up does the job |

## Tune it from the driver's seat

1. Start Poweramp playing, stereo on **AUX**.
2. Open SWC Bridge, press each wheel button, watch the log.

| What the log shows | What to do |
|---|---|
| `[broadcast] KEYCODE_MEDIA_NEXT ACTION_DOWN -> A: Poweramp API NEXT` | Done. Turn input path 2 off to keep things tidy |
| `[session] KEYCODE_MEDIA_NEXT ACTION_DOWN -> A: ...` | Done. Turn input path 1 off. Leave re-assert **on** |
| Events appear but Poweramp doesn't skip | Output problem — try modes B, then C, then D |
| Events appear then stop working after a track change | Session priority was stolen — make sure re-assert is on |
| **Nothing at all** | Neither path sees it. Save the log; next step is `READ_LOGS` via adb, or decompiling the vendor launcher |

3. **Save** writes a log file to `Android/data/com.mattc.swcbridge/files/` — readable
   from the unit's file manager or over USB.

## How it works

Everything is configurable **on the head unit's touchscreen** — no adb, no laptop,
no rebuild to change behaviour. That's deliberate: the unit is in the car.

**Two input paths, both active by default**, so the first install reveals which one fires:

1. `MediaButtonReceiver` — manifest receiver for `ACTION_MEDIA_BUTTON` at intent-filter
   priority `2147483647`, ahead of Poweramp's own `PSMediaButtonReceiver` at 999.
   Fires if the vendor app broadcasts the button directly, or if the framework falls
   back to the legacy broadcast.
2. `BridgeService` — our own `MediaSession` with `FLAG_HANDLES_MEDIA_BUTTONS`.
   Fires if the key is routed by `MediaSessionService`. Because `STATE_SKIPPING_TO_NEXT`
   is an "always priority" state on API 24, Poweramp reclaims the front of the media
   button stack on **every track change** — so there's an optional 15-second re-assert.

**Four output modes:**

| Mode | What it does | Notes |
|---|---|---|
| **A** `OUT_PA_API` | `com.maxmpz.audioplayer.API_COMMAND` broadcast to `PowerampAPIReceiver` | **Default.** Leaves the key-event system entirely, so the missing-UP problem cannot apply |
| **B** `OUT_PA_MEDIA_BUTTON` | Well-formed DOWN+UP `KeyEvent` pair, explicitly addressed to `PSMediaButtonReceiver` | No session priority contention, no echo risk. Poweramp's own press settings keep working |
| **C** `OUT_PA_UP_ONLY` | Supplies **only the missing `ACTION_UP`**, completing the head unit's own DOWN | The most surgical fix, if it works |
| **D** `OUT_GLOBAL_MEDIA_KEY` | `AudioManager.dispatchMediaKeyEvent` DOWN+UP | Generic — use if you later want Spotify etc. |

All three Poweramp targets are `exported="true"` with **no `android:permission`**
(verified against `Poweramp-build-1025-uni.apk`, versionCode `1025009`), so no
permission or allowlisting is needed.

Also included: debounce (Poweramp throttles NEXT/PREV to ~200 ms internally),
optional gating on Poweramp's sticky `STATUS_CHANGED` state so the bridge doesn't
hijack the wheel while you're on radio or CarPlay, an on-screen event log with
export, and a synthetic-event echo guard.

### Lean mode

The steering wheel never needed a running app: `MediaButtonReceiver` is a manifest receiver,
so Android starts the process on demand for every press, and the process ends a few seconds
later. Nothing of SWC Bridge is running when the car is switched off, so the head unit's
switch-off cleanup (which force-stops running apps) has nothing to kill. The whole story is in
[CHANGELOG.md](CHANGELOG.md) (v0.8 to v0.9.1) and the
[findings doc](docs/SwcBridge-ColdBoot-Findings.md).

### Volume

On this unit Android's media volume is a pre-gain stage feeding the car's own amp over AUX, so
SWC Bridge keeps it at the top and the car's volume knob sets the loudness. Android's
safe-volume limiter gets in the way of that; how that was found and worked around is in
[docs/volume-story.md](docs/volume-story.md).

## Build

Requirements: Android Studio, any recent release. The bundled JDK is used automatically.

Pinned to **AGP 8.7.3 / Gradle 8.9**, which supports JDK 21 — what current Studio releases
bundle. Studio may still offer an AGP upgrade when you open it; accepting is fine.

1. **File → Open** → select this `SwcBridge` folder.
2. `gradle-wrapper.jar` is not included (it's a binary, and Google's and Gradle's servers
   were unreachable from where this was generated). On first sync Studio offers to set the
   wrapper up — accept it. Or run `gradle wrapper --gradle-version 8.9` if you have Gradle
   on PATH. Everything else is present.
3. Studio will prompt to install **SDK Platform 34** if it's missing — accept.
4. **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

If sync complains about the Gradle JDK, the one-click fix lives in
**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.

Output: `app/build/outputs/apk/debug/swc-bridge-<version>-debug.apk` (for example `swc-bridge-0.9.2-debug.apk`)

Zero dependencies — framework APIs only. `minSdk 21`, `targetSdk 24` (deliberately low,
to keep Android 7 semantics and avoid background-execution and package-visibility guards).

## Testing

The whole output half can be tested at the desk on an emulator, with the bundled **`mock`**
module standing in for Poweramp. Only the input half needs the car.

> **Never install `mock` on the head unit.** It claims Poweramp's package name.

Setup, test steps and the recorded desk and car results: [docs/TESTING.md](docs/TESTING.md).

## Files

```
mock/                      Fake Poweramp for emulator tests - NEVER install on the head unit
app/src/main/java/com/mattc/swcbridge/
  Poweramp.java            Poweramp's API surface, verified against the build-1025 APK
  KeyRouter.java           The core: debounce, gating, DOWN handling, output dispatch
  MediaButtonReceiver.java Input path 1 - max-priority MEDIA_BUTTON broadcast receiver
  BridgeService.java       Input path 2 - own MediaSession + priority re-assert; foreground service
  MainActivity.java        On-screen config, tests and log
  Prefs.java               Runtime settings and their defaults
  EventLog.java            Ring buffer + file export (this replaces logcat)
  Diagnostics.java         Device / Poweramp facts for the screen and exported logs
  BootReceiver.java        Auto-start (boot, media-mounted, clock-set and other startup signals)
  WatchdogReceiver.java    15-minute non-wakeup alarm that restarts the service after ordinary kills
  Lean.java                v0.9 lean mode: ends the process when idle
  ShutdownReceiver.java    v0.8 diagnostic, unregistered in v0.9 (safe to delete)
  RunHistory.java          First-run-this-boot detection, previous-boot report, once-per-boot startup
  SwcApp.java              Logs every process start and its reason
  VendorInfo.java          Vendor audio parameters (none readable on this unit)
  VolumeControl.java       Startup volume, every method, safe-volume diagnostics
```

## Known limitations

- Discrete presses only. Hold-to-seek isn't mapped; Poweramp has
  `BEGIN_FAST_FORWARD`(10) / `END_FAST_FORWARD`(11) if you want to add it.
- Volume buttons are out of scope, and note the Poweramp API has **no volume command** —
  that path is `AudioManager` on `STREAM_MUSIC`.
- Input path 2 registers an active `MediaSession` claiming `STATE_PLAYING`. That's how it
  wins media button priority, but it may show up in the notification shade. Turn it off
  once you know path 1 works.

## Documentation

| | |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version, v0.1 to v0.9.2 |
| [docs/TESTING.md](docs/TESTING.md) | Testing at the desk with the mock, and the desk and car results |
| [docs/volume-story.md](docs/volume-story.md) | Why Android's volume wouldn't go up, and the fix (v0.4 – v0.6) |
| [docs/](docs/README.md) | The cold-start investigation, infographics, the original plan and head unit details |

## License

This project is licensed under the **PolyForm Noncommercial License 1.0.0**.

* **Allowed:** You are free to download, use, and modify this app for **personal, educational, or non-commercial purposes**.
* **Not Allowed:** You may **not** sell this app, offer it as a paid service, or use it for any commercial or for-profit purpose.

See the [LICENSE](./LICENSE) file for the full legal terms.
