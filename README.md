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

**Status (v0.9.2, Oct 2026):** works end to end. It starts itself after the car is switched off and on, with no user interaction ("lean mode"), and keeps Android's media volume at 100% by topping it up on each NEXT press.
See [docs/](docs/README.md): the [findings doc](docs/SwcBridge-ColdBoot-Findings.md) and two infographics ([overview](docs/infographics/how-swc-bridge-starts-itself.png), [lean mode in detail](docs/infographics/lean-mode-explained.png)).

## Design

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

Output: `app/build/outputs/apk/debug/app-debug.apk`

Zero dependencies — framework APIs only. `minSdk 21`, `targetSdk 24` (deliberately low,
to keep Android 7 semantics and avoid background-execution and package-visibility guards).

## Test it at your desk — no car needed

The whole output half is verifiable on an emulator, using the bundled **`mock` module**.

### Why a mock

`Poweramp-build-1025-uni.apk` ships `arm64-v8a` and `armeabi-v7a` native libraries only —
"uni" means universal across ARM, *not* including x86. It therefore cannot be installed on
an x86_64 emulator (`INSTALL_FAILED_NO_MATCHING_ABIS`), and ARM system images are
impractically slow on an x86 host.

So the `mock` module impersonates it: same package name `com.maxmpz.audioplayer`, the same
exported component names, the same intent filters, and `versionCode 1025009` so it reports
as build 1025. It prints whatever it is handed. That verifies the entire dispatch chain —
package resolution, component addressing, extras — everything except Poweramp's internal
reaction, which is documented behaviour.

> **Never install `mock` on the head unit.** It claims Poweramp's package name. The install
> would be refused on signature mismatch anyway, but don't go looking for trouble.

### Setup

1. **Tools → Device Manager → Create device**. Medium Tablet suits the two-column layout
   (landscape, roomy). System image: **API 24 / Nougat / Intel x86_64 Atom / Android Open Source**.
2. Run configuration **`mock`** → **Run**. Leave it open.
3. Run configuration **`app`** → **Run**.

Studio's drag-to-install does not work on API 24 — it calls `pm install-create --full`, and
that flag doesn't exist until API 28. Use the Run button, or `adb install`.

### Confirm the output path

In SWC Bridge, tap **NEXT**. Mock Poweramp should show:

```
API_COMMAND  cmd=4 (NEXT)  from=com.mattc.swcbridge   <- PowerampAPIReceiver
```

One line, and it proves package resolution, component addressing and the extras are all
correct. Cycle the output mode through **B** and **C** and the same button lands on
`PSMediaButtonReceiver` instead — as `ACTION_DOWN`+`ACTION_UP`, then as `ACTION_UP` alone.

### Confirm the input path

Tap **"Simulate head unit bug (MEDIA_NEXT, DOWN only)"**. SWC Bridge's log shows which path
caught it (`[broadcast]` or `[session]`); Mock Poweramp shows the command that resulted.
That is the whole fix, end to end.

### See the fault itself

Turn **both input paths OFF**, then tap "Simulate head unit bug". Mock Poweramp receives a
bare `ACTION_DOWN`, and no `ACTION_UP` ever follows. That is precisely the state the real
Poweramp sits in — forever — in the car.

### Test the playing-state gate

Tap **Report PLAYING** / **Report PAUSED** in the mock, then toggle "Only act while Poweramp
is playing" in the bridge.

Only the *input* half genuinely needs the car, because only the car has the broken MCU.

## Verified on the desk — 2026-09-05

Emulator: `Lexus_HeadUnit`, API 24 / Nougat / x86_64 / AOSP, with the `mock` module
standing in for Poweramp.

| What | Result |
|---|---|
| Runtime package resolution | `resolveService(ACTION_API_COMMAND)` found the target; `versionCode / 1000` reported **build 1025** |
| MediaSession acquired | yes, at service start |
| Output **A** — Poweramp API | `API_COMMAND cmd=4 (NEXT) from=com.mattc.swcbridge` -> `PowerampAPIReceiver` |
| Output **B** — DOWN+UP pair | `MEDIA_BUTTON ACTION_DOWN` + `ACTION_UP` -> `PSMediaButtonReceiver` |
| Output **C** — missing UP only | `MEDIA_BUTTON ACTION_UP` alone -> `PSMediaButtonReceiver` |
| Normal press (DOWN + UP) | caught by `[session]`; UP correctly ignored; **exactly one** command emitted, no double-skip |
| **Head unit fault (DOWN, no UP)** | caught by `[session]`, dispatched as `A: Poweramp API NEXT`, **3 ms** end to end |

