package com.mattc.swcbridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * A repeating alarm that restarts the bridge service if it has gone away.
 *
 * Note: a force-stop cancels this alarm along with everything else, so the watchdog
 * cannot rescue the app from the failure seen on the car -- only from ordinary kills.
 *
 * It is also the diagnosis. If the app was force-stopped -- by a task killer, or by the
 * unit's "clean memory" routine -- Android cancels its alarms too, so these ticks stop
 * appearing in the persistent log. A gap in the ticks is therefore evidence of exactly
 * the failure this version is chasing, and the absence of a gap rules it out.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    /** Log at most hourly, so three quiet days do not bury the interesting lines. */
    private static final long LOG_EVERY_MS = 60 * 60 * 1000L;
    private static long sLastLogged = 0L;

    public static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            // v0.8: non-wakeup. v0.7 used ELAPSED_REALTIME_WAKEUP, which pulls the SoC
            // out of sleep every 15 minutes while the car is parked -- the opposite of
            // what the unit should be doing. A non-wakeup alarm simply waits until the
            // unit is awake anyway.
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS, pending(c));
        } catch (Throwable t) {
            EventLog.add("! watchdog schedule failed: " + t);
        }
    }

    /** v0.9: lean mode must not be woken every 15 minutes, so the alarm is removed. */
    public static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(c));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c.getApplicationContext(), WatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c.getApplicationContext(), 0, i, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        SwcApp.entered("watchdog alarm");
        if (Lean.on(context)) {
            // Left over from keep-running mode (or v0.8). Remove it and go away.
            cancel(context);
            EventLog.add("watchdog alarm fired in lean mode -- removed it");
            Lean.holdFor(context, Lean.SIGNAL_LINGER_MS);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastLogged > LOG_EVERY_MS || sLastLogged == 0L) {
            sLastLogged = now;
            EventLog.add("watchdog tick (uptime " + Diagnostics.duration(now)
                    + ", service " + (BridgeService.isRunning() ? "running" : "NOT running") + ")");
            // Gives a timeline of the audio source across a long sit.
            VendorInfo.log(context, "watchdog");
        }
        BridgeService.start(context);
    }
}
