# SWC Bridge — Cold-Start Investigation: What We Know

*Final update 7 Oct 2026: project complete. Earlier updates: §14 startup timing, the overnight test, Developer options (§8). Originally written 26 Sep 2026, evening. This covers everything since the original handoff doc (`coldboot-handoff.md`, 20 Sep) and supersedes that doc's "live theories" section.*

> ## ✅ PROJECT COMPLETE (7 Oct 2026): SWC Bridge v0.9.2

**Both goals are met.** The wheel controls Poweramp after a cold start with no user interaction, and Android's media volume goes to 100% by itself. The unit still powers down fully when the car is off.

## Final status

### What works

| | Result | How |
|---|---|---|
| **Self-start after the car is off** | ✅ 3/3 car-off tests passed: 10 min, ~3 h, overnight | v0.9.1 "lean mode": SWC Bridge is never a running app at switch-off, so the unit's switch-off cleanup doesn't force-stop it. Android starts it on demand for each wheel press (cold start 30–50 ms). |
| **Wheel → Poweramp** | ✅ NEXT / PREV / double-press PREV = play/pause | Unchanged since v0.3–v0.8: act on the DOWN-only virtual key, swap the wheel's reversed NEXT/PREV, send Poweramp API commands. |
| **Volume at 100%** | ✅ the first NEXT once music is playing raises it to 15/15, no dialog | v0.9.2: every NEXT checks the media volume and raises it if low. The real loudness is set with the car amp. |
| **Power-down** | ✅ unchanged | Nothing keeps the unit awake. |

### Timing after starting the car

- **The unit boots Android from scratch every time.** Switching off is a full shutdown, and the clock starts at 31 Dec 1969 18:00.
- **SWC Bridge first runs ~35–40 s after power-on.** That's the earliest Android allows any app to run.
- **Wheel presses before ~55 s uptime are queued** by Android or the vendor sender, then delivered in a burst. In practice the wheel works ~15 s after Poweramp appears (8th press at 2 s intervals, 7 Oct). **That's the floor; nothing in the app can shorten it.** Matt finds it acceptable.
- **A burst of queued presses can skip several tracks.** Press once and wait instead of pressing repeatedly.
- **The first NEXT of a drive may log the volume top-up as `REFUSED … music active:false`.** Android won't raise the volume until music is actually playing. The next NEXT with music playing takes it to 100%. Normal; nothing to fix.

### Known limitations and how to recover

| Situation | What happens | Fix |
|---|---|---|
| **Poweramp paused** at switch-off | Poweramp doesn't come back, and SWC Bridge is force-stopped (5 Oct) | Open SWC Bridge once (and Poweramp). Habit: switch off with music **playing**. |
| Wheel pressed in the **last ~5 s** before switch-off | The app is still running → force-stopped | Open SWC Bridge once on the next start. |
| Switching off with the **SWC Bridge screen open** | Same | Same. |
| Switching off **within ~1 min of starting** the car | The startup service may still be running → force-stopped | Same, or turn off "Force Android volume at startup" (see below). |
| **Never swipe SWC Bridge or Poweramp out of recent apps** | This unit force-stops anything removed from recents | Open it again. |

