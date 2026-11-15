package com.mattc.swcbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** All runtime configuration. Everything here is tunable from the head unit's touchscreen. */
public final class Prefs {

    private static final String FILE = "swcbridge";

    // --- keys ---
    public static final String ENABLED          = "enabled";
    public static final String IN_RECEIVER      = "in_receiver";
    public static final String IN_SESSION       = "in_session";
    public static final String SESSION_REASSERT = "session_reassert";
    public static final String ONLY_WHEN_PLAYING= "only_when_playing";
    public static final String ACT_ON_DOWN      = "act_on_down";
    public static final String SWAP_NEXT_PREV   = "swap_next_prev";
    public static final String OUTPUT_MODE      = "output_mode";
    public static final String DEBOUNCE_MS      = "debounce_ms";
    public static final String GESTURE_MODE     = "gesture_mode";
    public static final String DOUBLE_WINDOW_MS = "double_window_ms";
    public static final String VOLUME_AT_BOOT   = "volume_at_boot";
    public static final String VOLUME_PCT       = "volume_pct";
    public static final String VOLUME_METHOD    = "volume_method";
    public static final String VOLUME_SHOW_UI   = "volume_show_ui";
    public static final String KEEP_RUNNING     = "keep_running";
    public static final String LEAN_SELF_EXIT   = "lean_self_exit";
    public static final String VOLUME_ON_NEXT   = "volume_on_next";

    // --- output modes ---
    /** Send Poweramp's own API command. Leaves the key-event system entirely. */
    public static final int OUT_PA_API              = 0;
    /** Send a well-formed DOWN+UP KeyEvent pair straight to Poweramp's PSMediaButtonReceiver. */
    public static final int OUT_PA_MEDIA_BUTTON     = 1;
    /** Send only the missing ACTION_UP to PSMediaButtonReceiver, completing the head unit's DOWN. */
    public static final int OUT_PA_UP_ONLY          = 2;
    /** Generic: AudioManager.dispatchMediaKeyEvent DOWN+UP, for any player holding the session. */
    public static final int OUT_GLOBAL_MEDIA_KEY    = 3;

    public static final String[] OUTPUT_MODE_NAMES = {
            "A - Poweramp API command (recommended)",
            "B - DOWN+UP to Poweramp media button receiver",
            "C - Supply missing ACTION_UP only",
            "D - Global media key (any player)"
    };

    // --- play/pause gesture ---
    public static final int GESTURE_OFF     = 0;
    public static final int GESTURE_ON_NEXT = 1;
    public static final int GESTURE_ON_PREV = 2;

    public static final String[] GESTURE_NAMES = {
            "Off - every press is a track change",
            "Double-press NEXT = play/pause",
            "Double-press PREVIOUS = play/pause"
    };

    // --- how to set the volume ---
    public static final int VOL_SET              = 0;
    public static final int VOL_SET_UI           = 1;
    public static final int VOL_ADJUST_MUSIC     = 2;
    public static final int VOL_ADJUST_RELEVANT  = 3;
    /** What the framework itself calls when it handles a hardware VOLUME_UP key -- the
     *  same path the USB remote takes, which is known to work on this unit. */
    public static final int VOL_ADJUST_SUGGESTED = 4;
    public static final int VOL_ALL              = 5;

    public static final String[] VOLUME_METHOD_NAMES = {
            "setStreamVolume",
            "setStreamVolume + UI",
            "adjustStreamVolume step",
            "adjustVolume (relevant stream)",
            "adjustSuggestedStreamVolume (key path)",
            "Try all, report what works"
    };

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean bool(Context c, String key, boolean def) {
        return get(c).getBoolean(key, def);
    }

    public static int integer(Context c, String key, int def) {
        return get(c).getInt(key, def);
    }

    public static void put(Context c, String key, boolean v) {
        get(c).edit().putBoolean(key, v).apply();
    }

    public static void put(Context c, String key, int v) {
        get(c).edit().putInt(key, v).apply();
    }

    // --- convenience accessors with the shipped defaults ---

    public static boolean enabled(Context c)         { return bool(c, ENABLED, true); }

