package com.mattc.swcbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

/**
 * v0.9 "lean mode": make sure this app is NOT a running app when the car is switched off.
 *
 * Why (proven on the car, 26 Sep 2026): at switch-off the head unit force-stops the apps
 * that are running -- except the one playing audio -- before Android even starts to shut
 * down. A force-stopped app receives nothing (no BOOT_COMPLETED, no steering-wheel
 * MEDIA_BUTTON) until it is opened by hand. v0.7/v0.8 kept a foreground service running
 * permanently, so the app was always running at switch-off and was always killed.
 *
 * The steering wheel does not need a running app: MediaButtonReceiver is declared in the
 * manifest, so Android starts the process on demand for each press. So in lean mode the
 * process ends itself whenever it is idle:
 *
 *   - after a wheel press            -> KEY_LINGER_MS later (covers the double-press window)
 *   - after the startup volume ramp  -> when BridgeService stops itself
 *   - after the config screen closes -> UI_LINGER_MS later
 *
 * Nothing here runs when "Keep bridge running" is on.
 */
final class Lean {

    static final long KEY_LINGER_MS     = 5000L;
    static final long UI_LINGER_MS      = 2000L;
    static final long SERVICE_LINGER_MS = 1500L;
    static final long SIGNAL_LINGER_MS  = 1500L;

    /** When something still needs the process at exit time, look again this much later. */
    private static final long RECHECK_MS = 2000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context sApp;
    private static volatile boolean sUiVisible = false;
    private static long sExitAt = 0L;

    private Lean() {}

    static void init(Context c) { sApp = c.getApplicationContext(); }

    /** True when lean mode is active (the default). */
    static boolean on(Context c) { return !Prefs.keepRunning(c); }

    static boolean uiVisible() { return sUiVisible; }

    static void setUiVisible(Context c, boolean visible) {
        sUiVisible = visible;
        if (!visible) {
            BridgeService.onUiHidden();
            holdFor(c, UI_LINGER_MS);
        }
    }

    /** Keep the process for at least {@code ms} from now, then end it if nothing needs it. */
    static void holdFor(Context c, long ms) {
        if (sApp == null) sApp = c.getApplicationContext();
        if (!on(sApp)) return;
        long at = SystemClock.uptimeMillis() + ms;
        if (at <= sExitAt) return;
        sExitAt = at;
        H.removeCallbacks(EXIT);
        H.postDelayed(EXIT, ms);
    }

    private static final Runnable EXIT = new Runnable() {
        @Override public void run() {
            Context c = sApp;
            if (c == null || !on(c)) return;

            if (sUiVisible || BridgeService.isRunning() || KeyRouter.gesturePending()) {
                // Something still needs us. Whatever it is will call holdFor() when it is
                // done; the recheck is just a safety net.
                sExitAt = SystemClock.uptimeMillis() + RECHECK_MS;
                H.postDelayed(this, RECHECK_MS);
                return;
            }

            RunHistory.heartbeatNow(c);   // synchronous: the process may end on the next line
            if (!Prefs.leanSelfExit(c)) {
                EventLog.add("idle (lean mode) -- self-exit is OFF, process left for Android to reclaim");
                return;
            }
            EventLog.add("process exit (lean mode, idle) -- nothing of ours is running now");
            Process.killProcess(Process.myPid());
        }
    };
}