The simulated events carried `virt=true`, matching the real head unit's readout from
forum thread 20241.

**Not testable at the desk:** input path 1 (the `MEDIA_BUTTON` broadcast receiver).
Nothing on an emulator broadcasts `ACTION_MEDIA_BUTTON` the way a vendor head-unit app
might, so it stays enabled purely as insurance for the car.

Everything except the head unit's own key injection is therefore verified.

## v0.3 — startup volume and a play/pause gesture

### Force Android volume at startup

On this unit Android's `STREAM_MUSIC` level is a **pre-gain stage** feeding the OEM amp
over AUX — the car's own volume knob sits downstream of it. Leaving Android low throws
away signal-to-noise and makes you compensate on the car side, so the sane arrangement is
to pin Android near maximum and control level with the car.

`VolumeControl` applies the target at **0 s, 3 s, 10 s and 30 s** after the service starts.
One attempt is not enough: the audio stack is still coming up, and vendor firmware often
sets its own level a few seconds in. The ramp runs from `BridgeService.onCreate`, which
fires once per process — so it happens on a real startup (the boot receiver starting the
service) and not every time the config screen is opened.

Target is a percentage, default 100%. Back it off if 100% clips into the OEM amp.
The log reports before/after and says explicitly if the level did not stick.

### Double-press = play/pause

Defaults to the **PREVIOUS** button. The reason is a genuine trade-off:

> To know whether a press is a single or the first half of a double, the single-press
> action has to be **held back for the length of the window**. Only the key carrying the
> gesture pays that cost — so it goes on Previous, which is pressed far less often than
> Next.

Long-press was never an option here: measuring a hold needs the release, and this head
unit never sends `ACTION_UP` at all. The same defect that started the project also rules
out the obvious alternative gesture.

Two implementation details worth knowing:

- **The swap is applied *before* the gesture check**, so the gesture follows the physical
  button you press, not the keycode the reversed MCU emits.
- **The duplicate-event guard is clamped to 120 ms on the gesture key.** That guard exists
  to reject the *same* event arriving twice (both input paths delivering it); it is not a
  user-intent filter. Left at the 400 ms default it would silently swallow the second
  press and the gesture would simply never fire.

The delayed single press needs the process alive when it fires, so arming the gesture also
calls `BridgeService.start()` — a no-op when the foreground service is already up.

### Settings added

| Setting | Default |
|---|---|
| Play/pause gesture | Double-press PREVIOUS |
| Double-press window | 350 ms (floor 150 ms) |
| Force Android volume at startup | on |
| Target volume | 100% (floor 10%) |
| *Apply volume now* button | — |

`MODIFY_AUDIO_SETTINGS` added to the manifest — normal protection level, granted at
install, no prompt. Not strictly required for `setStreamVolume` on `STREAM_MUSIC`, but
some OEM audio stacks check for it.

## v0.4 - v0.6 — the volume story

Worth recording in full, because the first diagnosis was wrong and the log said so.

### What it looked like

`setStreamVolume` returned cleanly, threw nothing, and the index did not move:

```
volume [button] 9 -> 9 of 15  (target 15 = 100%)
 ! volume did not stick -- something else is holding it
```

The obvious reading — "the vendor audio layer owns this stream, like the SWC keys" —
was wrong.

### The clue that broke it open

Two observations from the car, which point in opposite directions:

- The **steering wheel** volume buttons never reach Android at all. They are handled by
  the MCU and change the amp directly. Nothing to do with Android's mixer.
- The **USB wireless remote** *does* move Android's volume.

So `STREAM_MUSIC` was demonstrably settable — the system was honouring a real hardware
volume key while ignoring our programmatic call. That is not what a vendor lock looks like.

### The actual cause

Android's **safe-media-volume limiter** — the EU listening-level cap. It arms whenever the
output device reports as a wired headset, which is how this unit's AUX line presents, and
`AudioService` then refuses any index above the safe ceiling (10 of 15 here).

