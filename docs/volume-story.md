# The volume story (v0.4 – v0.6)

Why Android's media volume would not go up on this head unit, and what fixed it. Worth recording
in full, because the first diagnosis was wrong and the log said so.
Back to the [README](../README.md) · [CHANGELOG](../CHANGELOG.md).

## What it looked like

`setStreamVolume` returned cleanly, threw nothing, and the index did not move:

```
volume [button] 9 -> 9 of 15  (target 15 = 100%)
 ! volume did not stick -- something else is holding it
```

The obvious reading — "the vendor audio layer owns this stream, like the SWC keys" —
was wrong.

## The clue that broke it open

Two observations from the car, which point in opposite directions:

- The **steering wheel** volume buttons never reach Android at all. They are handled by
  the MCU and change the amp directly. Nothing to do with Android's mixer.
- The **USB wireless remote** *does* move Android's volume.

So `STREAM_MUSIC` was demonstrably settable — the system was honouring a real hardware
volume key while ignoring our programmatic call. That is not what a vendor lock looks like.

## The actual cause

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

## Three ways out

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

## Diagnostics added

- **Volume snapshot** — dumps every stream's index plus `isVolumeFixed`, audio mode, and
  the safe-volume state. Snapshot, press volume on the **USB remote** (not the wheel, which
  never reaches Android), snapshot again: whichever number moved is the stream in use.
- Five selectable set-volume methods with a "try all, report what works" mode.
- The log now distinguishes *"stopped at the safe ceiling"* from *"nothing moved at all"* —
  the distinction that would have saved a version or two.

## Lesson

"The call is ignored" had two candidate explanations that look identical from one data
point. The second observation — one input path working while another failed — is what
separated them. When a silent no-op has more than one plausible cause, find the case that
*does* work and ask what is different about it.

## Later: v0.9.2

v0.9.2 added a top-up on every NEXT press, which gets the volume to 100% once music is playing,
with no dialog. See [CHANGELOG.md](../CHANGELOG.md#v092--volume-top-up-on-next-2026-10-04).
