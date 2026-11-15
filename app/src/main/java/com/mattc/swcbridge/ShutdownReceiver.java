package com.mattc.swcbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * v0.8 diagnostic, retired in v0.9 and NO LONGER REGISTERED in the manifest.
 *
 * v0.8 proved the app never receives Android's shutdown broadcast: the head unit kills
 * it first. In v0.9 lean mode the app is normally not running at switch-off, and a
 * registered shutdown receiver would make Android START it at exactly that moment --
 * the one time it must not be running. Kept only so the file is not orphaned; safe to
 * delete from the project.
 */
public class ShutdownReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Intentionally empty (not registered).
    }
}
