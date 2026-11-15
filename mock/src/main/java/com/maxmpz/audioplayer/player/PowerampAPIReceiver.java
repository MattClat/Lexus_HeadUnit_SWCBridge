package com.maxmpz.audioplayer.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.maxmpz.audioplayer.MockLog;

/**
 * Stands in for Poweramp's real API receiver. Output mode A in SWC Bridge lands here.
 * A line appearing here means the bridge produced a correctly addressed, correctly
 * shaped command -- which is the whole thing we want to prove at a desk.
 */
public class PowerampAPIReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int cmd = intent.getIntExtra("cmd", -1);
        String from = intent.getStringExtra("pak");
        MockLog.add("API_COMMAND  cmd=" + cmd + " (" + MockLog.cmdName(cmd) + ")"
                + (from != null ? "  from=" + from : "")
                + "   <- PowerampAPIReceiver");
    }
}
