package com.mattc.swcbridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The core of the bridge.
 *
 * The head unit (4x4shop MRW_LEXUS, MCU LEXUS_V5.0_0321) injects a virtual
 * KEYCODE_MEDIA_NEXT / KEYCODE_MEDIA_PREVIOUS with ACTION_DOWN and never sends the
 * matching ACTION_UP. Poweramp waits for the release to distinguish single / double /
 * triple / long press, so the press never completes.
 *
 * This class acts on the DOWN and dispatches something Poweramp can actually process.
 */
public final class KeyRouter {

    /** Guards against a synthetic event we emitted coming straight back to us. */
    private static long sSuppressUntil = 0L;

    /** Last time we acted, per keycode, for debouncing. */
    private static final Map<Integer, Long> LAST_ACTION = new HashMap<>();

    /** Latest Poweramp playback state, from the sticky STATUS_CHANGED broadcast. */
    private static volatile int sPowerampState = Poweramp.STATE_NO_STATE;

    /**
     * Upper bound on the duplicate-event guard for a key that carries a double-press
     * gesture. The guard exists to reject the *same* event arriving twice (both input
     * paths delivering it, say); it must never be long enough to swallow a deliberate
     * second press, which the user's 400ms setting otherwise would.
     */
    private static final int DUPLICATE_GUARD_MS = 120;

    // Double-press state. Everything here is touched only from the main thread:
    // BroadcastReceiver.onReceive and MediaSession.Callback both run there.
    private static final Handler GESTURE = new Handler(Looper.getMainLooper());
    private static int sPendingCmd = 0;
    private static long sPendingAt = 0L;
    private static Runnable sPendingRunnable;

    private KeyRouter() {}

    public static void setPowerampState(int state) { sPowerampState = state; }

    /** v0.9: true while a single press is being held back for the double-press window. */
    public static boolean gesturePending() { return sPendingRunnable != null; }
    public static int powerampState() { return sPowerampState; }

    public static String stateName(int s) {
        switch (s) {
            case Poweramp.STATE_STOPPED: return "stopped";
            case Poweramp.STATE_PLAYING: return "playing";
            case Poweramp.STATE_PAUSED:  return "paused";
            default:                     return "unknown";
        }
    }

