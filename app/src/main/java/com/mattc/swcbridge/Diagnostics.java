package com.mattc.swcbridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import java.util.Calendar;

/** Device / Poweramp facts, shown on screen and written into any exported log. */
public final class Diagnostics {

    private Diagnostics() {}

    // ------------------------------------------------------------------ app version

    private static String sVersion;

    /** "v0.8 (build 8)", read from the installed package so it can never drift. */
    public static String appVersion(Context c) {
        if (sVersion != null) return sVersion;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            sVersion = "v" + pi.versionName + " (build " + pi.versionCode + ")";
        } catch (Throwable t) {
            sVersion = "v?";
        }
        return sVersion;
    }

    // ------------------------------------------------------------------ time

    /** "0:03:39", or "2d 4:05:06" past a day. */
    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        String hms = h + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
        return d > 0 ? d + "d " + hms : hms;
    }

    /**
     * This unit has no battery-backed clock: every boot starts at Unix time zero
     * (shown as 12-31 18:00 in Central time) until GPS or Wi-Fi sets it.
     */
    public static boolean clockLooksUnset(long wallMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(wallMs);
        return cal.get(Calendar.YEAR) < 2020;
    }

    /**
     * The first line of every run: app version, uptime (how long since the unit booted --
     * the one number the clock can't be trusted for), boot count and clock state.
     */
    public static String startupLine(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(appVersion(c));
        sb.append("  uptime ").append(duration(SystemClock.elapsedRealtime()));
        int boot = RunHistory.bootCount(c);
        if (boot >= 0) sb.append("  boot #").append(boot);
        if (clockLooksUnset(System.currentTimeMillis())) sb.append("  clock NOT SET yet");
        return sb.toString();
    }

    // ------------------------------------------------------------------ poweramp

    /**
     * Whether Android currently has Poweramp in the stopped (force-stopped) state.
     * The same flag is what silences SWC Bridge, so seeing it on Poweramp at startup
     * says the unit's shutdown cleanup hit Poweramp too.
     */
    public static String powerampPackageState(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(Poweramp.packageName(c), 0);
            boolean stopped = (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
            return "poweramp package: " + (stopped
                    ? "STOPPED (force-stopped; will not start by itself)"
                    : "not stopped");
        } catch (Throwable t) {
            return "poweramp package: not installed";
        }
    }

    // ------------------------------------------------------------------ summaries

    public static String oneLine(Context c) {
        int build = Poweramp.build(c);
        return "SWC " + appVersion(c)
                + ", Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + ", Poweramp " + (build == -1 ? "NOT FOUND" : "build " + build);
    }

    public static String header(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("SWC Bridge diagnostics  ").append(appVersion(c)).append('\n');
        sb.append("=======================\n");
        sb.append("device        : ").append(Build.MANUFACTURER).append(' ')
          .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        sb.append("fingerprint   : ").append(Build.FINGERPRINT).append('\n');
        sb.append("android       : ").append(Build.VERSION.RELEASE)
          .append("  API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("uptime        : ").append(duration(SystemClock.elapsedRealtime())).append('\n');
        sb.append("boot count    : ").append(RunHistory.bootCount(c)).append('\n');
        sb.append("poweramp pkg  : ").append(Poweramp.packageName(c)).append('\n');
        sb.append("poweramp build: ").append(Poweramp.build(c)).append('\n');
        sb.append("poweramp state: ").append(KeyRouter.stateName(KeyRouter.powerampState())).append('\n');
        sb.append(powerampPackageState(c)).append('\n');
        sb.append("\nsettings\n");
        sb.append("  enabled           : ").append(Prefs.enabled(c)).append('\n');
        sb.append("  keep running      : ").append(Prefs.keepRunning(c))
          .append(Prefs.keepRunning(c) ? "  (v0.8 behaviour)" : "  (lean mode)").append('\n');
        sb.append("  lean self-exit    : ").append(Prefs.leanSelfExit(c)).append('\n');
        sb.append("  input broadcast   : ").append(Prefs.inputReceiver(c)).append('\n');
        sb.append("  input session     : ").append(Prefs.inputSession(c))
          .append(Prefs.keepRunning(c) ? "" : "  (inactive in lean mode)").append('\n');
        sb.append("  session re-assert : ").append(Prefs.sessionReassert(c)).append('\n');
        sb.append("  act on DOWN       : ").append(Prefs.actOnDown(c)).append('\n');
        sb.append("  swap next/prev    : ").append(Prefs.swapNextPrev(c))
          .append("  (steering wheel keys only)\n");
        sb.append("  only when playing : ").append(Prefs.onlyWhenPlaying(c)).append('\n');
        sb.append("  debounce ms       : ").append(Prefs.debounceMs(c)).append('\n');
        sb.append("  output mode       : ")
          .append(Prefs.OUTPUT_MODE_NAMES[Prefs.outputMode(c)]).append('\n');
        sb.append("  gesture           : ")
          .append(Prefs.GESTURE_NAMES[Prefs.gestureMode(c)]).append('\n');
        sb.append("  double window ms  : ").append(Prefs.doubleWindowMs(c)).append('\n');
        sb.append("  volume at boot    : ").append(Prefs.volumeAtBoot(c)).append('\n');
        sb.append("  volume target %   : ").append(Prefs.volumePct(c)).append('\n');
        sb.append("  volume method     : ")
          .append(Prefs.VOLUME_METHOD_NAMES[Prefs.volumeMethod(c)]).append('\n');
        sb.append("  volume show UI    : ").append(Prefs.volumeShowUi(c)).append('\n');
        sb.append("  volume on NEXT    : ").append(Prefs.volumeOnNext(c)).append('\n');
        sb.append("  isVolumeFixed     : ").append(VolumeControl.isFixed(c)).append('\n');
        sb.append("  safe-volume state : ")
          .append(VolumeControl.safeStateName(VolumeControl.safeState(c))).append('\n');
        sb.append("  ").append(VolumeControl.summary(c)).append('\n');
        return sb.toString();
    }
}
