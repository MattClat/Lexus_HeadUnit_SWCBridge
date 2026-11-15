# Steering Wheel Controls → Poweramp
### Project plan — Lexus IS / 4x4shop MRW_LEXUS head unit
*Rev 3 — diagnosis resolved; no-ADB deployment*

---

## 0. The answer

**The head unit sends `ACTION_DOWN` and never sends the matching `ACTION_UP`.**

You diagnosed this yourself in November 2020 —
[forum.powerampapp.com thread 20241](https://forum.powerampapp.com/topic/20241-steering-wheel-controls/),
posted as *soljarag*. Your own readout:

```
17:51:49.103 PSMediaButtonReceiver android.intent.action.MEDIA_BUTTON KEYCODE_MEDIA_PREVIOUS ACTION_DOWN
name=Virtual source=0x0 sources=0x301 virt=true repeat=0 actionTime=307135 playerState=1

17:51:50.927 PSMediaButtonReceiver android.intent.action.MEDIA_BUTTON KEYCODE_MEDIA_NEXT ACTION_DOWN
name=Virtual source=0x0 sources=0x301 virt=true repeat=0 actionTime=308958 playerState=1
```

Poweramp moderator andrewilley:

> "Your device appears to be only sending KEYCODE ACTION_DOWN events (i.e. telling PA that a button
> has been pressed) but it does not seem to be correctly issuing a follow-up ACTION_UP event which
> tells PA that the button has been released, which allows the activity to be processed."

Poweramp waits for the release to decide between single / double / triple / long press. From its
point of view your wheel button is being **held down forever**, so the press never completes. It
isn't ignoring the button — it's still waiting for you to let go, six years later.

### What that readout tells us, line by line

| Field | Meaning |
|---|---|
| `PSMediaButtonReceiver` | Poweramp's exported `MEDIA_BUTTON` receiver — confirmed present in your build-1025 APK at intent-filter priority 999 |
| `name=Virtual`, `virt=true`, `sources=0x301` | An **injected virtual key event** (`SOURCE_KEYBOARD｜SOURCE_DPAD`), i.e. the vendor MainUI process calling `InputManager.injectInputEvent` — not a physical input device |
| `KEYCODE_MEDIA_NEXT / _PREVIOUS` | The unit emits the **correct standard keycodes**. Nothing needs remapping |
| `repeat=0` | Not an auto-repeat artifact — one clean event per press |
| `ACTION_DOWN` (never `ACTION_UP`) | **The entire bug** |
| `playerState=1` | Poweramp was playing and receiving. It was never deaf |

**This is very good news.** It eliminates most of what Rev 1 of this plan was worried about: the
MCU serial protocol, the LY CAN decoder, hunting for vendor broadcast action strings, decompiling
the launcher, `getevent`, keylayout files, and root. None of that is needed. The key reaches
Android globally, with the right keycode, and lands on Poweramp. It's just malformed.

---

## 1. Things that will NOT fix it

Ruling these out saves you the weekend:

| Non-fix | Why |
|---|---|
| A Poweramp setting | No "act on press" / "don't wait for release" option exists — not in 2020, not in build 1025. Changelogs 800–1025 show nothing of the kind. Build 981 added *Ignore Bluetooth Commands*, which goes the opposite direction |
| Setting Long/Double/Triple press to "None" | The multi-press timeout starts *after* the release. With no release, Poweramp never gets that far. **Worth 30 seconds to test anyway** — cheap, and I'd rather be wrong |
| A Poweramp plugin | Poweramp's plugin points are skins, lyrics providers, track (SAF) providers, vis presets. None touch input |
| Disabling the stock music app | The key is already reaching Poweramp. There's no competitor to remove |
| `.kl` keylayout remapping / root | The event is injected virtually — there is no input device and no keylayout in the path |
| Key Mapper / Button Mapper | Current builds need Android 8.0+. And they classify presses by release timing too — they'd hang exactly like Poweramp does |
| Media Button Router (Harleensahni) | Abandoned 2014, built on the pre-Lollipop ordered-broadcast model. Architecturally obsolete |
| Fixing it upstream | `LEXUS_V5.0_0321` MCU firmware, from a vendor with no update channel. Not happening |

**The one thing worth trying before writing any code:** re-run the **SWC key study / learn**
routine in Factory Settings (PIN `666666`). It's a long shot, but if the MCU's key table can be
re-taught, there's an outside chance it emits a proper press/release pair afterward. Note the
current CAN protocol before you change anything.

---

## 2. The fix: a small bridge app

One background service. It catches the malformed `ACTION_DOWN` and turns it into something
Poweramp can act on. Two designs, both non-root:

### Design A — command Poweramp directly *(recommended)*

```
wheel button → head unit injects MEDIA_NEXT ACTION_DOWN
             → bridge sees the DOWN
             → bridge sendBroadcast:
                  component  com.maxmpz.audioplayer/.player.PowerampAPIReceiver
                  action     com.maxmpz.audioplayer.API_COMMAND
                  extra      "cmd" = 4 (NEXT) / 5 (PREVIOUS) / 1 (TOGGLE_PLAY_PAUSE)
```