**How to tell it was force-stopped:** the wheel does nothing after a restart. Settings → Apps → SWC Bridge → **Force stop is greyed out** (look only, don't tap). Opening the app clears it.

### Final settings (SWC Bridge screen)

- **Keep bridge running: OFF** (lean mode). **Lean self-exit: ON.** **Top up Android volume on every NEXT press: ON.**
- **Swap next/prev: ON** (the MCU reverses them; it applies to the wheel only). Gesture: double-press PREVIOUS = play/pause (default). Output: A, Poweramp API.
- **Optional:** "Force Android volume at startup" can be turned **off**. The NEXT top-up does its job, it never got past the limit at startup anyway, and with it off the app stops right after starting, which closes the "switched off within a minute" gap.

### Optional extras (offered, not built)

- **"Poweramp + Wheel" home-screen icon:** one tap opens SWC Bridge and then Poweramp, for the paused-at-switch-off case.
- **Shorter linger:** after a wheel press, 5 s → ~1.5 s, to shrink the "pressed just before switch-off" window.
- **Volume retry:** if the top-up is refused because music isn't playing yet, retry a few seconds later.
- **Collapse queued presses:** treat a burst of presses during boot as one NEXT.
- **Not needed:** adb / WRITE_SECURE_SETTINGS (option B), rooting, OEM unlocking. Leave those alone.

### Where everything is

- **Source:** `AndroidHeadUnit\SwcBridge` (versionCode 11, v0.9.2). README sections per version.
- **APKs:** `AndroidHeadUnit\!Latest_APKs` (plus older 0.3, 0.5 and 0.8 builds in `AndroidHeadUnit`). Install by USB stick, then open the app once after each install.
- **Log on the unit:** `Android/data/com.mattc.swcbridge/files/history.log`. Clear archives it as `history-<date>.log`.
- **This doc** (`SwcBridge-ColdBoot-Findings.md` / project `claude/coldboot-findings.md`) is the full history. Everything below is the investigation record, kept for reference.

---

*History: status as of late 26 Sep.* v0.8 installed and tested. **Hiding from recents did NOT fix it.** v0.8's new diagnostics showed the rule the unit follows: **at switch-off it force-stops running apps, except the one that is playing audio.** That led to v0.9 lean mode (§12), v0.9.1 (§12b) and v0.9.2 (§13–14).

---

## Summary as of 26 Sep (history)

- **The app works.** Once SWC Bridge is running, the whole chain works: wheel press → manifest receiver → Poweramp API command → track changes. The double-press play/pause works too.
- **The cause: the head unit force-stops SWC Bridge.** Proven on 26 Sep:
  - On a failing start, SWC Bridge's **Force stop** button was greyed out, which means the app was in Android's stopped state.
  - **Swiping SWC Bridge out of the recent-apps list killed the wheel instantly.** This unit force-stops any app cleared from recents; stock Android only closes its window.
  - A force-stopped app receives no broadcasts at all (no `BOOT_COMPLETED`, no wheel `MEDIA_BUTTON`) until it's opened by hand.
- **Autostart itself was never broken.** On the one start where the app *hadn't* been force-stopped (26 Sep, ~30 min off), `MEDIA_MOUNTED`, `BOOT_COMPLETED` and `USER_PRESENT` all arrived. The app started itself, and the wheel worked without the app being opened.
- **Why it kept happening: a loop.**
  1. The unit clears recent apps when the car turns off, force-stopping whatever is listed.
  2. Opening SWC Bridge to recover put it straight back into recents.
  3. At the next shutdown, it got force-stopped again.
- **Turning the car off reboots the unit**, even after 5 minutes. Every fresh boot starts the clock at **18:00 on 31 Dec 1969**, which is Unix time zero in Central time (§3.8).
- **The fix in v0.8:** `excludeFromRecents`. The app never appears in recents, so the cleanup has nothing to clear. Built and installed 26 Sep; test pending.
- **The startup volume override is a separate bug.** Android's safe-volume limiter refuses `setStreamVolume` above 10/15. (On 26 Sep at ~15:04 the status line read 15/15, for reasons not yet known; see §3.11.)

---

## 1. Setup and constraints

| | |
|---|---|
| Head unit | Aftermarket 4x4shop.ca unit, 2014–2019 Lexus IS, `lexus_mrw1920x720`, MT8783T, Android 7.0 / API 24 (mismatched fingerprint), not rooted |
| App | `com.mattc.swcbridge`, **v0.8 (build 8)** installed 26 Sep (was v0.7). Java, no dependencies |
| Player | Poweramp build 1025, driven through the exported `PowerampAPIReceiver` (`cmd` 4 = NEXT, 5 = PREVIOUS, 1 = TOGGLE) |
| Install method | APK copied to a USB stick and installed from the file manager. The app is always opened once after each install. |
| Build | Android Studio on the Windows laptop. Source is in Google Drive at `G:\My Drive\Projects\Programming\ClaudeCode\AndroidHeadUnit\SwcBridge`. APK output: `app\build\outputs\apk\debug\swc-bridge-0.8-debug.apk` |
| Constraints | No adb (Wi-Fi adb considered and declined). Not rooted. The unit must still power down. Don't redesign any of the working parts. |
| USB | A hub on the unit holds the wireless remote's dongle permanently, plus the thumb drive when needed. |

---

## 2. Evidence, in order

Timestamps come from the unit's clock. Right after a boot it reads **18:00-something** until it syncs (§3.8).

| When | Situation | What was pressed | What the log showed | What it means |
|---|---|---|---|---|
| **19 Sep 12:22 → 13:13** (handoff doc) | 51-minute gap | Nothing | 12:22 working, volume **15/15**. At 13:13 the process started 106 ms before the config screen opened. No `startup signal:`. | Nothing was pressed, so it doesn't show whether the receiver was dead. |
| **20 Sep 10:25** | Normal use | Wheel | `[broadcast]` lines and the gesture working. Volume stuck at 10/15, `safe-volume ACTIVE`. The run ends with `10:25:30 --- config screen opened ---`. | The volume fails even while the buttons work. |
| **20 Sep 13:38** (~3 h later) | Start | Wheel before opening: nothing | `13:38:16.458` process started, `.575` config screen opened. No `[broadcast]`, no `startup signal:`. | **Failure.** |
| **20 Sep 14:12** | App reopened | — | Process started and config screen opened in the same second. | — |
| **23 Sep 06:59** (~2.5 days later) | Start | Wheel before opening: nothing | `06:59:30.627` process started, `.747` config screen opened. No `[broadcast]`. `06:59:33.277 poweramp state -> stopped` (Poweramp opened by hand). | **Failure.** |
| **24 Sep 10:18–10:22** | Normal use | USB remote, in-app volume button | Remote arrives as `[session] ... src=0x301 devId=5` (real DOWN+UP), and **was swapped to PREVIOUS**. `volume [button]` failed. `watchdog tick (process alive)`. | While the service runs, the remote comes in through the app's own MediaSession. |
| **26 Sep morning** (2 days off) | Start | 1) USB remote NEXT, nothing open: nothing, no music. 2) Wheel NEXT: nothing. | `10:48:31.648` process started, `.764` config screen opened. Poweramp had **not** auto-started. | **Failure.** Nothing reached either app. |
| **26 Sep morning, continued** | SWC Bridge open, Poweramp playing | Wheel NEXT ×3 | `10:49:57`, `10:49:59`, `10:50:02` `[broadcast] PREVIOUS virt=true -> [swapped] NEXT`. **Track changed.** | Opening the app recovers everything. |
| **26 Sep drive** | Normal use | Wheel | Working `[broadcast]` lines stamped `10:14:58`–`10:29:58`, appearing *after* the run that started at "10:48". | Not explained; possibly another run that wasn't photographed. |
| **26 Sep gas stop** (~5 min off) | Start | Wheel ×5: nothing. Remote: worked (Poweramp took it). | **Poweramp auto-started.** `18:03:37.461` process started (**3 min 37 s after boot**), `.580` config screen opened. No startup signal, no `[broadcast]`. | **Failure after a real reboot.** |
| **26 Sep 11:56:21** | Unknown | — | A run whose last line is `vendor audio [process start]`, with nothing else before the next run. | **Something started the process without logging why.** Unexplained. It's the run just before the only success. |
| **26 Sep, ~30 min off** | Start | Wheel, with the app **never opened** | `18:00:40.658` process started → `.669 startup signal: MEDIA_MOUNTED` → `18:00:41.214 bridge service started` → `18:00:44.572 poweramp state -> paused` → `18:00:48.692 startup signal: BOOT_COMPLETED` → `18:00:50.799 startup signal: USER_PRESENT`. Then wheel: `12:55:11` double press → TOGGLE, and `12:55:32` / `12:55:34` → NEXT, all **before** `12:55:47 --- config screen opened ---`. Status → **Up time 0:03:39**. | **Success.** The first `startup signal:` lines ever seen. |
| **26 Sep 13:02** | Same drive, after the screen was opened at 12:55:47 | Wheel | `13:02:12 [broadcast] ... -> NEXT` | Working. |
| **26 Sep 13:37** (~15 min off) | Start | Wheel: nothing. **Poweramp auto-started.** | Before opening anything: **SWC Bridge Force stop greyed out, Poweramp Force stop active.** Then `13:37:04.632` process started, `.739` config screen opened. No startup signal. | **Failure, with the stopped state confirmed directly.** It was predicted in advance: the screen had been opened at 12:55:47. |
| **26 Sep, recents test A** | App open, wheel working | Swiped **SWC Bridge only** out of recents, then pressed the wheel | The wheel **stopped working immediately**. | **Clearing an app from recents force-stops it on this ROM.** |
| **26 Sep, "leave it on screen" test** (5 min off) | Poweramp swiped away (force-stopped). SWC Bridge opened at 13:49:34 and left on screen at shutdown. | Restart | The clock showed 12:12 for ~10 s, then synced over Wi-Fi. **Nothing came back on screen. Force stop greyed out for both apps.** `14:08:16.897` process started, `14:08:17.004` config screen opened. | (1) The unit does **not** restore the last on-screen app. (2) Being on screen **doesn't protect** SWC Bridge. (3) Once Poweramp is swiped away, it doesn't come back by itself either. |
| **26 Sep ~15:04, v0.8 installed** | Fresh install, opened once | — | Title bar **"SWC Bridge v0.8 (build 8)"**. `15:04:04.102 log cleared -- previous log saved as history-2026-09-26_150404.log`. Status: uptime 0:03:13, Poweramp playing, **volume 15/15** (safe-volume still ACTIVE). | v0.8 works, and the new Clear works. The car was then turned off. |
| **26 Sep halftime, first v0.8 restart** (~1.5 h off; Poweramp was **playing** at switch-off) | Start | Wheel before opening: nothing. **Poweramp auto-started.** SWC Bridge not in the recent-apps list. | `16:41:59.234 === process started === v0.8 (build 8)  uptime 0:02:46  boot #1171`. `previous run: last seen alive 09-26 15:08:33 at uptime 0:07:53 -- unit has REBOOTED since (boot #1170 -> #1171)`. `previous run: NO shutdown signal reached the app before power-off`. `poweramp package: not stopped`. `process start reason: user opened the app (unit booted 0:02:46 ago and no startup signal reached us: the app was almost certainly force-stopped)`. Volume **already 15/15** on all four attempts. The wheel then worked (`16:43:01` double press → TOGGLE, `16:43:05` → NEXT). | **v0.8 did not fix it.** The app was killed right at switch-off: it was last seen alive seconds after the last wheel press, and no screen-off or shutdown signal came first. Poweramp, which was playing, was spared. |
| **26 Sep, "pause Poweramp" test** (6 min off; Poweramp **paused** before switch-off) | Start | — | **Poweramp did NOT auto-start.** `16:56:33.544 === process started === v0.8  uptime 0:01:33  boot #1172`. `previous run: last seen alive 09-26 15:48:29 at uptime 0:09:17 -- REBOOTED (#1171 -> #1172)`. `NO shutdown signal`. **`poweramp package: STOPPED (force-stopped; will not start by itself)`**. Start reason: user opened the app, force-stopped. Volume back to **10/15**, refused. | **When Poweramp isn't playing at switch-off, it gets force-stopped too.** The cleanup spares only the app that is playing. The same rule explains the 2-day sit (Poweramp stopped) and the leave-on-screen test. |

