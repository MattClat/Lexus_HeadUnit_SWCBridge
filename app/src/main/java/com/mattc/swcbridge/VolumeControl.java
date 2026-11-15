package com.mattc.swcbridge;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;

/**
 * Forces Android's media volume to a target level at startup.
 *
 * On this head unit Android's STREAM_MUSIC level is a pre-gain stage feeding the OEM
 * amplifier over AUX -- the car's own volume knob is downstream of it. Leaving Android
 * low means throwing away signal-to-noise and then compensating on the car side.
 *
 * v0.4: on the MRW_LEXUS, setStreamVolume returns cleanly and the index does not move.
 * No exception, no SecurityException -- the vendor audio layer is simply ignoring it.
 * So rather than assume one API works, this tries several genuinely different paths and
 * reports which (if any) the hardware honours.
 */
public final class VolumeControl {

    private static final long[] BOOT_ATTEMPTS_MS = { 0L, 3000L, 10000L, 30000L };

    /** Safety stop for the adjust loops, well above any real stream's step count. */
    private static final int MAX_STEPS = 40;

    /**
     * AudioService's persisted safe-media-volume state (Settings.Global, @hide).
     * 0 = not configured, 1 = disabled, 2 = inactive, 3 = active.
     *
     * This is Android's EU listening-level limiter. It arms whenever the output device
     * reports as a wired headset -- which is how this unit's AUX line presents -- and it
     * refuses any index above the safe ceiling. With FLAG_SHOW_UI the refusal becomes the
     * "Raise volume above recommended level?" dialog; without it, the raise silently stops
     * at the ceiling.
     */
    private static final String SAFE_VOLUME_SETTING = "audio_safe_volume_state";
    public  static final int SAFE_DISABLED = 1;

    private VolumeControl() {}

    /** FLAG_SHOW_UI is the difference between a silent cap and a confirmation dialog. */
    private static int flags(Context c) {
        return Prefs.volumeShowUi(c) ? AudioManager.FLAG_SHOW_UI : 0;
    }

    private static AudioManager am(Context c) {
        return (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    public static int max(Context c) {
        AudioManager am = am(c);
        try { return am == null ? 0 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); }
        catch (Throwable t) { return 0; }
    }

    public static int current(Context c) {
        AudioManager am = am(c);
        try { return am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC); }
        catch (Throwable t) { return -1; }
    }

    public static int target(Context c) {
        int max = max(c);
        if (max <= 0) return 0;
        return Math.max(1, Math.round(max * Prefs.volumePct(c) / 100f));
    }