Leaves the key-event system entirely. The ACTION_UP question stops mattering because no key event
is involved on the output side. Verified against your APK: `PowerampAPIReceiver` is
`exported="true"` with **no `android:permission`**, so any app can do this.

### Design B — synthesise the missing ACTION_UP

```
bridge sees the DOWN → AudioManager.dispatchMediaKeyEvent(new KeyEvent(ACTION_UP, KEYCODE_MEDIA_NEXT))
```

`dispatchMediaKeyEvent` is public API since 19 and needs no permission. Elegant, because Poweramp
then processes a normal complete press and all its own settings keep working. **Risk:** if the
bridge holds the front media session, the synthetic UP routes straight back to itself. Design A
has no such failure mode.

Ship A. Keep B as a toggle.

### The one open question: how does the bridge *see* the DOWN?

Your log line says `PSMediaButtonReceiver` + `android.intent.action.MEDIA_BUTTON`, but that's
ambiguous — `MediaSessionCompat` wraps session-delivered media buttons in an
`ACTION_MEDIA_BUTTON` intent too, so the label alone doesn't distinguish:

1. **Ordered `MEDIA_BUTTON` broadcast** (the vendor app calling `sendOrderedBroadcast` directly, as
   MTK/TS firmware is known to do). → a manifest `<receiver>` at priority > 999 catches it.
   **~40 lines of code.**
2. **MediaSession routing** via `MediaSessionService`. → the bridge must hold its own
   `MediaSession` with `FLAG_HANDLES_MEDIA_BUTTONS` and stay at priority index 0. Workable but
   fragile: on API 24, `STATE_SKIPPING_TO_NEXT` is a priority state, so Poweramp re-takes the front
   of the stack on *every* track change and the bridge must re-assert.

**Resolution: don't choose. Implement both inputs at once** — it's barely more code than either
alone, and v0.1 logs which one actually fired. That single install answers the question on real
hardware, which is worth more than any amount of further reading.

Third input, as backstop: `READ_LOGS` (grantable with `adb shell pm grant`) tailing logcat. Proven
pattern — `alt236/KeyEvent-Display` does exactly this for head-unit porting work. Only useful if
something actually logs the key; unknown until we look.

### Implementation notes for API 24

- `sendBroadcast`, not `startForegroundService` — build 1025 is well past 855, and API 24 has no
  background-execution limits.
- Debounce next/prev: Poweramp throttles those internally to ~200 ms.
- Foreground service, `START_STICKY`, auto-start on `BOOT_COMPLETED`.
- Gate on the sticky `com.maxmpz.audioplayer.STATUS_CHANGED` broadcast so the bridge doesn't hijack
  the wheel while you're on radio or CarPlay.
- `compileSdk 34`, `minSdk 21`, **`targetSdk 24`** — deliberately low, to keep Android 7 semantics
  and avoid newer-API guard code. Nothing is going to the Play Store.
- Near-zero dependencies so it installs cleanly on a 2020 MTK unit.

---

## 3. Deployment constraint: no reliable ADB

The head unit is in the car; the laptop is not. **ADB is a convenience here, not a dependency.**
Every step has a no-ADB substitute:

| Needed ADB for | Substitute |
|---|---|
| Confirm the ACTION_DOWN diagnosis | Poweramp → Settings → Headset/Bluetooth → **Last Processed Commands**, on the unit |
| `input keyevent 87` — does a well-formed key work? | **Test button in the bridge app** |
| `am broadcast … --ei cmd 4` — does the Poweramp API work? | **Test button in the bridge app** |
| `adb install -r` | APK on a **USB stick**, tap it in the unit's file manager (Settings → Security → *Unknown sources*) |
| `logcat` from the sniffer build | App keeps its own **on-screen event log** + "dump to USB" button |
| `pm grant READ_LOGS` | Only needed for the third-choice fallback input. Skip unless we get there |

### The design consequence — this is the important part

The real cost isn't ADB, it's the **iteration loop**: build → walk to car → USB stick → install →
test → walk back. So don't iterate on code. **Ship one app that is configurable on the head unit's
touchscreen.**

A single APK containing:

- **All input paths active at once** — manifest `MEDIA_BUTTON` receiver at max priority, *and* its
  own `MediaSession` with `FLAG_HANDLES_MEDIA_BUTTONS`
- **All output paths** — Poweramp API broadcast (A), synthetic `ACTION_UP` (B), generic
  `dispatchMediaKeyEvent` (C)
- **Runtime toggles** for which input and output to use, persisted in `SharedPreferences`
- **Live scrolling event log** — every key event seen, which path caught it, what was sent
- **Test buttons** — "Send Poweramp NEXT", "Inject MEDIA_NEXT", "Dispatch to session", replacing
  the adb commands entirely
- **Export log to USB / internal storage**

Then tuning happens from the driver's seat. One trip instead of ten.