---

## 3. What's established

1. **The key handling and the Poweramp interface work.** Wheel presses arrive as `[broadcast] ... src=0x0 virt=true` ACTION_DOWN, get swapped, and reach Poweramp as API commands.
2. **The ROM delivers boot broadcasts, and the receivers work.** On the successful start, `MEDIA_MOUNTED` arrived first (~40 s after boot) and created the process. `BOOT_COMPLETED` came 8 s later, then `USER_PRESENT`.
3. **Turning the car off reboots the unit**, even for 5 or 30 minutes: up time 0:03:39, and runs start at an 18:0x clock (§3.8).
4. **SWC Bridge was force-stopped on every failing start.** It's confirmed directly by the greyed Force stop button (13:37, 14:08). On all six logged failures, the process was only created when the app was opened by hand.
5. **This unit force-stops apps cleared from recents** (test A). That's the mechanism.
6. **The failure is intermittent, not tied to time off.** A 5-minute stop failed and a 30-minute stop worked. What matters is the app's state at shutdown, not how long the car sits.
7. **The one lead that predicted correctly** is whether the app's screen was opened during the previous drive, i.e. whether it was in recents at shutdown. Every failure followed a drive where the screen had been opened. The one success followed a stretch where the app was running without its screen being opened (the unexplained 11:56:21 process start). A prediction made in advance (fail at 13:37) came true.
8. **The clock's startup value decodes as up time.** 18:00 on 31 Dec 1969 in Central time is Unix time zero. So a run stamped **18:0x** began that many minutes after boot. The clock then syncs from Wi-Fi or GPS about 10 s in and jumps to the real time. The log later labelled those lines `12-31 18:00:51`, which confirms this. The "12:00" / "12:12" Matt sees on screen right after a start is the same unset state in the unit's own clock display.
9. **Opening SWC Bridge is what recovers the wheel, not opening Poweramp.** At the gas stop Poweramp was running and the wheel was still dead.
10. **The unit doesn't restore the last on-screen app.** Poweramp comes back after short stops (5, 15 and 30 min) but not after the 2-day sit, and not after it had been swiped away. Why the cleanup treats Poweramp differently from SWC Bridge is unresolved (§4).
11. **The volume override is a separate bug.** `safe-volume ACTIVE (limiting)` caps `STREAM_MUSIC` at 10/15, and `setStreamVolume` above that is ignored without an error unless the user confirms a dialog (Show volume dialog = off). It failed every run from 20 Sep, including the successful self-start. **At the v0.8 install (~15:04, 26 Sep) the status read 15/15 with the limiter still ACTIVE.** Why it reached 15 is unknown: the confirmation dialog may have been accepted, or the remote may have been used.