The two halves of the original mystery both fall out of one mechanism:

- `setStreamVolume(15, flags=0)` was **rejected outright** for exceeding the ceiling —
  silently, with no exception. Hence "did not stick".
- Adding `FLAG_SHOW_UI` turns that silent rejection into the *"Raise volume above
  recommended level?"* dialog. Tapping OK is what let it reach 15 — so the dialog was not
  incidental to the fix, it *was* the fix.

`adjustSuggestedStreamVolume` is what the framework itself calls for a hardware VOLUME_UP,
which is why it behaves like the remote does.

### Three ways out

| Setting | Dialog | Level reached |
|---|---|---|
| Show volume dialog **off** *(default)* | none | climbs silently to the safe ceiling (~10/15) |
| Show volume dialog **on** | every startup | full 15/15 after confirming |
| **Disable safe-volume limit** | none | full 15/15, permanent |

The third writes `Settings.Global.audio_safe_volume_state = 1` (DISABLED). That needs
`WRITE_SECURE_SETTINGS`, a development-protection permission — a normal install cannot
hold it, but it can be granted once:

```
adb shell pm grant com.mattc.swcbridge android.permission.WRITE_SECURE_SETTINGS
```

`AudioService` reads that value at boot, so it takes effect on the next restart. Without
the grant the button simply prints that command into the log.

Note what it switches off: a hearing-protection cap. In this car it is measuring the wrong
thing — Android's level is pre-gain into the OEM amp, and loudness is set downstream by the
car's own volume — but on headphones the cap is doing a real job.

### Diagnostics added

- **Volume snapshot** — dumps every stream's index plus `isVolumeFixed`, audio mode, and
  the safe-volume state. Snapshot, press volume on the **USB remote** (not the wheel, which
  never reaches Android), snapshot again: whichever number moved is the stream in use.
- Five selectable set-volume methods with a "try all, report what works" mode.
- The log now distinguishes *"stopped at the safe ceiling"* from *"nothing moved at all"* —
  the distinction that would have saved a version or two.

### Lesson

"The call is ignored" had two candidate explanations that look identical from one data
point. The second observation — one input path working while another failed — is what
separated them. When a silent no-op has more than one plausible cause, find the case that
*does* work and ask what is different about it.

## v0.9.2 — volume top-up on NEXT (2026-10-04)

The real loudness is set with the car amp, so Android's media volume should simply
sit at the top. The startup ramp can't get it there (Android's safe-volume limiter
stops it at 10/15), so v0.9.2 also tries on every NEXT press:

- After the NEXT command goes to Poweramp, the app reads the media volume. If it's
  below the target (the volume % slider, default 100%), it raises it: volume-up steps
  (the same call a real volume-up key makes), then stream steps, then a direct set.
- **It logs one line per press**, for example `volume [NEXT] 10/15 -> 15/15  OK`, or
  `... REFUSED (target 15) [...] safe:ACTIVE (limiting) music active:true`.
- **New switch:** "Top up Android volume on every NEXT press" (on by default).
- "Show volume dialog" applies to it too. With it on, a raise past the limit pops
  Android's confirmation instead of being silently refused.
- It covers NEXT from the wheel and the USB remote, including a single press held for
  the double-press window. It doesn't run on PREVIOUS or play/pause.
- Nothing about start-up, lean mode, the key handling or the gesture changed.

## v0.9.1 — keep the task (2026-09-26)

The v0.9 driveway test failed: after pressing Home, the wheel stopped working and
Force stop was greyed out. The log showed why. `config screen closed` was the last
line, and neither `bridge service stopped` nor `process exit` followed: the app was
killed within milliseconds of v0.9 calling `finishAndRemoveTask()`. **On this head unit,
removing an app's task in any way force-stops the whole app**, whether the user swipes
it or the app does it itself.

- **Leaving the config screen now only calls `finish()`.** The hidden task is left alone.
- **New test switch, "lean mode: app ends its own process when idle"** (default ON). If
  the wheel still dies after pressing Home even with this build, turn it OFF and repeat.
  That shows whether ending the process is what trips the force-stop.

## v0.9 — lean mode (2026-09-26)

