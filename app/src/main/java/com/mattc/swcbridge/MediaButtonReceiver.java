package com.mattc.swcbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * INPUT PATH 1 -- manifest receiver for ACTION_MEDIA_BUTTON at maximum intent-filter
 * priority (2147483647), so we sort ahead of Poweramp's own PSMediaButtonReceiver at 999.
 *
 * This is the path the steering wheel actually uses on the MRW_LEXUS: the vendor app
 * broadcasts the (DOWN-only, virtual) media button directly. Because it is a manifest
 * receiver, Android starts our process for it on demand -- it needs no running service,
 * only that the app has not been force-stopped. v0.9 lean mode is built on that.
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SwcApp.entered("media button broadcast");
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;

        if (!Prefs.inputReceiver(context)) {
            EventLog.add("[broadcast] media button seen but this input path is off");
            return;
        }

        KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        boolean consumed = KeyRouter.handle(context, "broadcast", ke);

        // If we acted on it, stop it reaching Poweramp as the broken half-press.
        if (consumed && isOrderedBroadcast()) {
            abortBroadcast();
        }

        if (Lean.on(context)) {
            // Stay up long enough for a follow-up press (and the double-press window),
            // then end the process so nothing of ours is running at switch-off.
            Lean.holdFor(context, Lean.KEY_LINGER_MS);
        } else if (!BridgeService.isRunning()) {
            BridgeService.start(context);
        }
    }
}