---

## 3b. What the 26 Sep evening tests established

12. **`excludeFromRecents` is not enough.** With the app hidden from recents, it was still force-stopped at switch-off. So the cleanup doesn't just clear the visible recents list. It may include hidden tasks, or it may work from running apps.
13. **The kill happens at the moment of switch-off, before Android shuts down.** No `ACTION_SHUTDOWN` and no `screen OFF` were ever logged. The last heartbeat was within seconds of the last wheel press before the car was turned off.
14. **The unit spares the app that is playing audio.** Poweramp playing at switch-off: not stopped, and it restarts itself. Poweramp paused: force-stopped, and it doesn't restart. SWC Bridge's own MediaSession claims "playing" (Input 2 and re-assert are on), yet it is always killed. So what counts is real audio playback or audio focus, not a media session's reported state.
15. **The one success is the only start where SWC Bridge's service was not running at switch-off.** That run began at 11:56:21 with a bare process start, and the service never came up. So the working hypothesis is that the unit force-stops **running** third-party apps except the playing one. An app that isn't running at switch-off is left alone and receives its boot signals normally.
16. **Clock oddity:** the halftime run was stamped "16:41:59" at uptime 2:46, yet its last heartbeat, at uptime 9:17, was stamped "15:48:29". The clock seems to have been set about an hour ahead first and then corrected. It doesn't affect the diagnosis. Trust uptime.
17. **Volume is inconsistent:** 15/15 held across one reboot (halftime) and was back to 10/15 on the next.

## 4. Theories: current status

| Theory | Status |
|---|---|
| **SWC Bridge is force-stopped at some shutdowns** | **Confirmed.** The Force stop button was greyed out on failing starts. |
| **The shutdown cleanup works by clearing the visible recent-apps list** | **Ruled out as the whole story** by v0.8: hidden from recents, the app was still killed. Swiping still force-stops, so it's one mechanism the unit has, but not the one at switch-off. |
| **At switch-off, the unit force-stops running third-party apps except the one playing audio** | **Current best fit.** The playing-app exemption is shown directly (playing → spared, paused → stopped). The "running" part rests on the single success. v0.9 lean mode is the test. |
| No boot broadcast is delivered | **Ruled out.** `BOOT_COMPLETED`, `MEDIA_MOUNTED` and `USER_PRESENT` all arrived. |
| The vendor only sends wheel keys when an audio source is active | **Ruled out as the cause.** It can't explain the system broadcasts going missing on the same boots. |

**Still unresolved:**

- **Why Poweramp usually survives.** Once swiped away, it didn't come back, so its usual comeback depends on it *not* being cleared. Either the cleanup spares it (perhaps as the active audio app, or because it holds a media session or notification) and it restarts itself, or the unit revives it from its recents entry. After the 2-day sit it was stopped too.
- **The 11:56:21 process start** that logged no reason. v0.8 logs the start reason on every process, so this can't recur unexplained.
- **The "10:14–10:29" lines** that appear after the "10:48" run on the morning of 26 Sep.

---

## 5. v0.8: what changed

It was built and installed on 26 Sep, and the source was saved back to the project folder.

**The fix**

- **`android:excludeFromRecents="true"` on `MainActivity`.** The app never appears in recent apps, so the shutdown cleanup has nothing to clear.

**Other improvements**

- **Poweramp commands carry `FLAG_INCLUDE_STOPPED_PACKAGES`.** Android drops even explicit broadcasts to a stopped app without this flag. Now a wheel press can reach, and start, a force-stopped Poweramp (the 2-day-sit case).
- **Next/previous swap applies to the steering wheel only** (virtual keys, `virt=true`). v0.7 also reversed the USB remote. Wheel behaviour is unchanged.
- **Watchdog alarm is non-wakeup.** v0.7 used `ELAPSED_REALTIME_WAKEUP`, which woke the unit every 15 min while parked, against the "must power down" constraint.
- **Earlier startup signals:** `TIME_SET` and `TIMEZONE_CHANGED`. The clock is set ~10 s after boot, well before `MEDIA_MOUNTED` at ~40 s. They're only logged when the bridge isn't already running.
- **A wheel press that wakes the app now also starts the service**, so the startup volume, heartbeat and watchdog run too.
- **Stored setting indexes are range-checked**, so a bad value can't crash the diagnostics header.

**Requested features**

- **Clear really clears.** It renames `history.log` to `history-YYYY-MM-DD_HHmmss.log`, deletes the original, and empties the screen. The newest 20 archives are kept, and the new log starts with a "log cleared -- previous log saved as …" line.
- **Version on screen**, in the title bar and the status line: "SWC Bridge v0.8 (build 8)", plus uptime.

**New log lines**

