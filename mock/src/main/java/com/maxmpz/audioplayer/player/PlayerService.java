package com.maxmpz.audioplayer.player;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.maxmpz.audioplayer.MockLog;

/**
 * Exists mainly so PackageManager.resolveService(ACTION_API_COMMAND) succeeds --
 * that is how SWC Bridge discovers Poweramp's package name at runtime, and it is
 * worth exercising rather than assuming.
 *
 * Also catches the legacy pre-855 startService dispatch path if anything uses it.
 */
public class PlayerService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int cmd = intent.getIntExtra("cmd", -1);
            MockLog.add("API_COMMAND  cmd=" + cmd + " (" + MockLog.cmdName(cmd) + ")"
                    + "   <- PlayerService (legacy path)");
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
