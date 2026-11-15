package com.mattc.swcbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Starts the bridge when the head unit comes up.
 *
 * What was learned on the car (26 Sep 2026): this ROM DOES deliver BOOT_COMPLETED,
 * MEDIA_MOUNTED and USER_PRESENT -- MEDIA_MOUNTED first, about 40 s after boot. They
 * only ever failed to arrive when the unit had force-stopped this app at the previous
 * switch-off, because a force-stopped app receives no broadcasts at all.
 *
 * TIME_SET / TIMEZONE_CHANGED (v0.8): the unit has no battery-backed clock and sets the
 * time from Wi-Fi/GPS about 10 s after boot -- earlier than any other signal.
 *
 * v0.9 lean mode: the service only has one job at startup (the volume ramp), so once it
 * has run in this boot, later signals just log and let the process end again.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        SwcApp.entered("startup signal " + action);

        boolean clockSignal = Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action);
        String up = "(uptime " + Diagnostics.duration(SystemClock.elapsedRealtime()) + ")";

        if (BridgeService.isRunning()) {
            if (!clockSignal) EventLog.add("startup signal: " + action + " " + up
                    + " -- bridge already running");
            BridgeService.start(context);   // re-applies mode; harmless
            return;
        }

        if (Lean.on(context) && RunHistory.startupDoneThisBoot(context)) {
            if (!clockSignal) EventLog.add("startup signal: " + action + " " + up
                    + " -- startup already done this boot, nothing to do");
            Lean.holdFor(context, Lean.SIGNAL_LINGER_MS);
            return;
        }

        EventLog.add("startup signal: " + action + " " + up + " -> starting bridge service");
        BridgeService.start(context);
    }
}
