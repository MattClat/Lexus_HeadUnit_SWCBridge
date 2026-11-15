package com.mattc.swcbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics about process starts and boots.
 *
 * "Last activity" (wall time, uptime, boot count) is written whenever a process of ours
 * starts, every 30 s while the service runs, and just before a lean-mode exit. The first
 * process of each boot reads it back to report on the previous boot, and uses it to tell
 * whether it is the first of this boot at all.
 *
 * That last point is the key v0.9 diagnostic: if the FIRST process of a boot was started
 * by the user rather than by a startup signal or a wheel press, the app was force-stopped
 * at the previous switch-off.
 */
final class RunHistory {

    private static final String K_ALIVE_WALL   = "hb_wall";
    private static final String K_ALIVE_UPTIME = "hb_uptime";
    private static final String K_ALIVE_BOOT   = "hb_boot";

    /** Set once the startup volume ramp has run in a boot, so it runs once per boot. */
    private static final String K_STARTUP_UPTIME = "startup_uptime";
    private static final String K_STARTUP_BOOT   = "startup_boot";

    // What the previous "last activity" said, captured before this process overwrites it.
    private static long sPrevWall = 0L;
    private static long sPrevUptime = -1L;
    private static int sPrevBoot = -1;
    private static boolean sFirstThisBoot = true;

    private RunHistory() {}

    /**
     * Settings.Global.BOOT_COUNT ("boot_count") exists from API 24; confirmed present on
     * this unit (boot #1170-#1172 on 26 Sep). Falls back to -1 and uptime comparison.
     */
    static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Same boot as a record written at (bootThen, upThen)? Uptime restarts at every boot. */
    private static boolean sameBoot(Context c, int bootThen, long upThen) {
        if (upThen < 0) return false;
        int bootNow = bootCount(c);
        if (bootNow >= 0 && bootThen >= 0) return bootNow == bootThen;
        return SystemClock.elapsedRealtime() >= upThen;
    }

    /**
     * Called once from SwcApp.onCreate, first thing.
     * @return true if this is the first process of ours since the unit booted
     */
    static boolean noteProcessStart(Context c) {
        try {
            SharedPreferences p = Prefs.get(c);
            sPrevWall = p.getLong(K_ALIVE_WALL, 0L);
            sPrevUptime = p.getLong(K_ALIVE_UPTIME, -1L);
            sPrevBoot = p.getInt(K_ALIVE_BOOT, -1);
            sFirstThisBoot = !sameBoot(c, sPrevBoot, sPrevUptime);
        } catch (Throwable t) {
            sFirstThisBoot = true;
        }
        heartbeat(c);
        return sFirstThisBoot;
    }

    static boolean firstProcessThisBoot() { return sFirstThisBoot; }

    /** Logged by the first process of a boot only. */
    static void logPreviousBoot(Context c) {
        if (sPrevUptime < 0) {
            EventLog.add("previous boot: nothing recorded yet");
            return;
        }
        StringBuilder sb = new StringBuilder("previous boot: last activity ");
        sb.append(formatWall(sPrevWall)).append(" at uptime ").append(Diagnostics.duration(sPrevUptime));
        int bootNow = bootCount(c);
        if (sPrevBoot >= 0 && bootNow >= 0) {
            sb.append("  (boot #").append(sPrevBoot).append(" -> #").append(bootNow).append(')');
        }
        EventLog.add(sb.toString());
    }

    /** Asynchronous; fine while the process keeps running. */
    static void heartbeat(Context c) {
        try {
            edit(c).apply();
        } catch (Throwable ignored) {}
    }

    /** Synchronous; used right before the process ends itself. */
    static void heartbeatNow(Context c) {
        try {
            edit(c).commit();
        } catch (Throwable ignored) {}
    }

    private static SharedPreferences.Editor edit(Context c) {
        return Prefs.get(c).edit()
                .putLong(K_ALIVE_WALL, System.currentTimeMillis())
                .putLong(K_ALIVE_UPTIME, SystemClock.elapsedRealtime())
                .putInt(K_ALIVE_BOOT, bootCount(c));
    }

    // ------------------------------------------------------------------ startup volume

    static boolean startupDoneThisBoot(Context c) {
        try {
            SharedPreferences p = Prefs.get(c);
            return sameBoot(c, p.getInt(K_STARTUP_BOOT, -1), p.getLong(K_STARTUP_UPTIME, -1L));
        } catch (Throwable t) {
            return false;
        }
    }

    static void markStartupDone(Context c) {
        try {
            Prefs.get(c).edit()
                    .putLong(K_STARTUP_UPTIME, SystemClock.elapsedRealtime())
                    .putInt(K_STARTUP_BOOT, bootCount(c))
                    .commit();
        } catch (Throwable ignored) {}
    }

    private static String formatWall(long wall) {
        if (wall <= 0) return "(unknown time)";
        String s = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(wall));
        return Diagnostics.clockLooksUnset(wall) ? s + " [clock was unset]" : s;
    }
}