    /**
     * @param source human-readable name of the input path that caught this event
     * @return true if we consumed the event
     */
    public static boolean handle(Context ctx, String source, KeyEvent ke) {
        Context c = ctx.getApplicationContext();

        if (ke == null) {
            EventLog.add("[" + source + "] intent with no KeyEvent extra");
            return false;
        }

        int code = ke.getKeyCode();
        boolean down = ke.getAction() == KeyEvent.ACTION_DOWN;
        String desc = "[" + source + "] " + KeyEvent.keyCodeToString(code)
                + " " + (down ? "ACTION_DOWN" : "ACTION_UP")
                + " repeat=" + ke.getRepeatCount()
                + " src=0x" + Integer.toHexString(ke.getSource())
                + (ke.getDeviceId() == -1 ? " virt=true" : " devId=" + ke.getDeviceId());

        long now = SystemClock.uptimeMillis();

        if (now < sSuppressUntil) {
            EventLog.add(desc + "  -> ignored (echo of our own synthetic event)");
            return false;
        }

        int cmd = toPowerampCommand(code);
        if (cmd == 0) {
            EventLog.add(desc + "  -> not a transport key, ignored");
            return false;
        }

        if (!Prefs.enabled(c)) {
            EventLog.add(desc + "  -> bridge disabled");
            return false;
        }

        // Act on the press, not the release. This is the entire fix.
        boolean actOnDown = Prefs.actOnDown(c);
        if (actOnDown && !down) {
            EventLog.add(desc + "  -> ignored (acting on DOWN)");
            return false;
        }
        if (!actOnDown && down) {
            EventLog.add(desc + "  -> ignored (acting on UP)");
            return false;
        }

        if (Prefs.onlyWhenPlaying(c) && sPowerampState == Poweramp.STATE_NO_STATE) {
            // v0.9 lean mode: no service is running to track Poweramp, so read its sticky
            // status broadcast directly (registerReceiver with a null receiver just
            // returns the sticky intent, and is allowed from a receiver's context).
            try {
                Intent st = c.registerReceiver(null,
                        new IntentFilter(Poweramp.ACTION_STATUS_CHANGED));
                if (st != null) {
                    sPowerampState = st.getIntExtra(Poweramp.EXTRA_STATE, Poweramp.STATE_NO_STATE);
                }
            } catch (Throwable ignored) {}
        }
        if (Prefs.onlyWhenPlaying(c) && sPowerampState != Poweramp.STATE_PLAYING) {
            EventLog.add(desc + "  -> skipped, Poweramp is " + stateName(sPowerampState));
            return false;
        }

        // The 4x4shop MRW_LEXUS MCU has its seek-up / seek-down key table reversed:
        // pressing "next" on the wheel emits KEYCODE_MEDIA_PREVIOUS. Correct it here
        // rather than in the mapping table, so the log still shows the raw keycode
        // the hardware actually sent. This runs before the debounce so the gesture
        // check below sees the command the user actually meant.
        //
        // v0.8: only the steering wheel is reversed. Its presses arrive as VIRTUAL key
        // events (deviceId -1, "virt=true" in the log). Real input devices -- the USB
        // wireless remote (devId=5, src=0x301) -- send the right keycode already, and
        // v0.7 was wrongly swapping those too, so the remote's NEXT went backwards.
        boolean fromWheel = ke.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD;
        int outCmd = cmd;
        int outCode = code;
        boolean swapped = false;
        if (Prefs.swapNextPrev(c) && fromWheel) {
            if (cmd == Poweramp.CMD_NEXT) {
                outCmd = Poweramp.CMD_PREVIOUS;
                outCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                swapped = true;
            } else if (cmd == Poweramp.CMD_PREVIOUS) {
                outCmd = Poweramp.CMD_NEXT;
                outCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                swapped = true;
            }
        }

        int gestureCmd = Prefs.gestureCommand(c);
        boolean isGestureKey = gestureCmd != 0 && outCmd == gestureCmd;

        int debounce = Prefs.debounceMs(c);
        if (isGestureKey) debounce = Math.min(debounce, DUPLICATE_GUARD_MS);
        Long last = LAST_ACTION.get(code);
        if (last != null && now - last < debounce) {
            EventLog.add(desc + "  -> debounced (" + (now - last) + "ms < " + debounce + "ms)");
            return true;
        }
        LAST_ACTION.put(code, now);

        if (isGestureKey) {
            handleDoublePress(c, desc, swapped, outCmd, outCode, now);
            return true;
        }

        int mode = Prefs.outputMode(c);
        String what = dispatch(c, mode, outCode, outCmd);
        EventLog.add(desc + "  -> " + (swapped ? "[swapped] " : "") + what);
        maybeTopUpVolume(c, outCmd);
        return true;
    }

    /**
     * v0.9.2: after a NEXT has been sent to Poweramp, top up Android's media volume.
     * Runs after the track command, and can never stop the key handling: any failure is
     * caught and logged.
     */
    private static void maybeTopUpVolume(Context c, int sentCmd) {
        if (sentCmd != Poweramp.CMD_NEXT || !Prefs.volumeOnNext(c)) return;
        try {
            VolumeControl.topUp(c, "NEXT");
        } catch (Throwable t) {
            EventLog.add("! volume [NEXT] top-up failed: " + t);
        }
    }