    /** True when the framework is built to ignore volume changes entirely. */
    public static boolean isFixed(Context c) {
        AudioManager am = am(c);
        try { return am != null && am.isVolumeFixed(); }
        catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ methods

    /** @return true if the stream ended up at the target. */
    public static boolean apply(Context c, String why) {
        AudioManager am = am(c);
        if (am == null) {
            EventLog.add("! volume [" + why + "]: no AudioManager");
            return false;
        }

        int method = Prefs.volumeMethod(c);
        int startedAt = safeGet(am, AudioManager.STREAM_MUSIC);

        if (method == Prefs.VOL_ALL) {
            for (int m = Prefs.VOL_SET; m <= Prefs.VOL_ADJUST_SUGGESTED; m++) {
                // The forced-UI method exists to raise past the safe ceiling. Skip it
                // when the user has asked not to be shown the confirmation dialog.
                if (m == Prefs.VOL_SET_UI && !Prefs.volumeShowUi(c)) continue;

                if (tryMethod(c, am, m, why)) {
                    EventLog.add("volume [" + why + "] SUCCEEDED via "
                            + Prefs.VOLUME_METHOD_NAMES[m]);
                    return true;
                }
            }
            return report(c, am, why, startedAt);
        }

        if (tryMethod(c, am, method, why)) return true;
        return report(c, am, why, startedAt);
    }

    /** Distinguishes "the system capped us" from "nothing moved at all". */
    private static boolean report(Context c, AudioManager am, String why, int startedAt) {
        int now = safeGet(am, AudioManager.STREAM_MUSIC);
        int max = safeMax(am, AudioManager.STREAM_MUSIC);
        int want = target(c);

        if (now > startedAt) {
            EventLog.add("volume [" + why + "] stopped at " + now + "/" + max
                    + " (wanted " + want + ")");
            EventLog.add("  This is Android's safe-volume ceiling for wired output, not a"
                    + " failure. Turn on 'Show volume dialog' to confirm past it, or use"
                    + " 'Disable safe-volume limit' for a permanent fix.");
            return true;
        }

        EventLog.add("! volume [" + why + "] nothing moved, still " + now + "/" + max
                + (isFixed(c) ? "  (isVolumeFixed=TRUE)" : "")
                + "  safe-volume state=" + safeStateName(safeState(c)));
        return false;
    }

    private static boolean tryMethod(Context c, AudioManager am, int method, String why) {
        int want = target(c);
        int before = safeGet(am, AudioManager.STREAM_MUSIC);

        if (before == want) {
            EventLog.add("volume [" + why + "] already " + before + "/"
                    + safeMax(am, AudioManager.STREAM_MUSIC));
            return true;
        }

        try {
            switch (method) {
                case Prefs.VOL_SET:
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
                    break;

                case Prefs.VOL_SET_UI:
                    // Some vendor builds only honour changes that come through the
                    // volume-panel path, so ask for the UI and the tick sound.
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, want,
                            AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                    break;

                case Prefs.VOL_ADJUST_MUSIC:
                    // Stepwise, as if someone held volume-up. A HAL can implement
                    // adjust without implementing set.
                    for (int i = 0; i < MAX_STEPS; i++) {
                        int now = safeGet(am, AudioManager.STREAM_MUSIC);
                        if (now >= want) break;
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_RAISE, flags(c));
                        if (safeGet(am, AudioManager.STREAM_MUSIC) == now) break; // not moving
                    }
                    break;

                case Prefs.VOL_ADJUST_RELEVANT:
                    // adjustVolume targets whatever stream the system considers most
                    // relevant right now -- which may not be STREAM_MUSIC at all on
                    // a unit with a custom audio policy.
                    for (int i = 0; i < MAX_STEPS; i++) {
                        int now = safeGet(am, AudioManager.STREAM_MUSIC);
                        if (now >= want) break;
                        am.adjustVolume(AudioManager.ADJUST_RAISE, flags(c));
                        if (safeGet(am, AudioManager.STREAM_MUSIC) == now) break;
                    }
                    break;

                case Prefs.VOL_ADJUST_SUGGESTED:
                    // This is what PhoneWindowManager ends up calling for a real
                    // hardware VOLUME_UP. The USB remote demonstrably moves Android's
                    // volume on this unit, so this is the closest public equivalent
                    // to whatever the remote is doing.
                    for (int i = 0; i < MAX_STEPS; i++) {
                        int now = safeGet(am, AudioManager.STREAM_MUSIC);
                        if (now >= want) break;
                        am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE,
                                AudioManager.STREAM_MUSIC, flags(c));
                        if (safeGet(am, AudioManager.STREAM_MUSIC) == now) break;
                    }
                    break;

                default:
                    EventLog.add("! volume: unknown method " + method);
                    return false;
            }
        } catch (SecurityException se) {
            EventLog.add("! volume [" + why + "] " + Prefs.VOLUME_METHOD_NAMES[method]
                    + " refused: " + se.getMessage());
            return false;
        } catch (Throwable t) {
            EventLog.add("! volume [" + why + "] " + Prefs.VOLUME_METHOD_NAMES[method]
                    + " failed: " + t);
            return false;
        }