**v0.8 did not fix the cold start**, and its diagnostics showed why. At switch-off the
unit force-stops the apps that are *running*, except the one playing audio: Poweramp
survives only when it is playing, and is force-stopped too when paused. The kill happens
before Android starts to shut down, so no shutdown or screen-off broadcast ever arrives.
v0.7/v0.8 kept a foreground service running permanently, so SWC Bridge was always a
running app at switch-off, and was always killed. Hiding it from recents (v0.8) didn't
change that.

**Lean mode (the new default)** makes sure nothing of ours is running at switch-off. The
steering wheel never needed a running app: `MediaButtonReceiver` is a manifest receiver,
so Android starts the process on demand for every press.

- **At startup:** a startup signal starts the service, which runs the startup-volume ramp
  (once per boot), stops itself, and the process ends about 35 s after boot.
- **Wheel press:** Android starts the process, the press is handled exactly as before,
  and the process ends 5 s later (long enough for the double-press window).
- **Config screen:** works normally while shown. Leaving it closes it completely
  (`finishAndRemoveTask`), and the process ends about 2 s later.
- **Not running in lean mode:** no foreground service or notification, no own
  MediaSession (Input 2), no 15-minute watchdog. `START_NOT_STICKY`, so Android never
  restarts the service behind our back. The v0.8 shutdown receiver is no longer
  registered: it could only ever have started the app at switch-off.
- **"Keep bridge running in background"** (new switch, off by default) restores the
  v0.8 behaviour for A/B testing without rebuilding.

Key handling, swap, gesture, debounce, Poweramp commands and the volume logic are
unchanged. The startup volume now runs once per boot instead of every time the service
starts.

**Log changes**

| Line | Meaning |
|---|---|
| `=== process started: <reason> === v0.9 (build 9)  uptime …  boot #N` | One line per process. For a wheel press it adds `cold start N ms`: the delay from process launch to handling the press. |
| `first run this boot, started by the system -> the app was NOT force-stopped at the last switch-off` | **The success line.** |
| `first run this boot, … only because it was opened by hand -> the app WAS force-stopped` | The failure line. |
| `previous boot: last activity … (boot #a -> #b)` | Logged by the first process of each boot, along with vendor audio and Poweramp's package state. |
| `process exit (lean mode, idle) -- nothing of ours is running now` | The process ended itself. |
| `bridge service stopping (lean mode: startup done, screen closed)` | |

History-file timestamps now include milliseconds. The screen loads the history when it
opens rather than on every process start, so wheel presses don't pay for reading it.

**Testing v0.9**

1. **In the driveway:** install it, open it once, press Home, wait 10 s, press the wheel.
   It should work, and the log should show `process started: media button broadcast`.
   That proves closing the screen didn't force-stop the app.
2. **The real test:** switch off (no wheel press in the last ~5 s, and not with the SWC
   Bridge screen open), wait 5+ minutes, restart, press the wheel before opening
   anything. Look for `first run this boot, started by the system`.

## v0.8 — the cold-start fix (2026-09-26)

**What was actually wrong.** Tests on the car proved that this head unit *force-stops*
any app that is cleared from the recent-apps list (stock Android only closes its window),
and that it appears to clear that list when the car is switched off. A force-stopped app
receives no broadcasts at all — not `BOOT_COMPLETED`, not the wheel's `MEDIA_BUTTON` —
until someone opens it by hand. Opening it put it straight back into recents, ready to be
killed at the next shutdown. The boot receivers were never the problem: on the one start
where the app had *not* been force-stopped, `MEDIA_MOUNTED`, `BOOT_COMPLETED` and
`USER_PRESENT` all arrived and the wheel worked without the app being opened.
The full investigation is in the project doc `coldboot-findings.md`.

**Changes**

- **`excludeFromRecents` on `MainActivity`** — the fix. The app never appears in recent
  apps, so the unit's shutdown cleanup has nothing to clear.
- **Poweramp commands carry `FLAG_INCLUDE_STOPPED_PACKAGES`.** The cleanup can leave
  Poweramp force-stopped too (seen after a 2-day sit); without the flag Android silently
  drops even an explicit broadcast to a stopped app. Now a wheel press starts Poweramp.
