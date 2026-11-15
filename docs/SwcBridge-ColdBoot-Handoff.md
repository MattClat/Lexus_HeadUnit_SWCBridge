# SWC Bridge — cold-boot autostart problem

**Handoff document. Written 2026-09-20.**

This is a self-contained brief for a fresh conversation. Everything else about this
project is solved and working; **the only open problem is getting the app to start on
its own after a cold boot.** Please don't redesign the working parts.

---

## 1. The problem, in one paragraph

`SWC Bridge` is a small Android app I wrote that makes my car's steering wheel
next/previous buttons work with the Poweramp music player. It works perfectly — *once
the app has been opened*. If the car sits unused for a few days, then on the next start
the steering wheel buttons do nothing and the startup volume override doesn't run.
The moment I open the SWC Bridge app manually, everything works again for days.
Nothing else needs fixing.

**What I need:** the app to start itself after a cold boot, with no user interaction.

---

## 2. The head unit

Aftermarket OEM-fit replacement head unit from 4x4shop.ca, in a 2014–2019 Lexus IS.

### Android / platform

| | |
|---|---|
| Reported device | `alps lexus_mrw1920x720` (`Build.DEVICE` = `lexus_mrw1920x720`) |
| Android version | **7.0 (Nougat), API 24** |
| Build ID | `NRD90M` |
| **Runtime fingerprint** | `alps/c200_en/c200_en:5.1.1/JOP40D/1524551747:user/test-keys` |
| Security patch | 2019-02-05 |
| Kernel | `3.18.35+`, built 9 Mar 2020 |
| Board / integrator | SmartPie (`smartpie@smartpie-Server`) |
| SoC | MediaTek **MT8783T** / MT6735 platform |
| CPU | 8× ARM Cortex-A53 @ 299–1495 MHz, aarch64 / ARMv8-A |
| GPU | ARM Mali-T720 MP3, OpenGL ES 3.1 |
| RAM | 4 GB LPDDR3 @ 1600 MHz |
| Storage | 64 GB eMMC (~52.44 GiB usable) |
| Audio | sound driver `mtsndcard`; PMIC `mt6311`; charger `ncp1854` |
| Wi-Fi/BT | `CONSYS_MT6735` |
| Modem | `MOLY.LR9.W1444.MD.LWTG.MP.V123.P10` |
| Root | **Not rooted** |

> **Note the fingerprint.** It claims `c200_en`, Android **5.1.1**, build `JOP40D`
> (which is actually a Jelly Bean ID), while the runtime genuinely reports 7.0 / API 24
> and build `NRD90M`. This ROM is stitched together from mismatched pieces. That is a
> plausible contributor to broken boot-broadcast delivery and is worth keeping in mind.

### Vehicle integration

| | |
|---|---|
| Product model | `LEXUS_LEXUS_MRW1920X720`, type `MRW_LEXUS` |
| MCU firmware | `LEXUS_V5.0_0321` (note: **not** an `MTC*` board — MTCD/MTCE tools do not apply) |
| CAN decoder box | `LY-Lexus-V206,F,01` (LY / Luyuan / LDir family) |
| System build | `4.64.8core.191207` |
| App version | `V2.0.3.1217h` |
| Software version | `1.0.3.1123` |
| Factory settings PIN | `666666` |
| Display | 10.25" 1920×720 @ 47.4 Hz, density 240 hdpi |
| Panel / touch | `icn6201_1920x720_drv` / `gt9xx` (Goodix) |
| Audio path | Android → **AUX** into the OEM Lexus amplifier |

### Power behaviour (relevant to the bug)

