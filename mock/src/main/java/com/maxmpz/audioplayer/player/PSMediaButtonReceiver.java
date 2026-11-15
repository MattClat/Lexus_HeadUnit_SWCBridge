package com.maxmpz.audioplayer.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import com.maxmpz.audioplayer.MockLog;

/**
 * Stands in for Poweramp's real media button receiver (priority 999 in the real
 * manifest). Output modes B and C land here.
 *
 * The log line prints the action, so you can see for yourself whether an
 * ACTION_UP ever arrives -- which is exactly the thing the head unit fails to send.
 */
public class PSMediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ke == null) {
            MockLog.add("MEDIA_BUTTON with no KeyEvent   <- PSMediaButtonReceiver");
            return;
        }
        MockLog.add("MEDIA_BUTTON  " + KeyEvent.keyCodeToString(ke.getKeyCode())
                + "  " + (ke.getAction() == KeyEvent.ACTION_DOWN ? "ACTION_DOWN" : "ACTION_UP")
                + "  repeat=" + ke.getRepeatCount()
                + "   <- PSMediaButtonReceiver");
    }
}
