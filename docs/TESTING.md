# Testing SWC Bridge

How to test SWC Bridge without the car, and what was verified at the desk and in the car.
Back to the [README](../README.md).

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

The settings recommended after these tests (updated for later versions) are in the
[README](../README.md#recommended-settings-for-this-head-unit).