        int after = safeGet(am, AudioManager.STREAM_MUSIC);
        EventLog.add("volume [" + why + "] " + Prefs.VOLUME_METHOD_NAMES[method]
                + ": " + before + " -> " + after + " of " + safeMax(am, AudioManager.STREAM_MUSIC)
                + "  (target " + want + ")");
        return after >= want;
    }

    private static int safeGet(AudioManager am, int stream) {
        try { return am.getStreamVolume(stream); } catch (Throwable t) { return -1; }
    }

    private static int safeMax(AudioManager am, int stream) {
        try { return am.getStreamMaxVolume(stream); } catch (Throwable t) { return -1; }
    }

    // ------------------------------------------------------------- safe volume limit

    /** @return the persisted safe-volume state, or -1 if unreadable. */
    public static int safeState(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), SAFE_VOLUME_SETTING, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String safeStateName(int v) {
        switch (v) {
            case 0:  return "not configured";
            case 1:  return "DISABLED";
            case 2:  return "inactive";
            case 3:  return "ACTIVE (limiting)";
            default: return "unknown(" + v + ")";
        }
    }

    /**
     * Turns the limiter off permanently, so the volume can reach maximum with no
     * confirmation dialog.
     *
     * Needs WRITE_SECURE_SETTINGS, which is a development-protection permission: a
     * normal install cannot hold it, but it can be granted once over adb. AudioService
     * reads this value at boot, so the change takes effect on the next restart.
     *
     * Worth being clear about what this switches off: it is a hearing-protection cap.
     * In this car it is measuring the wrong thing -- Android's level here is pre-gain
     * into the OEM amplifier, and actual loudness is set downstream by the car's own
     * volume -- but on headphones that cap is doing a real job.
     */
    public static void disableSafeLimit(Context c) {
        int before = safeState(c);
        EventLog.add("safe-volume state is currently: " + safeStateName(before));
        try {
            ContentResolver cr = c.getContentResolver();
            Settings.Global.putInt(cr, SAFE_VOLUME_SETTING, SAFE_DISABLED);
            EventLog.add("safe-volume limit DISABLED -- takes effect after a restart"
                    + " (AudioService reads this at boot)");
        } catch (SecurityException se) {
            EventLog.add("! needs WRITE_SECURE_SETTINGS. Grant it once over adb:");
            EventLog.add("    adb shell pm grant " + c.getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS");
            EventLog.add("  Until then, either accept the ceiling or turn on"
                    + " 'Show volume dialog' and tap OK each startup.");
        } catch (Throwable t) {
            EventLog.add("! could not write safe-volume state: " + t);
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private static final int[] STREAMS = {
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_DTMF,
    };

    private static final String[] STREAM_NAMES = {
            "VOICE_CALL", "SYSTEM", "RING", "MUSIC", "ALARM", "NOTIFICATION", "DTMF",
    };

    /**
     * Dumps every stream's index. Run once, press volume on the USB remote, run again:
     * whichever number moved is the stream Android really uses here.
     *
     * Do NOT use the steering wheel for this -- its volume buttons are handled entirely
     * by the MCU and never reach Android, so they would show no change and prove nothing.
     */
    public static void dumpStreams(Context c, String why) {
        AudioManager am = am(c);
        if (am == null) { EventLog.add("! no AudioManager"); return; }

        StringBuilder sb = new StringBuilder("=== volume snapshot [" + why + "] ===");
        for (int i = 0; i < STREAMS.length; i++) {
            sb.append("\n    ").append(STREAM_NAMES[i]).append(": ")
              .append(safeGet(am, STREAMS[i])).append(" / ").append(safeMax(am, STREAMS[i]));
        }
        sb.append("\n    isVolumeFixed: ").append(isFixed(c));
        sb.append("\n    safe-volume:   ").append(safeStateName(safeState(c)));
        sb.append("\n    mode: ").append(modeName(am));
        sb.append("\n    music active: ");
        try { sb.append(am.isMusicActive()); } catch (Throwable t) { sb.append('?'); }
        if (Build.VERSION.SDK_INT >= 21) {
            try { sb.append("\n    wired headset on: ").append(am.isWiredHeadsetOn()); }
            catch (Throwable ignored) {}
        }
        EventLog.add(sb.toString());
    }

    private static String modeName(AudioManager am) {
        try {
            switch (am.getMode()) {
                case AudioManager.MODE_NORMAL:          return "NORMAL";
                case AudioManager.MODE_RINGTONE:        return "RINGTONE";
                case AudioManager.MODE_IN_CALL:         return "IN_CALL";
                case AudioManager.MODE_IN_COMMUNICATION:return "IN_COMMUNICATION";
                default:                                return String.valueOf(am.getMode());
            }
        } catch (Throwable t) { return "?"; }
    }

    // ------------------------------------------------------------------ top-up on NEXT

    /**
     * v0.9.2: called after each NEXT press. If Android's media volume is below the target,
     * raise it -- first with volume-up steps (the same call the framework makes for a
     * real volume-up key, i.e. the USB remote's path), then plain stream steps, and
     * finally a direct set. Logs exactly one line, so the log shows what happened every
     * time: whether it moved, and if not, the conditions it was refused under.
     *
     * "Show volume dialog" applies here too: with it on, a raise past the safe-volume
     * ceiling pops Android's confirmation instead of being silently refused.
     */
    public static void topUp(Context c, String why) {
        AudioManager am = am(c);
        if (am == null) {
            EventLog.add("! volume [" + why + "]: no AudioManager");
            return;
        }
        final int s = AudioManager.STREAM_MUSIC;
        int max = safeMax(am, s);
        int want = target(c);
        int before = safeGet(am, s);
        if (max <= 0 || before < 0) {
            EventLog.add("! volume [" + why + "]: can't read the media volume");
            return;
        }
        if (before >= want) {
            EventLog.add("volume [" + why + "] " + before + "/" + max + " - already at target");
            return;
        }

        int f = flags(c);
        int keySteps = 0, streamSteps = 0;
        boolean triedSet = false;
        String error = null;
        try {
            for (int i = 0; i < MAX_STEPS && safeGet(am, s) < want; i++) {
                int now = safeGet(am, s);
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, s, f);
                keySteps++;
                if (safeGet(am, s) == now) break;          // refused, stop asking
            }
            for (int i = 0; i < MAX_STEPS && safeGet(am, s) < want; i++) {
                int now = safeGet(am, s);
                am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f);
                streamSteps++;
                if (safeGet(am, s) == now) break;
            }
            if (safeGet(am, s) < want) {
                am.setStreamVolume(s, want, f);
                triedSet = true;
            }
        } catch (Throwable t) {
            error = t.toString();
        }

        int after = safeGet(am, s);
        StringBuilder sb = new StringBuilder("volume [" + why + "] ")
                .append(before).append("/").append(max).append(" -> ")
                .append(after).append("/").append(max);
        if (after >= want) {
            sb.append("  OK");
        } else {
            sb.append(after > before ? "  PARTLY raised" : "  REFUSED")
              .append(" (target ").append(want).append(")");
        }
        sb.append("  [volume-up x").append(keySteps)
          .append(", stream-up x").append(streamSteps)
          .append(triedSet ? ", set" : "")
          .append(f != 0 ? ", with dialog" : "")
          .append("]");
        if (after < want) {
            sb.append("  safe:").append(safeStateName(safeState(c)));
            try { sb.append("  music active:").append(am.isMusicActive()); }
            catch (Throwable ignored) {}
        }
        if (error != null) sb.append("  ! ").append(error);
        EventLog.add(sb.toString());
    }

    // ------------------------------------------------------------------ boot ramp

    public static void scheduleBootRamp(final Context ctx, Handler h) {
        final Context c = ctx.getApplicationContext();
        if (!Prefs.volumeAtBoot(c)) {
            EventLog.add("volume: startup override is off");
            return;
        }
        for (int i = 0; i < BOOT_ATTEMPTS_MS.length; i++) {
            final String label = "startup " + (i + 1) + "/" + BOOT_ATTEMPTS_MS.length;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (Prefs.volumeAtBoot(c)) apply(c, label);
                }
            }, BOOT_ATTEMPTS_MS[i]);
        }
    }

    public static String summary(Context c) {
        int max = max(c);
        if (max <= 0) return "volume: unavailable";
        return "volume " + current(c) + " / " + max
                + (isFixed(c) ? " (FIXED)" : "")
                + "  safe:" + safeStateName(safeState(c));
    }
}
