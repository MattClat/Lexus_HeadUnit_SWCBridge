package com.mattc.swcbridge;

import android.app.Application;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

/**
 * Application.onCreate runs whenever this process is created, for ANY reason -- a
 * broadcast, the service, or the user opening the app.
 *
 * v0.9: in lean mode a process is created for every wheel press after an idle spell, so
 * the per-process logging is one line ("=== process started: <reason> ==="). The fuller
 * diagnostics -- previous boot, vendor audio, Poweramp's state -- are logged only by the
 * FIRST process of each boot, which is also the one that says whether the app survived
 * the last switch-off.
 */
public class SwcApp extends Application {

    private static volatile boolean sReasonLogged = false;
    private static Application sApp;

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        EventLog.attach(this);
        Lean.init(this);
        RunHistory.noteProcessStart(this);

        // Every entry point calls entered(). If none has after a few seconds, something
        // created the process without running any of our components -- worth knowing.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                entered("unknown -- no component of ours ran within 5 s");
                Lean.holdFor(sApp, Lean.SIGNAL_LINGER_MS);
            }
        }, 5000L);
    }

    /**
     * Called at the top of every entry point. Only the first call in a process is logged,
     * so the log says what actually created the process.
     */
    public static void entered(String what) {
        if (sReasonLogged) return;
        sReasonLogged = true;
        Application c = sApp;
        if (c == null) return;

        StringBuilder sb = new StringBuilder("=== process started: ").append(what)
                .append(" === ").append(Diagnostics.startupLine(c));

        // How long Android took to get us from "process launched" to handling this
        // entry point: the delay a cold wheel press pays in lean mode.
        if (Build.VERSION.SDK_INT >= 24 && what.startsWith("media button")) {
            long ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis();
            sb.append("  cold start ").append(ms).append(" ms");
        }
        EventLog.add(sb.toString());

        if (!RunHistory.firstProcessThisBoot()) return;

        // First process of this boot: this is the line that answers "did the app survive
        // the last switch-off?"
        long up = SystemClock.elapsedRealtime();
        String verdict;
        if (what.startsWith("startup signal") || what.startsWith("media button")) {
            verdict = "first run this boot, started by the system -> the app was NOT"
                    + " force-stopped at the last switch-off";
        } else if (what.startsWith("user opened") && up > 90_000L) {
            verdict = "first run this boot, " + Diagnostics.duration(up) + " after boot, and"
                    + " only because it was opened by hand -> the app WAS force-stopped at"
                    + " the last switch-off";
        } else if (what.startsWith("user opened")) {
            verdict = "first run this boot, opened by hand " + Diagnostics.duration(up)
                    + " after boot -- too early to tell whether it was force-stopped";
        } else {
            verdict = "first run this boot";
        }
        EventLog.add(verdict);
        RunHistory.logPreviousBoot(c);
        VendorInfo.log(c, "first run this boot");
        EventLog.add(Diagnostics.powerampPackageState(c));
    }
}