| Line | Meaning |
|---|---|
| `=== process started === v0.8 (build 8)  uptime 0:00:40  boot #N` | Uptime is time since the unit booted; trust it over the clock |
| `process start reason: …` | What created the process: `startup signal …`, `media button broadcast`, `watchdog alarm`, `system restarted the service …`, or `user opened the app`. The last one carries a note when the log points to a force-stop, or `unknown -- no component of ours ran within 5 s`. |
| `previous run: last seen alive … -- unit has REBOOTED` / `SAME boot` | From a 30-second heartbeat the service leaves behind |
| `previous run: shutdown signal WAS received` / `NO shutdown signal reached the app before power-off` | Whether Android's `ACTION_SHUTDOWN` reached the app, i.e. whether the kill comes before Android shuts down |
| `screen OFF` / `screen on` / `power disconnected` (with uptime) | The switch-off sequence as the running app sees it. If the log stops right after `screen OFF`, that's when it was killed. |
| `poweramp package: STOPPED` / `not stopped` | Whether Poweramp was force-stopped too |
| `startup signal: … (uptime …)` | Now includes uptime |

**Build notes**

- The code was compile-checked here against stand-in Android classes, since the real SDK couldn't be downloaded.
- The first build in Android Studio failed on `The string "--" is not permitted within comments` in `AndroidManifest.xml`. The v0.8 manifest comments used `--` as a dash. They were changed to single dashes and the build then succeeded.
- The v0.7 source was backed up (`swcbridge-v0.7-source-backup.zip`, in the chat).
- The README has a v0.8 section.

---

## 6. Earlier assumptions that turned out wrong

- *"It happens after several days" / "after ~3 hours" / "after every ignition cycle."* All wrong. It depends on the app's state at shutdown.
- *"The ROM may not deliver `BOOT_COMPLETED`."* Wrong.
- *"Both symptoms fail together, so nothing of ours runs."* Wrong: the volume failure is independent.
- *"Daily driving: everything keeps working"* (handoff doc). Contradicted by the gas stop.
- *"The MCU cuts power after a few days."* The unit reboots even after 5 minutes.
- *"The clock jumped back about 7 h 20 min."* Misread: it was the clock going from 1969 to the real time.
- *"The clock was right at startup, so there was no reboot."* Withdrawn.
- *"Poweramp is force-stopped at every shutdown and then revived."* Contradicted by the leave-on-screen test.
- *"Opening the app might not fix it"* (duplicate photo, 23 Sep). Resolved: the same photo was attached twice.
- *"Input 2 (own MediaSession) is off"* (handoff doc). The v0.8 screen shows it **on**, along with re-assert.

---

## 7. Another AI's proposed fix: rejected

It proposed adding `BootReceiver` and a foreground `BridgeService` with `START_STICKY`, and verifying by restarting from the menu.

- **Both were already in v0.7**, and they work. They just can't run while the app is stopped.
- **Its root cause is wrong.** Android doesn't stop apps for not being used; only a never-opened install or a force-stop does.
- **It's circular.** A stopped app never receives `BOOT_COMPLETED`.
- **Its test can't fail.** A restart from the menu already worked on v0.7.

---

## 8. Head-unit screens reviewed

### Factory settings (after entering 666666)

It's a grid of tiles:

- File manager
- LCD test
- Main Volume Set
- GPS test
- Type selection
- Android settings
- SDCard reset

The left column was cut off in the photo (two labels ending in "…ion" and "…n") and still needs photographing.

- **Main Volume Set** (the vendor "FactoryTest" app) has three sliders: Bluetooth Telephone 30, FL-speaker 30, Media 30, all at or near maximum. These are vendor/MCU output levels, separate from Android's 0–15 media volume, so **they're not the cause of the 10/15 cap. Leave them.**
- **Type selection** is probably the car-model / CAN type. **Don't change it.**

### Android settings

This is stock Android 7:

- **Wireless & networks:**
  - Wi-Fi (connected)
  - SIM cards
  - Data usage (0 B)
  - More: Airplane mode, Tethering & portable hotspot, VPN, and Cellular networks (greyed out)
- **Device:**
  - Apps (39 installed)
  - Storage (16.86 of 52.44 GB)
  - Memory (average 1.7 of 4.0 GB)
- **Personal:**
  - Location (on, high accuracy)
  - Accounts
  - Google
  - Languages & input (English US)
  - Backup & reset (backup disabled)
- **System:**
  - Date & time (GMT−05:00 Central Daylight Time)
  - The rest of the list isn't photographed

**About → Status** shows an IP address and Wi-Fi MAC, "Bluetooth address: Unavailable", a placeholder serial `0123456789ABCDEF`, and **Up time 0:03:39**.

**No settings for sleep, ACC-off, autostart, background-app cleanup or a navigation app were found.**

### Developer options (unlocked 3 Oct 2026)

Unlocked by tapping Build number 7 times. **Nothing has been changed here.** The master switch reads **On** because unlocking turns it on. Most photos cut off the right edge, so a toggle's state is only listed where it was visible. "—" means it wasn't visible, or the item has no toggle.

| Section | Setting | Shown value / state |
|---|---|---|
| (top) | Developer options | **On** |
| | Take bug report | — |
| | Desktop backup password | "Desktop full backups aren't currently protected" |
| | Stay awake | Off |
| | Enable Bluetooth HCI snoop log | Off |
| | OEM unlocking | Off |
| | Running services | — (lists running apps/services) |
| | WebView implementation | Android WebView |
| | Multiprocess WebView | Off |
| | Automatic system updates | **On** |
| | Demo mode | — |
| Debugging | **USB debugging** ("Debug mode when USB is connected") | toggle not visible; **probably off**, since the two items below are greyed out |
| | Revoke USB debugging authorizations | — |
| | Bug report shortcut | — |
| | Select mock location app | No mock location app set |
| | Enable view attribute inspection | Off |
| | Select debug app | No debug application set |
| | Wait for debugger | Off (greyed out) |
| | Verify apps over USB | greyed out |
| | Logger buffer sizes | 256K per log buffer |
| Networking | Wireless display certification | — |
| | Enable Wi-Fi Verbose Logging | — |
| | Aggressive Wi-Fi to Cellular handover | — |
| | Always allow Wi-Fi Roam Scans | — |
| | Cellular data always active | — |
| | Select USB Configuration | MTP (Media Transfer Protocol) |
| | Disable absolute volume | — (Bluetooth only) |
| Input | Show taps | — |
| | Pointer location | — |
| Drawing | Show surface updates | — |
| | Show layout bounds | — |
| | Force RTL layout direction | — |
| | Window / Transition / Animator duration scale | 1x / 1x / 1x |
| | Simulate secondary displays | None |
| | Smallest width | ~480 dp (photo blurry) |
| Hardware accelerated rendering | Force GPU rendering | — |
| | Show GPU view updates | — |
| | Show hardware layers updates | — |
| | Debug GPU overdraw | Off |
| | Debug non-rectangular clip operations | Off |
| | Force 4x MSAA | — |
| | Disable HW overlays | — |
| | Simulate color space | Disabled |
| Media | Disable USB audio routing | — |
| Monitoring | Strict mode enabled | — |
| | Show CPU usage | — |
| | Profile GPU rendering | Off |
| Apps | Don't keep activities | — (should be off; not visible) |
| | Background process limit | **Standard limit** |
| | Show all ANRs | — |
| | Inactive apps | — (opens a list) |
| | Force allow apps on external | — |
| | Force activities to be resizable | — |