    /**
     * Double-press on one transport key means play/pause.
     *
     * The cost is unavoidable and worth stating plainly: to know whether a press is a
     * single or the first half of a double, the single-press action has to be held back
     * for the length of the window. Only the key carrying the gesture pays it.
     *
     * Note this cannot be done as a long-press instead. A long press needs the release
     * to measure duration, and this head unit never sends ACTION_UP at all.
     */
    private static void handleDoublePress(final Context c, String desc, final boolean swapped,
                                          final int outCmd, final int outCode, long now) {
        final int window = Prefs.doubleWindowMs(c);

        if (sPendingCmd == outCmd && now - sPendingAt <= window) {
            // Second press inside the window. The single-press action never fired.
            if (sPendingRunnable != null) GESTURE.removeCallbacks(sPendingRunnable);
            sPendingRunnable = null;
            sPendingCmd = 0;
            String what = dispatch(c, Prefs.outputMode(c),
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, Poweramp.CMD_TOGGLE_PLAY_PAUSE);
            EventLog.add(desc + "  -> [double press] " + what);
            return;
        }

        sPendingCmd = outCmd;
        sPendingAt = now;
        sPendingRunnable = new Runnable() {
            @Override public void run() {
                sPendingCmd = 0;
                sPendingRunnable = null;
                String what = dispatch(c, Prefs.outputMode(c), outCode, outCmd);
                EventLog.add("           ... no second press -> "
                        + (swapped ? "[swapped] " : "") + what);
                maybeTopUpVolume(c, outCmd);
            }
        };
        GESTURE.postDelayed(sPendingRunnable, window);

        // The delayed action needs this process to still be alive when it fires.
        // v0.9: in lean mode, hold the process past the window; with "Keep bridge
        // running" on, the foreground service guarantees it (no-op if already up).
        if (Lean.on(c)) {
            Lean.holdFor(c, window + Lean.KEY_LINGER_MS);
        } else {
            BridgeService.start(c);
        }

        EventLog.add(desc + "  -> armed " + Poweramp.cmdName(outCmd)
                + ", waiting " + window + "ms for a second press");
    }

    /** Fires an output without an incoming key event. Used by the on-screen test buttons. */
    public static void fireCommand(Context ctx, int cmd, String why) {
        Context c = ctx.getApplicationContext();
        int code = toKeyCode(cmd);
        String what = dispatch(c, Prefs.outputMode(c), code, cmd);
        EventLog.add("[" + why + "] " + Poweramp.cmdName(cmd) + " -> " + what);
    }

    private static String dispatch(Context c, int mode, int keyCode, int cmd) {
        try {
            switch (mode) {
                case Prefs.OUT_PA_API: {
                    Poweramp.sendApiCommand(c, cmd);
                    return "A: Poweramp API " + Poweramp.cmdName(cmd)
                            + " -> " + Poweramp.packageName(c);
                }
                case Prefs.OUT_PA_MEDIA_BUTTON: {
                    suppress();
                    long t = SystemClock.uptimeMillis();
                    Poweramp.sendMediaButton(c, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
                    Poweramp.sendMediaButton(c, new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, keyCode, 0));
                    return "B: DOWN+UP " + KeyEvent.keyCodeToString(keyCode)
                            + " -> PSMediaButtonReceiver";
                }
                case Prefs.OUT_PA_UP_ONLY: {
                    suppress();
                    long t = SystemClock.uptimeMillis();
                    Poweramp.sendMediaButton(c, new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
                    return "C: missing ACTION_UP " + KeyEvent.keyCodeToString(keyCode)
                            + " -> PSMediaButtonReceiver";
                }
                case Prefs.OUT_GLOBAL_MEDIA_KEY: {
                    suppress();
                    long t = SystemClock.uptimeMillis();
                    Poweramp.dispatchGlobal(c, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
                    Poweramp.dispatchGlobal(c, new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, keyCode, 0));
                    return "D: global media key " + KeyEvent.keyCodeToString(keyCode);
                }
                default:
                    return "unknown output mode " + mode;
            }
        } catch (Throwable t) {
            return "FAILED: " + t;
        }
    }

    /** Blocks our own synthetic events from re-entering the router. */
    private static void suppress() {
        sSuppressUntil = SystemClock.uptimeMillis() + 500;
    }

    public static int toPowerampCommand(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return Poweramp.CMD_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return Poweramp.CMD_PREVIOUS;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return Poweramp.CMD_TOGGLE_PLAY_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return Poweramp.CMD_RESUME;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return Poweramp.CMD_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return Poweramp.CMD_STOP;
            default:
                return 0;
        }
    }

    public static int toKeyCode(int cmd) {
        switch (cmd) {
            case Poweramp.CMD_NEXT:     return KeyEvent.KEYCODE_MEDIA_NEXT;
            case Poweramp.CMD_PREVIOUS: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case Poweramp.CMD_RESUME:   return KeyEvent.KEYCODE_MEDIA_PLAY;
            case Poweramp.CMD_PAUSE:    return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case Poweramp.CMD_STOP:     return KeyEvent.KEYCODE_MEDIA_STOP;
            default:                    return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        }
    }
}