- Driving daily: the unit sleeps/resumes between trips and **everything keeps working**.
- After a few days unused: the unit appears to lose power entirely (the MCU cuts the
  SoC's power rail), and the next start is a **true cold boot**. This is when the
  failure occurs.
- I want the shutdown behaviour preserved — the unit *should* power down when the car
  isn't driven.

---

## 3. What SWC Bridge is and why it exists

### The original bug it solves (solved — background only)

The head unit's vendor software injects a **virtual** media key when a wheel button is
pressed, but sends only `ACTION_DOWN` and **never the matching `ACTION_UP`**:

```
PSMediaButtonReceiver android.intent.action.MEDIA_BUTTON KEYCODE_MEDIA_NEXT ACTION_DOWN
name=Virtual source=0x0 sources=0x301 virt=true repeat=0 playerState=1
```

Poweramp waits for the release to tell single from double from long press, so the press
never completes. Diagnosed on the Poweramp forum in Nov 2020 (thread 20241). SWC Bridge
acts on the `ACTION_DOWN` and sends Poweramp a command instead of a key event, which
sidesteps the missing release entirely.

Two further hardware quirks, both handled:

- The MCU's key table is **reversed** — pressing *next* emits `KEYCODE_MEDIA_PREVIOUS`.
- Android's media volume is reset low; the app forces it at startup.

### App identity

| | |
|---|---|
| Package | `com.mattc.swcbridge` |
| Version | **0.7** (versionCode 7) |
| Language | Java, **zero dependencies** (framework APIs only) |
| `minSdk` / `targetSdk` / `compileSdk` | 21 / **24** / 34 |
| Build | AGP 8.7.3, Gradle 8.9, Gradle JDK 21 (Studio bundles 25 — must stay on 21) |
| APK size | ~30 KB |
| Signing | debug keystore |

### Components

| Component | Type | Role |
|---|---|---|
| `SwcApp` | `Application` | Logs `=== process started ===` on every process creation |
| `MainActivity` | launcher activity | All config + the on-screen event log |
| `BridgeService` | foreground service, `START_STICKY` | MediaSession input path, volume ramp, watchdog scheduling |
| `MediaButtonReceiver` | **manifest** receiver, `MEDIA_BUTTON`, priority `2147483647` | **The input path that actually works** |
| `BootReceiver` | manifest receiver | Startup signals (see §6) |
| `WatchdogReceiver` | manifest receiver, not exported | Repeating non-wakeup alarm, restarts the service |
| `KeyRouter` | — | Debounce, swap, double-press gesture, dispatch |
| `Poweramp` | — | Poweramp's API surface |
| `VolumeControl` | — | Five volume-setting strategies + diagnostics |
| `EventLog` | — | In-memory ring **plus a persistent file** |
| `VendorInfo` | — | Reads vendor audio params (returns nothing on this unit) |
| `Diagnostics` | — | Device/settings dump |

### Permissions declared

```
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.FOREGROUND_SERVICE
android.permission.MODIFY_AUDIO_SETTINGS
android.permission.WRITE_SECURE_SETTINGS   <- declared but NOT granted (needs adb)
```

---

## 4. How SWC Bridge talks to Poweramp

Poweramp build **1025**, package `com.maxmpz.audioplayer`, versionCode `1025009`,
`sharedUserId="com.maxmpz"`, minSdk 21 / targetSdk 35.

All of the following were verified by decoding the binary `AndroidManifest.xml` out of
the installed APK — they are **exported with no `android:permission`**, so any app may
drive them, with no allowlist and no root:

```
com.maxmpz.audioplayer/.player.PowerampAPIReceiver     exported, no permission   <- in use
com.maxmpz.audioplayer/.player.PlayerService           exported, no permission   (legacy)
com.maxmpz.audioplayer/.PowerampAPIActivity            exported, no permission
com.maxmpz.audioplayer/.player.PSMediaButtonReceiver   exported, MEDIA_BUTTON @ priority 999
com.maxmpz.audioplayer/.data.external.BrowserService   MediaBrowserService
```

### The path in use (output mode A)

```java
Intent i = new Intent("com.maxmpz.audioplayer.API_COMMAND")
    .setComponent(new ComponentName(pkg, "com.maxmpz.audioplayer.player.PowerampAPIReceiver"))
    .putExtra("cmd", 4)                       // NEXT
    .putExtra("pak", getPackageName());
context.sendBroadcast(i);
```

Build 1025 is well past 855, so an explicit **`sendBroadcast`** is correct — not
`startService`. Command values: `1` TOGGLE_PLAY_PAUSE, `2` PAUSE, `3` RESUME, `4` NEXT,
`5` PREVIOUS, `10/11` BEGIN/END_FAST_FORWARD, `14` STOP. **There is no volume command**
in the Poweramp API — volume goes through `AudioManager` on `STREAM_MUSIC`.

Poweramp's package name is resolved at runtime via
`resolveService(new Intent("com.maxmpz.audioplayer.API_COMMAND"))`, falling back to the
literal name. Build number is `versionCode > 1000 ? versionCode / 1000 : versionCode`.

### Other output modes, implemented and verified but not in use

- **B** — well-formed `ACTION_DOWN` + `ACTION_UP` pair sent explicitly to `PSMediaButtonReceiver`
- **C** — *only the missing `ACTION_UP`*, completing the head unit's broken press
- **D** — `AudioManager.dispatchMediaKeyEvent` (generic, for other players)

### Status in

Manifest-free, runtime-registered receiver for the **sticky** broadcast
`com.maxmpz.audioplayer.STATUS_CHANGED`, int extra `state`
(`-1` none, `0` stopped, `1` playing, `2` paused). Registering returns the sticky intent,
so current state arrives immediately with no polling.

---

## 5. Settings as deployed (all working — do not change)

| Setting | Value |
|---|---|
| Input 1 — `MEDIA_BUTTON` broadcast receiver | **on** — this is the path that fires |
| Input 2 — own MediaSession | off (unnecessary on this unit) |
| Session re-assert | off |
| Act on `ACTION_DOWN` | **on** — the core fix |
| Swap next/previous | **on** — MCU key table is reversed |
| Only while Poweramp is playing | off |
| Play/pause gesture | **Double-press PREVIOUS**, 350 ms window |
| Output mode | **A** — Poweramp API command |
| Debounce | 400 ms (auto-clamped to 120 ms on the gesture key) |
| Force volume at startup | **on**, target 100% |
| Show volume dialog | off |
| Volume method | Try all (succeeds via `setStreamVolume`) |

---

## 6. The cold-boot problem — everything known

### Symptom

- **Daily driving:** wheel buttons and startup volume work perfectly.
- **After ~2+ days unused:** on the next start, wheel buttons do nothing *and* the
  volume override doesn't run.
- **Opening the SWC Bridge app fixes it completely**, and it stays fixed.
- Both symptoms failing together matters: the wheel buttons run off a *manifest*
  receiver that needs no running process, and the volume ramp runs in the service.
  Both being dead suggests **nothing of ours runs at all**.

### Hard evidence (2026-09-19 persistent log)

Working session, 12:22 — for reference, this is what healthy looks like:

```
09-19 12:22:34  volume [startup 4/4] already 15/15
09-19 12:22:41  [broadcast] KEYCODE_MEDIA_PREVIOUS ACTION_DOWN repeat=0 src=0x0 virt=true
                  -> [swapped] A: Poweramp API NEXT -> com.maxmpz.audioplayer
09-19 12:22:45  [broadcast] KEYCODE_MEDIA_NEXT ACTION_DOWN ... -> armed PREVIOUS, waiting 350ms
09-19 12:22:45  [broadcast] ... -> [double press] A: Poweramp API TOGGLE_PLAY_PAUSE
09-19 12:22:46  poweramp state -> playing
```

Next session, 13:13 — **51 minutes later**:

```
13:13:16.132  === process started ===
13:13:16.139  vendor audio [process start]  musicActive=false  (no vendor keys readable)
13:13:16.238  --- config screen opened ---
13:13:16.279  media session acquired
13:13:16.283  bridge service started (Android 7.0 / API 24, Poweramp build 1025)
13:13:16.401  volume [startup 1/4] already 15/15
```

**Read that carefully.** The process died sometime after 12:22. At 13:13 it was created
**106 ms before the config screen opened** — i.e. it started *because the user opened
the app*, and nothing else. There is **no `startup signal:` line at all**, which is what
`BootReceiver` logs when any boot broadcast arrives.

So even over a one-hour gap, nothing restarted the app on its own.

### Other relevant observations

- **Volume persists** across sleep/wake (`already 15/15` every time), so the volume
  symptom specifically involves a *cold boot* resetting it.
- **`av_channel` and other vendor audio parameters are NOT readable** on this unit
  (`no vendor keys readable`), so the "audio-source gating" theory can't be tested that way.
- The **stock/built-in media player's** wheel buttons have worked from day one,
  immediately after startup, without any bridge app. But this proves less than it seems:
  the vendor MainUI drives its own player through a **direct AIDL call**
  (`nDealMediaKey`), which is a *different branch* of the same function from the one
  that emits a global media key. So "stock player works" does **not** prove the
  broadcast is being sent.
- Wheel **volume** buttons never reach Android at all (pure MCU → amp).
- A **USB wireless remote** *does* change Android's volume, so the media stream is
  genuinely mutable by real hardware key events.
- Android's **safe-media-volume limiter** is `ACTIVE (limiting)` — the AUX output
  presents as a wired headset. Volume currently sits at 15/15 because the confirmation
  dialog was accepted once, and it persists.

### What has already been tried, and failed

| Attempt | Result |
|---|---|
| `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` manifest receiver | No `startup signal:` line has ever been observed |
| Added `QUICKBOOT_POWERON`, `com.htc.intent.action.QUICKBOOT_POWERON`, `autochips.intent.action.QB_POWERON`, `ACTION_POWER_CONNECTED`, `USER_PRESENT`, `MY_PACKAGE_REPLACED`, `MEDIA_MOUNTED` | Shipped in v0.7; **not yet confirmed at a true multi-day cold boot** |
| Foreground service, `START_STICKY` | Cannot survive a power cut |
| Repeating watchdog alarm (non-wakeup) | Useless if the process never starts; also cancelled by a force-stop |
| **A third-party "Auto Start" app** (installed 2026-09-19) | **Did not work** |

That last one is significant. Most auto-start managers themselves rely on
`BOOT_COMPLETED`, so its failure is consistent with the theory that **no boot broadcast
is delivered on this ROM at all**.

### Live theories

1. **No boot broadcast is delivered.** The ROM is a mismatched frankenbuild
   (fingerprint says 5.1.1 / `c200_en`, runtime is 7.0 / `NRD90M`). If `BOOT_COMPLETED`
   is never broadcast, both our receiver and the third-party auto-start app fail for the
   same reason — which matches the evidence. **Currently the strongest theory.**
2. **The app lands in Android's stopped state** (`FLAG_STOPPED`), e.g. from a vendor
   "clean memory" routine. In that state manifest receivers are dead until a human
   launches the app — which matches the symptom exactly. But it doesn't explain the
   auto-start app failing, since launching an activity by explicit component clears that
   flag.
3. **The media-key broadcast is gated on the active audio source.** Decompiled MTK/TS
   firmware only emits a global media key when `av_channel == "sys"`. Can't be tested
   here — the parameter isn't readable.

### The decisive test, not yet run

1. Power the unit fully off, wait long enough for a genuine cold boot.
2. **Before opening SWC Bridge**, press the wheel's next-track button.
3. *Then* open SWC Bridge and read the top of the run.

- `=== process started ===` followed by a `[broadcast] KEYCODE_...` line at the time of
  the press → the manifest receiver *does* wake the app from cold; the problem is only
  that the service (and thus volume) didn't start.
- First line is `=== process started ===` immediately followed by
  `--- config screen opened ---` → nothing reached us; the broadcast isn't being
  delivered, or the app is stopped.

---

## 7. Constraints on any proposed fix

- **No adb access set up.** The unit is in the car, the laptop is not. Installs are done
  by copying the APK to a **USB stick** and tapping it in the unit's file manager.
  ADB over Wi-Fi in the driveway has never been attempted and might work.
- **Not rooted.** No `/system` writes, no privileged permissions.
- **`WRITE_SECURE_SETTINGS` is declared but not granted** (would need a one-time
  `adb shell pm grant`).
- Iteration is slow — each test needs a trip to the car, and the multi-day failure needs
  the car to actually sit for days.
- Build environment: Android Studio on a Windows laptop; project source lives on a
  Google Drive folder (`G:\`), which locks files during `clean` — pause Drive sync first.
- The unit **must still power down** when the car isn't driven. No wakelocks, no
  keep-alive that defeats the MCU's power cut.

---

## 8. Ideas not yet explored

Offered as starting points, not conclusions:

- **Get adb working over Wi-Fi in the driveway.** One session would settle nearly
  everything: `dumpsys package com.mattc.swcbridge | grep -i stopped` shows the stopped
  flag directly; `logcat` at boot shows whether `BOOT_COMPLETED` is broadcast at all;
  `dumpsys activity broadcasts` shows the history. This is probably the single highest-value
  next step.
- **Piggyback on Poweramp.** Add a *manifest* receiver for
  `com.maxmpz.audioplayer.STATUS_CHANGED`. If Poweramp starts at boot (or the moment
  music is started), that broadcast would create our process for us.
- **Hunt for vendor startup broadcasts** specific to this firmware — `com.ts.can.*`,
  `forfan.intent.action.*`, `autochips.*` were all seen in decompiled MTK/TS sources.
  A debug build with a catch-all logging receiver could identify what this ROM actually
  emits at boot.
- **Check the unit's own settings** for a boot-apps list or a task killer that needs a
  whitelist entry (searched for once, not exhaustively).
- **Accept a one-tap workaround**: replace the home-screen shortcut with something that
  launches SWC Bridge and then Poweramp, so one tap does both.
- **Reconsider whether the app must start at all.** If the manifest receiver alone can
  wake the process (pending the test in §6), only the volume ramp needs a startup
  trigger — a much smaller problem.

---

## 9. Where everything lives

```
G:\My Drive\Projects\Programming\ClaudeCode\AndroidHeadUnit\
├── SwcBridge\                     Android Studio project
│   ├── app\                       the bridge app
│   ├── mock\                      fake Poweramp for emulator testing (never install on the unit)
│   └── README.md                  full build/test/tuning documentation
├── PLAN.md                        the original investigation
├── Lexus_Andriod_Headunit_info.md hardware spec sheet
└── Poweramp-build-1025-uni.apk    the real Poweramp APK (ARM only — will not install on an x86 emulator)
```

**On the head unit**, the persistent log is at:

```
Android/data/com.mattc.swcbridge/files/history.log
```

It survives reboots, rotates at 192 KB, and the app seeds its on-screen log from the
last 250 lines at startup — so opening the app shows what did or didn't happen while
nobody was looking. The **SAVE** button writes a timestamped copy alongside it.
