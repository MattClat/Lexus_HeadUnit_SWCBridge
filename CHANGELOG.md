# Changelog

What changed in each version of SWC Bridge, newest first. The README describes the current version.

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

First successful self-start after a cold boot: 3 Oct 2026 (see the
[findings doc](docs/SwcBridge-ColdBoot-Findings.md)).

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
The full investigation is in [docs/SwcBridge-ColdBoot-Findings.md](docs/SwcBridge-ColdBoot-Findings.md).

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

## v0.7 — trying to start at boot (Sep 2026)

- The bridge ran as a permanent foreground service (`START_STICKY`), with a 15-minute
  watchdog alarm (`ELAPSED_REALTIME_WAKEUP`) to restart it after ordinary kills.
- More startup signals in the boot receiver: `QUICKBOOT_POWERON` (and the HTC and Autochips
  variants), `ACTION_POWER_CONNECTED`, `USER_PRESENT`, `MY_PACKAGE_REPLACED`, `MEDIA_MOUNTED`.
- Next/previous swap applied to the USB remote as well as the wheel.
- None of this fixed the cold start; v0.8 found why. Background:
  [docs/SwcBridge-ColdBoot-Handoff.md](docs/SwcBridge-ColdBoot-Handoff.md).

## v0.4 – v0.6 — the volume fix (by 2026-09-12)

- Found why Android's volume would not go above 10 of 15: Android's **safe-media-volume
  limiter**, armed because the unit's AUX output reports as a wired headset.
- **Show volume dialog** option: a raise past the limit shows Android's confirmation instead of
  being silently refused.
- **Disable safe-volume limit** button (needs a one-time
  `adb shell pm grant com.mattc.swcbridge android.permission.WRITE_SECURE_SETTINGS`).
- **Volume snapshot** diagnostics, five selectable set-volume methods with a "try all" mode,
  and a log that tells "stopped at the safe ceiling" from "nothing moved at all".

The full story: [docs/volume-story.md](docs/volume-story.md).

## v0.3 — startup volume and a play/pause gesture (by 2026-09-12)

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

## v0.2 — swap next/previous (2026-09-05)

- **"Swap next / previous" toggle.** The first live run showed the head unit's MCU has its
  key table reversed: pressing *next* on the wheel sends `KEYCODE_MEDIA_PREVIOUS`, and vice versa.

## v0.1 — first version (2026-09-05)

- Two input paths (a max-priority `MEDIA_BUTTON` receiver and an own `MediaSession`), four
  output modes, debounce, optional gating on Poweramp's playing state, an on-screen event log
  with export, and the `mock` module for desk testing.
- Verified at the desk and in the car on 2026-09-05: see [docs/TESTING.md](docs/TESTING.md).
