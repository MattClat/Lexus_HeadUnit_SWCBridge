package com.mattc.swcbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.view.KeyEvent;

/**
 * Everything we know about Poweramp, verified against Poweramp-build-1025-uni.apk
 * (versionCode 1025009). All three targets below are exported with no android:permission,
 * so any app may drive them.
 */
public final class Poweramp {

    public static final String DEFAULT_PACKAGE = "com.maxmpz.audioplayer";

    public static final String ACTION_API_COMMAND   = "com.maxmpz.audioplayer.API_COMMAND";
    public static final String ACTION_STATUS_CHANGED= "com.maxmpz.audioplayer.STATUS_CHANGED";
    public static final String ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED";

    public static final String API_RECEIVER   = "com.maxmpz.audioplayer.player.PowerampAPIReceiver";
    public static final String MEDIA_BUTTON_RECEIVER =
            "com.maxmpz.audioplayer.player.PSMediaButtonReceiver";
    public static final String PLAYER_SERVICE = "com.maxmpz.audioplayer.player.PlayerService";

    public static final String EXTRA_COMMAND = "cmd";
    public static final String EXTRA_PACKAGE = "pak";
    public static final String EXTRA_STATE   = "state";

    public static final int STATE_NO_STATE = -1;
    public static final int STATE_STOPPED  = 0;
    public static final int STATE_PLAYING  = 1;
    public static final int STATE_PAUSED   = 2;

    /** PowerampAPI.Commands */
    public static final int CMD_TOGGLE_PLAY_PAUSE = 1;
    public static final int CMD_PAUSE             = 2;
    public static final int CMD_RESUME            = 3;
    public static final int CMD_NEXT              = 4;
    public static final int CMD_PREVIOUS          = 5;
    public static final int CMD_BEGIN_FAST_FORWARD= 10;
    public static final int CMD_END_FAST_FORWARD  = 11;
    public static final int CMD_BEGIN_REWIND      = 12;
    public static final int CMD_END_REWIND        = 13;
    public static final int CMD_STOP              = 14;

    private static String sPackage;
    private static int sBuild = -1;

    private Poweramp() {}

    public static String cmdName(int cmd) {
        switch (cmd) {
            case CMD_TOGGLE_PLAY_PAUSE: return "TOGGLE_PLAY_PAUSE";
            case CMD_PAUSE:             return "PAUSE";
            case CMD_RESUME:            return "RESUME";
            case CMD_NEXT:              return "NEXT";
            case CMD_PREVIOUS:          return "PREVIOUS";
            case CMD_STOP:              return "STOP";
            default:                    return "cmd" + cmd;
        }
    }

    /**
     * Resolve Poweramp's real package. Some editions ship under a different name
     * (e.g. com.maxmpz.audioplayer.huawei), so never hardcode it blindly.
     */
    public static String packageName(Context c) {
        if (sPackage != null) return sPackage;
        try {
            ResolveInfo ri = c.getPackageManager()
                    .resolveService(new Intent(ACTION_API_COMMAND), 0);
            if (ri != null && ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                sPackage = ri.serviceInfo.packageName;
                return sPackage;
            }
        } catch (Throwable ignored) {}
        sPackage = DEFAULT_PACKAGE;
        return sPackage;
    }

    /** Poweramp build number, or -1 if not installed / not resolvable. */
    public static int build(Context c) {
        if (sBuild != -1) return sBuild;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(packageName(c), 0);
            sBuild = pi.versionCode > 1000 ? pi.versionCode / 1000 : pi.versionCode;
        } catch (Throwable t) {
            sBuild = -1;
        }
        return sBuild;
    }

    public static boolean isInstalled(Context c) {
        return build(c) != -1;
    }

    // ---------------------------------------------------------------- outputs

    /**
     * Output A. Build 1025 is well past 855, so the correct dispatch is an explicit
     * broadcast to PowerampAPIReceiver -- not startService.
     *
     * v0.8: FLAG_INCLUDE_STOPPED_PACKAGES. The head unit's shutdown cleanup can leave
     * Poweramp force-stopped (seen after a 2-day sit), and Android silently drops every
     * broadcast to a stopped app -- even an explicitly addressed one -- unless this flag
     * is set. With it, a wheel press reaches Poweramp and starts it. It changes nothing
     * when Poweramp is already running.
     */
    public static void sendApiCommand(Context c, int cmd) {
        Intent i = new Intent(ACTION_API_COMMAND)
                .setComponent(new ComponentName(packageName(c), API_RECEIVER))
                .putExtra(EXTRA_COMMAND, cmd)
                .putExtra(EXTRA_PACKAGE, c.getPackageName())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        c.sendBroadcast(i);
    }

    /**
     * Outputs B and C. Explicitly addressed at Poweramp's own media button receiver,
     * so there is no MediaSession priority contention and no risk of the event
     * looping back into this app.
     */
    public static void sendMediaButton(Context c, KeyEvent ke) {
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(new ComponentName(packageName(c), MEDIA_BUTTON_RECEIVER))
                .putExtra(Intent.EXTRA_KEY_EVENT, ke)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);  // see sendApiCommand
        c.sendBroadcast(i);
    }

    /** Output D. Goes to whichever session the framework treats as highest priority. */
    public static void dispatchGlobal(Context c, KeyEvent ke) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.dispatchMediaKeyEvent(ke);
    }
}