    /**
     * v0.9. Off (the default) = "lean mode": the app does its startup work and then exits,
     * and each wheel press starts it only briefly -- so it is not a running app when the
     * car is switched off, which is when the head unit force-stops running apps.
     * On = the v0.7/v0.8 behaviour: an always-on foreground service (needed for Input 2).
     */
    public static boolean keepRunning(Context c)     { return bool(c, KEEP_RUNNING, false); }

    /**
     * v0.9.1 test switch. On (default): in lean mode the process ends itself when idle.
     * Off: the process is left for Android to reclaim whenever it likes -- only useful
     * to check whether ending the process is itself what trips the unit's force-stop.
     */
    public static boolean leanSelfExit(Context c)    { return bool(c, LEAN_SELF_EXIT, true); }
    /** Both input paths on by default, so the very first install reveals which one fires. */
    public static boolean inputReceiver(Context c)   { return bool(c, IN_RECEIVER, true); }
    public static boolean inputSession(Context c)    { return bool(c, IN_SESSION, true); }
    public static boolean sessionReassert(Context c) { return bool(c, SESSION_REASSERT, true); }
    public static boolean onlyWhenPlaying(Context c) { return bool(c, ONLY_WHEN_PLAYING, false); }
    /** The whole point: act on the press, not the release. */
    public static boolean actOnDown(Context c)       { return bool(c, ACT_ON_DOWN, true); }
    /** Some MCUs emit KEYCODE_MEDIA_PREVIOUS for the "next" button and vice versa.
     *  Applies to the steering wheel's virtual key events only (v0.8). */
    public static boolean swapNextPrev(Context c)    { return bool(c, SWAP_NEXT_PREV, false); }
    public static int outputMode(Context c) {
        int m = integer(c, OUTPUT_MODE, OUT_PA_API);
        return (m < 0 || m >= OUTPUT_MODE_NAMES.length) ? OUT_PA_API : m;
    }
    public static int debounceMs(Context c)          { return integer(c, DEBOUNCE_MS, 400); }

    /**
     * Defaults to the PREVIOUS button: detecting a double press means delaying the
     * single press, and Previous is pressed far less often than Next, so the lag
     * lands where it is least noticeable.
     */
    public static int gestureMode(Context c) {
        int m = integer(c, GESTURE_MODE, GESTURE_ON_PREV);
        return (m < 0 || m >= GESTURE_NAMES.length) ? GESTURE_ON_PREV : m;
    }

    /** The Poweramp command the gesture watches for, or 0 when the gesture is off. */
    public static int gestureCommand(Context c) {
        switch (gestureMode(c)) {
            case GESTURE_ON_NEXT: return Poweramp.CMD_NEXT;
            case GESTURE_ON_PREV: return Poweramp.CMD_PREVIOUS;
            default:              return 0;
        }
    }

    public static int doubleWindowMs(Context c)      { return integer(c, DOUBLE_WINDOW_MS, 350); }

    public static boolean volumeAtBoot(Context c)    { return bool(c, VOLUME_AT_BOOT, true); }
    public static int volumePct(Context c)           { return integer(c, VOLUME_PCT, 100); }

    /**
     * v0.9.2. On (default): every NEXT press also checks Android's media volume and raises
     * it toward the volume target if it is below. Matt sets the real loudness with the
     * car amp, so Android's level should simply always sit at the top.
     */
    public static boolean volumeOnNext(Context c)    { return bool(c, VOLUME_ON_NEXT, true); }
    /** Defaults to trying everything: on this unit the plain setter is ignored. */
    public static int volumeMethod(Context c) {
        int m = integer(c, VOLUME_METHOD, VOL_ALL);
        return (m < 0 || m >= VOLUME_METHOD_NAMES.length) ? VOL_ALL : m;
    }

    /**
     * FLAG_SHOW_UI is what turns Android's safe-volume rejection into the
     * "Raise volume above recommended level?" confirmation dialog. Off by default:
     * the volume then climbs silently to the safe ceiling and stops there.
     */
    public static boolean volumeShowUi(Context c)    { return bool(c, VOLUME_SHOW_UI, false); }
}