**What matters for this project:**

- **There's no "ADB over network" or "Wireless debugging" item.** That's normal for Android 7, which only has **USB debugging**. Two ways to reach adb:
  1. **Try Wi-Fi anyway.** Some head-unit ROMs listen for adb on the network once USB debugging is on. Test: turn on USB debugging, then run `adb connect 192.168.50.87:5555` from the laptop on home Wi-Fi.
  2. **Use a USB cable.** The unit's USB ports read USB sticks ("host" mode). A laptop needs the unit in "device" mode. Many of these units can switch modes in the factory menu, or have a dedicated port; one of the uncaptured factory-menu tiles might be it. "Select USB Configuration: MTP" suggests the unit has device-mode support somewhere.
- **Background process limit = Standard** and **Don't keep activities** (presumably off) are both fine. Neither explains the force-stops (those come from the vendor's switch-off cleanup, §3b).
- **Running services** shows what's running right now. It's a way to check lean mode without the log: SWC Bridge should be **absent** a few seconds after a wheel press.
- **Inactive apps** (App Standby) doesn't block broadcasts on Android 7, so it's not a factor. If you look, SWC Bridge and Poweramp should both read "Active".
- **Not relevant to the 10/15 volume cap:**
  - Disable absolute volume (Bluetooth only)
  - Disable USB audio routing (USB audio only; the car uses AUX)
  - Stay awake
- **Leave alone:** OEM unlocking, Stay awake (it would affect power-down), Automatic system updates.

### App info → Force stop

| When | SWC Bridge | Poweramp |
|---|---|---|
| Successful start (12:55) | Active (running) | Active (running) |
| Failing start (13:37) | **Greyed out = stopped** | Active |
| After the leave-on-screen test (14:08) | **Greyed out** | **Greyed out** (it had been swiped away) |

---

## 9. Procedures and tools

- **Log file:** `Android/data/com.mattc.swcbridge/files/history.log`, reachable from the file manager.
  - **v0.8 Clear** archives the log to `history-YYYY-MM-DD_HHmmss.log` in the same folder, then deletes it. (In v0.7, Clear only emptied the screen.)
  - `SAVE` writes a timestamped `swcbridge-*.txt` copy.
- **Finding the current run:** look for `---------- earlier runs above, this run below ----------`. If nothing is below it, scroll up.
- **Reading a run:**
  - **It started itself** if a `startup signal:` or `[broadcast]` line appears before `--- config screen opened ---`. In v0.8, `process start reason:` says it directly.
  - The wheel shows `src=0x0 virt=true`. The remote shows `src=0x301 devId=5`.
  - Trust `uptime` over clock times. A run stamped 18:0x is minutes after a fresh boot.
- **Force stop check (no adb needed):** go to Settings → Apps → *app* and **look** at Force stop; **don't tap it.** Greyed out = stopped state. It only means something on a start where the wheel isn't working, checked before opening SWC Bridge.
- **Never swipe SWC Bridge (or Poweramp) out of recent apps.** It force-stops them. With v0.8, SWC Bridge shouldn't appear there at all.
- **The "Cold Boot Test" card** (published page from 20 Sep) is out of date.

---

## 10. Next steps as of 26 Sep *(all done or superseded; see Final status at the top)*

1. **The v0.8 test (at halftime).** Start the car, and **press the wheel's next button before opening anything**. Note whether the track changes. Then open SWC Bridge and photograph the log.
   - **Success:** a `startup signal:` line above `--- config screen opened ---`, and `process start reason: startup signal …`.
   - **Failure:** `process start reason: user opened the app` with a force-stop note. The "previous run" and "shutdown signal" lines will then show when it was killed.
   - **Optional:** open the recent-apps screen and confirm SWC Bridge **isn't listed**. Look only; don't swipe.
2. **If it works: repeat it over a few days,** including short stops, a longer sit, and a drive where the SWC Bridge screen was opened. One success isn't proof, given how intermittent this has been.
3. **Volume:** check whether it's still 15/15 after the restart, and work out how it got there. That may point at the fix for the safe-volume limit.
4. *(Later)* Photograph the cut-off left column of the factory menu.

---

## 14. How fast can the wheel work after a start? (7 Oct 2026, boot #1188)

**Test:** NEXT pressed every ~2 s from the moment Poweramp appeared. The track changed on the 8th press (~16 s). Matt finds that acceptable.

**Log (boot at ~09:05:36; app opened 09:06:49.6 = uptime 1:13):**

