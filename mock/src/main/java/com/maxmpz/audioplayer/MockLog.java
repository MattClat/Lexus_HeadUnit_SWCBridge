package com.maxmpz.audioplayer;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared log between the fake receivers and the display activity. */
public final class MockLog {

    /** Mirrored to logcat: `adb logcat -s MockPoweramp:*` */
    public static final String TAG = "MockPoweramp";

    private static final int MAX = 400;
    private static final List<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private MockLog() {}

    public static void add(String s) {
        Log.i(TAG, s);
        synchronized (LINES) {
            LINES.add(TS.format(new Date()) + "  " + s);
            while (LINES.size() > MAX) LINES.remove(0);
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            if (LINES.isEmpty()) {
                return "(nothing received yet)\n\nSend something from SWC Bridge "
                        + "and it will appear here.";
            }
            for (String s : LINES) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LINES) { LINES.clear(); }
    }

    /** Human-readable name for a PowerampAPI.Commands value. */
    public static String cmdName(int cmd) {
        switch (cmd) {
            case 1:  return "TOGGLE_PLAY_PAUSE";
            case 2:  return "PAUSE";
            case 3:  return "RESUME";
            case 4:  return "NEXT";
            case 5:  return "PREVIOUS";
            case 6:  return "NEXT_IN_CAT";
            case 7:  return "PREVIOUS_IN_CAT";
            case 8:  return "REPEAT";
            case 9:  return "SHUFFLE";
            case 10: return "BEGIN_FAST_FORWARD";
            case 11: return "END_FAST_FORWARD";
            case 12: return "BEGIN_REWIND";
            case 13: return "END_REWIND";
            case 14: return "STOP";
            case 15: return "SEEK";
            default: return "cmd " + cmd;
        }
    }
}
