package com.mattc.swcbridge;

import android.content.Context;
import android.media.AudioManager;

/**
 * Reads the head unit's private audio-routing parameters.
 *
 * Decompiled MTK/TS head-unit firmware gates the media-key broadcast on the active
 * audio source: the vendor code checks av_channel and only emits a global media key
 * when Android ("sys") is the current source. If this unit does the same, then after
 * a long sit it may boot with the source set to something else, MainUI would still
 * drive its own player over AIDL, and no broadcast would ever reach us -- until
 * launching an Android app switches the source over.
 *
 * That is a different failure from the app being force-stopped, and the two want
 * different fixes, so it is worth logging the value rather than reasoning about it.
 */
public final class VendorInfo {

    /** Vendor keys seen in this firmware family; unknown ones just return empty. */
    private static final String[] KEYS = {
            "av_channel=",
            "audio_channel=",
            "media_state=",
            "routing=",
    };

    private VendorInfo() {}

    public static void log(Context ctx, String why) {
        Context c = ctx.getApplicationContext();
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        StringBuilder sb = new StringBuilder("vendor audio [" + why + "]");
        boolean anything = false;

        for (String k : KEYS) {
            String v = null;
            try { v = am.getParameters(k); } catch (Throwable ignored) {}
            if (v != null && v.trim().length() > 0) {
                sb.append("  ").append(v.trim());
                anything = true;
            }
        }

        try {
            sb.append("  musicActive=").append(am.isMusicActive());
        } catch (Throwable ignored) {}

        if (!anything) sb.append("  (no vendor keys readable)");
        EventLog.add(sb.toString());
    }
}