### Test the output side at your desk

Create an **API 24 emulator** in Android Studio and install your `Poweramp-build-1025-uni.apk`
into it. The whole output half — API broadcast, command mapping, debounce, status-broadcast gating
— is verifiable on the laptop. Only the *input* half needs the car, because only the car has the
broken MCU.

### Optional, if convenient

Worth 5 minutes each next time you're near the vehicle, free if they work:

- See whether the unit joins your home Wi-Fi in the driveway. Many of these ship with `adbd`
  already listening on TCP 5555, so `adb connect <ip>:5555` from indoors may just work.
- Check Factory Settings (PIN `666666`) for an ADB/debug toggle.

---

## 4. Revised phases

### Phase 1 — Confirm on-screen *(no laptop, ~5 minutes in the car)*

1. Poweramp → **Settings → Headset/Bluetooth → Last Processed Commands**. Press seek-up. Confirm
   `ACTION_DOWN` still appears with no matching `ACTION_UP`.
2. Set Long / Double / Triple press mappings to **None** and retest. Long shot; free.
3. Factory Settings (PIN `666666`) — try the **SWC key study / learn** routine. Long shot; free.
   Note the current CAN protocol before changing anything.

### Phase 2 — Build the app *(at your desk)*

Single configurable APK per the design above. Verify the output half against an API 24 emulator
with Poweramp installed.

### Phase 3 — Install and tune *(one trip to the car)*

USB stick → install → open the app → press each wheel button → read the on-screen log to see which
input path fired → set the toggles accordingly → confirm next/prev work.

### Phase 4 — Verify

- Wheel next/prev drives Poweramp on AUX, from cold boot (service auto-starts).
- Stock player still responds to the wheel when Poweramp isn't playing — no regression.
- Survives a Poweramp restart and a head-unit sleep/wake cycle.
- `Last Processed Commands` as ground truth throughout.

---

## 5. Remaining risks

| Risk | Mitigation |
|---|---|
| The DOWN arrives via MediaSession, and Poweramp keeps stealing priority index 0 | Fall back to Design B (synthesise the UP), or the `READ_LOGS` input. Also: use a `NotificationListenerService` + `getActiveSessions()` to *watch* the live priority order and re-assert when displaced |
| Neither input path sees the event | `READ_LOGS` logcat tail; failing that, decompile the vendor launcher to find the emitting call site |
| Long-press behaviour (fast-forward/rewind) still broken | Expected. Design A maps discrete presses only. `BEGIN_FAST_FORWARD`(10)/`END_FAST_FORWARD`(11) exist if you want to add hold-to-seek later |
| Volume buttons | Out of scope, and note the Poweramp API has **no volume command** — that path is `AudioManager` on `STREAM_MUSIC` |

---

## 6. Next steps

1. Get the current **Last Processed Commands** readout (you're on this) — confirms nothing changed
   in six years.
2. I write the bridge project — one self-configuring APK, all paths, on-screen log and test buttons.
3. You build it in Android Studio and check it against an API 24 emulator with Poweramp installed.
4. USB stick to the car, install, tune from the touchscreen.

ADB is optional throughout. If the unit turns out to be reachable over your home Wi-Fi, it makes
step 4 faster — but nothing in the plan waits on it.

---

## Appendix — reference values

**Poweramp targets** (verified against `Poweramp-build-1025-uni.apk`, versionCode `1025009`):

```
com.maxmpz.audioplayer/.player.PowerampAPIReceiver    exported, no permission   ← use this
com.maxmpz.audioplayer/.player.PlayerService          exported, no permission   (legacy path)
com.maxmpz.audioplayer/.PowerampAPIActivity           exported, no permission
com.maxmpz.audioplayer/.player.PSMediaButtonReceiver  exported, MEDIA_BUTTON @ priority 999
com.maxmpz.audioplayer/.data.external.BrowserService  MediaBrowserService
```

**Commands** — action `com.maxmpz.audioplayer.API_COMMAND`, int extra `"cmd"`:

```
1  TOGGLE_PLAY_PAUSE      10 BEGIN_FAST_FORWARD
2  PAUSE                  11 END_FAST_FORWARD
3  RESUME                 12 BEGIN_REWIND
4  NEXT                   13 END_REWIND
5  PREVIOUS               14 STOP
6  NEXT_IN_CAT            15 SEEK          (extra "pos", seconds)
7  PREVIOUS_IN_CAT        8  REPEAT   9  SHUFFLE
```

No volume command exists. Build ≥ 867 also accepts `"cmd"` as a case-insensitive string name.

**Status broadcasts** (sticky — readable with a null receiver for instant initial state):

```
com.maxmpz.audioplayer.STATUS_CHANGED         extras: state, paused, pos, ts
com.maxmpz.audioplayer.TRACK_CHANGED          extra:  track (Bundle)
com.maxmpz.audioplayer.PLAYING_MODE_CHANGED   extras: repeat, shuffle
```

State values: `-1` no state, `0` stopped, `1` playing, `2` paused.