- **SWC Bridge started at ~0:00:36 uptime.** The startup ramp's 4/4 attempt ran at 09:06:42.9, 30 s after the ramp began.
- **The wheel presses arrived in a BURST at ~0:00:54 uptime, not one per 2 s:**
  - `[broadcast]` lines at 30.903, 30.997, 31.169, 31.278, 31.469 and 31.912. That's six presses in about a second.
  - Three were debounced (96, 267 and 375 ms apart). Three went through as NEXT, so the burst skipped several tracks at once.
- **So presses during boot are mostly not lost.** They're queued outside SWC Bridge, by Android's broadcast queue during boot or by the vendor sender, then delivered together. SWC Bridge was already running 18 s before they arrived, so **the delay isn't SWC Bridge.** There's no app-side way to make the first press work sooner.
- **The volume top-up was REFUSED on all three of those NEXTs:** `12/15 -> 12/15 REFUSED … safe:ACTIVE (limiting) music active:false`. The startup ramp was refused too: `12 -> 12`, nothing moved. Notes:
  - Android was at **12/15** after this boot, not 10.
  - **"music active:false"**: Poweramp wasn't playing yet at those moments.
  - The earlier success (4 Oct) was mid-drive with music playing. That points to "music must be playing" as the condition, rather than "Poweramp on screen".
  - **Confirmed: once the track changed with music playing, the volume went to 100% by itself.** So the first-press refusal is harmless.
- **Possible future tweaks (not done):**
  - If the top-up is refused while music isn't active, retry it a few seconds later.
  - Collapse a burst of queued presses into one, so a backlog doesn't skip several tracks.

## 13. Volume: v0.9.2 top-up on NEXT (4 Oct 2026)

- **Matt sets the real loudness with the car amp** and never uses Android's volume, so Android's media volume should just always be at 15/15.
- **Matt's observation:** Android's volume only seems to change while an audio app is on screen. With SWC Bridge on screen, even the USB remote's volume buttons do nothing. The startup ramp runs while the screen shows the launcher or SWC Bridge, which may be why it never gets past 10/15. That's a separate theory from the safe-volume limiter (§3.11).
- **v0.9.2:** every NEXT press (wheel or remote) also checks the media volume and raises it if it's below the target: volume-up steps, then stream steps, then a direct set. Switch: "Top up Android volume on every NEXT press" (on by default).
- **What the log will show:** one line per NEXT, `volume [NEXT] a/15 -> b/15` with OK / PARTLY raised / REFUSED, plus the safe-volume state and "music active" when it's refused. That settles whether being in Poweramp is enough, or whether the limiter still blocks it. If the limiter blocks it, the next step is the adb grant (option B).
- **RESULT (4 Oct): it works.** NEXT raised Android's volume to 100% with **no dialog**. Matt turned it down with the USB remote, and the next NEXT put it straight back to 100%. So the safe-volume limiter wasn't what blocked the startup ramp. Android just wouldn't change the volume while no audio app was on screen. **adb (option B) is no longer needed.** Still to confirm: the first NEXT after a fresh start (the limiter re-arms at boot).

## 12b. v0.9.1: FIRST SUCCESSFUL SELF-START (3 Oct 2026)

**Part 1, the driveway Home test, passed (15:39–15:40).**

- Leaving the screen with `finish()` only: `bridge service stopped`, then `process exit (lean mode, idle)`. **No force-stop.**
- **A wheel press starts the app when it isn't running.** `=== process started: media button broadcast === … cold start 53 ms` (then 30 ms, 33 ms), the press handled, `process exit` about 6.5 s later. Three cycles in a row.
- This settles the last open question: the vendor sends wheel presses even when SWC Bridge isn't running, and Android starts it on demand. The added delay is 30–53 ms.

**Part 2, the real car-off test, passed (10 min off, Poweramp playing at switch-off).**