- **Swap next/previous applies to the steering wheel only** (virtual key events,
  `virt=true`). v0.7 also swapped the USB remote, so its NEXT went backwards.
- **Watchdog alarm is non-wakeup.** v0.7 used `ELAPSED_REALTIME_WAKEUP`, waking the SoC
  every 15 minutes while parked.
- **Earlier start:** `TIME_SET` / `TIMEZONE_CHANGED` added to the boot receiver (the unit
  sets its clock from Wi-Fi/GPS ~10 s after boot, well before `MEDIA_MOUNTED` at ~40 s).
  A wheel press that wakes the app now also starts the service (startup volume, heartbeat).
- **Clear really clears.** It renames `history.log` to `history-YYYY-MM-DD_HHmmss.log`,
  deletes it, and empties the screen. The newest 20 archives are kept.
- **Version on screen** in the title bar and status line.

**New log lines**

| Line | Meaning |
|---|---|
| `=== process started === v0.8 (build 8)  uptime 0:00:40  boot #N` | Uptime is time since the unit booted — trust it over the clock, which starts at 1969 (`12-31 18:00`) on every boot |
| `process start reason: …` | What created the process: a startup signal, a media button, the watchdog, a sticky restart, or `user opened the app` (with a note when that looks like a force-stop) |
| `previous run: last seen alive … -- unit has REBOOTED / SAME boot` | From a 30 s heartbeat the service leaves behind |
| `previous run: shutdown signal WAS received / NO shutdown signal …` | Whether Android's `ACTION_SHUTDOWN` reached the app before power-off — i.e. whether the kill comes before or after Android shuts down |
| `screen OFF` / `screen on` / `power disconnected` (with uptime) | The switch-off sequence as the running app sees it; if the log stops right after `screen OFF`, that is where it was killed |
| `poweramp package: STOPPED / not stopped` | Whether Poweramp was also force-stopped |

**How to test v0.8:** install it, open it **once** (a fresh install starts in the stopped
state), leave it, turn the car off for 5+ minutes, restart, press the wheel **without**
opening anything. Then open the app and look for a `startup signal:` line above
`--- config screen opened ---`.

## Verified in the car — 2026-09-05

Device: `alps lexus_mrw1920x720`, Android 7.0 / API 24, real Poweramp build 1025.
**It works.** Steering wheel next/previous now drive Poweramp.

Findings from the first live run:

- **Input path 1 wins.** Every wheel press arrives as `[broadcast]` — the vendor app
  broadcasts `ACTION_MEDIA_BUTTON` directly, exactly as decompiled MTK/TS firmware does.
  Input path 2 (our own MediaSession) is not needed on this unit and can be turned off.
- **No duplicates.** One log line per press: the max-priority receiver aborts the ordered
  broadcast, so the malformed half-press never reaches Poweramp at all.
- **`ACTION_DOWN` only, confirmed on real hardware**, `virt=true`, `repeat=0` — matching
  the 2020 readout exactly. No `ACTION_UP` ever arrives.
- **The MCU's key table is reversed.** Pressing *next* on the wheel emits
  `KEYCODE_MEDIA_PREVIOUS`, and vice versa. Fixed with the "Swap next / previous" toggle
  (v0.2). This is hardware, not software — the log shows the raw keycode as received.
- The unit's clock jumps around (log timestamps went 17:13 -> 16:14). Cosmetic; the RTC
  and network time disagree until it settles.

### Recommended settings for this head unit

| Setting | Value |
|---|---|
| Input 1 — MEDIA_BUTTON broadcast receiver | **on** |
| Input 2 — own MediaSession | **off** (unnecessary here; frees the media session) |
| Session re-assert | off (irrelevant with input 2 off) |
| Act on ACTION_DOWN | **on** |
| Swap next / previous | **on** |
| Only act while Poweramp is playing | off, unless the wheel starts stealing focus from radio/CarPlay |
| Output mode | **A** |
| Debounce | 400 ms |

## Install in the car (no adb)

1. Copy `app-debug.apk` to a USB stick.
2. On the head unit: **Settings → Security → Unknown sources** → enable.
3. Plug the stick in, open the file manager, tap the APK, install.
4. Open SWC Bridge. It starts its service and auto-starts on boot from then on.

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