- On restart, Poweramp auto-started. The wheel did nothing for the first ~30 s, then **worked without SWC Bridge being opened.**
- **Log (boot #1177):**
  - The startup volume ramp ran on its own (`volume [startup 4/4]` at 15:57:54, uptime ~0:01:05). So a startup signal started the service at about 0:00:35 uptime, with no user action.
  - `[broadcast]` wheel lines at 15:57:52 and 15:57:55.
  - `bridge service stopping (lean mode: startup done, screen closed)` → `process exit`.
  - Then `process started: media button broadcast … uptime 0:01:12 boot #1177 cold start 29 ms`, a working double-press (`TOGGLE_PLAY_PAUSE` ×2), NEXT, and `process exit` again.
  - The user only opened the app at 15:58:23, after all of that.
- **The app was NOT force-stopped at switch-off.** This is the first self-start since the 26 Sep 12:51 accident, and the first one by design.
- **The ~30 s dead time after starting the car** looks like the unit's own boot: no third-party app has ever started earlier than ~35–40 s after boot (MEDIA_MOUNTED at ~40 s on 26 Sep). Android doesn't let ordinary apps receive broadcasts until it finishes booting. The first `process started` line of the boot (scrolled off in the photo) will confirm which signal and uptime.
- **The volume ramp still stops at 10/15** (safe-volume limiter). That's a separate issue.

**Second car-off test passed (3 Oct evening, ~3 h off, Poweramp playing at switch-off, screen on Poweramp).**

- Boot #1178. Poweramp auto-started. Matt waited ~45 s and pressed NEXT 3 times: **all 3 worked.**
- **Log:**
  - The service was already running before `BOOT_COMPLETED`, so it was started by an earlier signal (MEDIA_MOUNTED or TIME_SET) at about 0:00:36 uptime.
  - `18:05:09.577 startup signal: BOOT_COMPLETED (uptime 0:00:55) -- bridge already running`.
  - `18:05:11.026 startup signal: USER_PRESENT (uptime 0:00:57)`.
  - Volume ramp 4/4 at 18:05:20 (refused at 10/15).
  - `18:05:21 bridge service stopping` → `stopped` → `18:05:23 process exit`.
  - Then `process started: media button broadcast … uptime 0:01:34 … cold start 33 ms`, NEXT; `process exit`; another cold start at 0:01:43 (33 ms), two NEXTs; `process exit`.
  - The user opened the app at 0:02:04, after all of that.
- **Two out of two car-off restarts now work** (10 min and ~3 h).
- **The first app start each boot is at ~35–40 s uptime.** Wheel presses before that are lost, because the unit is still booting.

**Third car-off test passed: overnight sit (3 Oct evening → 4 Oct morning).**

- Started the car in the morning. **The wheel worked within ~30 s, without SWC Bridge being opened.** No log photo was taken.
- **Three out of three car-off restarts now work:** 10 min, ~3 h, and overnight.
- **The cold-start problem is considered solved.**

**Fourth car-off test: Poweramp PAUSED at switch-off. FAILED (5 Oct, v0.9.2).**

- Poweramp had been paused before switch-off. On restart **Poweramp did not auto-start**, which fits the theory that the unit only restores the app that was playing.
- Matt opened Poweramp by hand and waited ~1 minute. **The wheel did not work.**
- **Opening SWC Bridge made the wheel work immediately.** That fits a force-stop: opening an app by hand is what clears Android's stopped state.
- Not yet known: whether SWC Bridge was force-stopped (log verdict line / previous-boot line still to be photographed). The leading theory: with nothing playing, the switch-off cleanup spares no app, and the hidden task v0.9.1 keeps may count as "running". Alternatively, a wheel press or the SWC Bridge screen just before switch-off left the process alive.
- Matt's view: not a big deal, since if Poweramp has to be opened by hand anyway, SWC Bridge can be opened too.

**This confirms the model:** the unit force-stops **running** apps at switch-off (except the one playing audio). An app that isn't running survives, and its boot receivers and wheel receiver work normally.

**Still to do:**

- ~~Long sit~~ (passed overnight, 4 Oct). Poweramp **paused** at switch-off: failed once (5 Oct, cause not yet known).
- Known edge cases:
  - a wheel press within ~5 s of switch-off (the process is still alive)
  - switching off with the SWC Bridge screen open
- Optional "Poweramp + Wheel" launcher icon (v0.9.2) as a one-tap recovery if an edge case ever hits.

## 12a. v0.9 driveway test: FAILED, and why (26 Sep, ~18:28)

- **Test:** installed v0.9, opened it, pressed Home, waited 10 s, pressed the wheel. **Nothing happened.** Opening the app made the wheel work again; pressing Home killed it again (repeated twice).
- **Force stop was greyed out** after pressing Home, so the app was force-stopped.
- **Log:** `18:28:05.024 bridge service stopping (lean mode: startup done, screen closed)` → `18:28:05.027 config screen closed (lean mode)` → next line `18:28:51.695 === process started: user opened the app ===`. Neither `bridge service stopped` nor `process exit` was ever logged, so the process was killed within milliseconds of `finishAndRemoveTask()`.
- **Finding:** **on this unit, removing an app's task in any way force-stops the app**, whether it's a user swipe or the app removing its own task. This suggests the switch-off cleanup may be "remove every task except the playing app's".
- **Still untested:** whether a wheel press can start the app when it's merely not running (as opposed to stopped). Every `[broadcast]` line ever logged arrived while the process was already running.
- **v0.9.1:** leaving the screen only calls `finish()` (the hidden task is kept), plus a test switch to turn off the self-exit. Same driveway test next.

## 12. v0.9 "lean mode" (built 26 Sep; see 12a for the driveway result)

> **Built as described below.** Source saved to the project. New `Lean.java`; `RunHistory` now does first-run-this-boot detection and runs the startup volume once per boot. The shutdown receiver is unregistered. A "Keep bridge running" switch (off by default) restores the v0.8 behaviour.
>
> **Test, step 1 (driveway):** install, open once, press Home, wait 10 s, press the wheel. It must still work, and the log should show `process started: media button broadcast`.
>
> **Test, step 2 (the real one):** switch off with no wheel press in the last ~5 s and the SWC Bridge screen closed. Wait 5+ min, restart, and press the wheel before opening anything. Look for `first run this boot, started by the system -> the app was NOT force-stopped`.

The idea: **don't be a running app when the car is switched off.**

- **The wheel doesn't need a running app.** Its `MEDIA_BUTTON` receiver is declared in the manifest, so Android starts the process on demand for each press, as long as the app isn't force-stopped.
- **At startup:** the service does its work (startup volume), then **stops itself** and the process exits.
- **Leaving the config screen** fully closes it: it finishes and removes its hidden task.
- **After each wheel press**, the process exits a few seconds later. That's long enough for the 350 ms double-press window.
- **The watchdog alarm and heartbeat are disabled** in this mode, since they exist to keep the app running.
- **A "Keep bridge running (v0.8 behaviour)" switch** allows A/B testing in the car without rebuilding.
- **Trade-offs:**
  - Input 2 (the app's own MediaSession) only works while the app is running. It isn't needed: the wheel uses the broadcast path, and the remote works through Poweramp directly.
  - Pressing the wheel within a few seconds of switching off, or leaving the config screen open at switch-off, would still get the app killed.
  - Per-launch logging would be trimmed, so each wheel press doesn't add a block of startup lines.

## 11. If v0.8 doesn't fix it *(it didn't; see §3b and §12)*

- **The cleanup may not be recents-based**, or it may include hidden (excluded) tasks. The v0.8 logs (`previous run`, `shutdown signal`, `screen OFF`) will show when the app dies.
- **The next lead is whatever spares Poweramp.** Candidates:
  - it being the active audio app
  - holding an active media session
  - its foreground notification
  - a vendor allow-list
- **Fallback, whichever it turns out to be:** a one-tap home-screen shortcut that opens SWC Bridge and then Poweramp.
- **Volume (separate problem):**
  - accept the safe-volume confirmation, or
  - raise volume through a route the limiter doesn't block. The USB remote can raise volume, so volume key events are a candidate.
