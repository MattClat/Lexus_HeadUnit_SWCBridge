package com.mattc.swcbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Foreground service holding INPUT PATH 2 -- our own MediaSession.
 *
 * On API 24, MediaSessionService routes an injected media key to the highest priority
 * active session. A session jumps to the front of the stack when it transitions into a
 * priority state, and STATE_SKIPPING_TO_NEXT is an "always priority" state -- which is
 * also why Poweramp reclaims the front on every track change. So if the re-assert option
 * is on, we periodically bounce through SKIPPING_TO_NEXT to take it back.
 */
public class BridgeService extends Service {

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "swcbridge";
    private static final long REASSERT_INTERVAL_MS = 15000L;
    /** v0.8: how often the "last seen alive" marker is refreshed (see RunHistory). */
    private static final long HEARTBEAT_INTERVAL_MS = 30000L;

    /** Startup volume attempts run at 0/3/10/30 s; the ramp counts as done after this. */
    private static final long STARTUP_DONE_MS = 31000L;

    private static volatile boolean sRunning = false;
    private static BridgeService sInstance;

    /** True between onCreate and onDestroy of the service in this process. */
    public static boolean isRunning() { return sRunning; }

    /** v0.9: startup ramp still in progress in this service instance. */
    private boolean mStartupPending = false;
    private boolean mForeground = false;

    private MediaSession mSession;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Separate from mHandler, which gets cleared whenever the session is reconfigured. */
    private final Handler mVolumeHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mStatusReceiver;
    private BroadcastReceiver mPowerReceiver;
    private final Handler mHeartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHeartbeat = new Runnable() {
        @Override public void run() {
            RunHistory.heartbeat(BridgeService.this);
            mHeartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    public static void start(Context c) {
        try {
            c.startService(new Intent(c, BridgeService.class));
        } catch (Throwable t) {
            EventLog.add("! could not start service: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        sInstance = this;
        watchPowerampStatus();
        EventLog.add("bridge service started, " + (Lean.on(this)
                ? "lean mode (will stop once startup is done)"
                : "keep-running mode") + " (" + Diagnostics.oneLine(this) + ")");
        applyMode();

        // v0.9: the startup volume ramp runs once per boot. In lean mode that used to
        // be the service's only reason to exist at startup; after it, the service stops
        // and the process ends (see Lean).
        if (RunHistory.startupDoneThisBoot(this)) {
            EventLog.add("startup volume already done this boot");
        } else {
            mStartupPending = true;
            VolumeControl.scheduleBootRamp(this, mVolumeHandler);
            mVolumeHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    mStartupPending = false;
                    RunHistory.markStartupDone(BridgeService.this);
                    maybeStopLean();
                }
            }, Prefs.volumeAtBoot(this) ? STARTUP_DONE_MS : 0L);
        }

        watchPowerEvents();
        mHeartbeatHandler.post(mHeartbeat);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // START_STICKY restart: the process was killed (not force-stopped -- that
            // would have cancelled this) and Android brought the service back.
            SwcApp.entered("system restarted the service after the process was killed");
            EventLog.add("bridge service restarted by the system (sticky restart)");
        } else {
            SwcApp.entered("service start request");
        }
        applyMode();
        maybeStopLean();
        // Lean mode must never be restarted behind our back: that would make the app a
        // running app again at switch-off.
        return Lean.on(this) ? START_NOT_STICKY : START_STICKY;
    }

    /**
     * v0.9: puts the service into the current mode. Keep-running = v0.8 behaviour
     * (foreground notification, own MediaSession if enabled, 15-minute watchdog).
     * Lean = none of those.
     */
    private void applyMode() {
        if (Lean.on(this)) {
            if (mForeground) {
                stopForeground(true);
                mForeground = false;
            }
            WatchdogReceiver.cancel(this);
            releaseSession();
        } else {
            if (!mForeground) {
                startForegroundCompat();
                mForeground = true;
            }
            WatchdogReceiver.schedule(this);
            applySessionState();
        }
    }

    /** In lean mode, stop once startup is done and the config screen is not showing. */
    private void maybeStopLean() {
        if (Lean.on(this) && !mStartupPending && !Lean.uiVisible()) {
            EventLog.add("bridge service stopping (lean mode: startup done, screen closed)");
            stopSelf();
        }
    }

    /** Called by Lean when the config screen goes away. */
    static void onUiHidden() {
        BridgeService s = sInstance;
        if (s != null) s.maybeStopLean();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        sInstance = null;
        mHandler.removeCallbacksAndMessages(null);
        mVolumeHandler.removeCallbacksAndMessages(null);
        mHeartbeatHandler.removeCallbacksAndMessages(null);
        if (mPowerReceiver != null) {
            try { unregisterReceiver(mPowerReceiver); } catch (Throwable ignored) {}
            mPowerReceiver = null;
        }
        releaseSession();
        if (mStatusReceiver != null) {
            try { unregisterReceiver(mStatusReceiver); } catch (Throwable ignored) {}
            mStatusReceiver = null;
        }
        EventLog.add("bridge service stopped");
        super.onDestroy();
        Lean.holdFor(this, Lean.SERVICE_LINGER_MS);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------- media session

    /** Call after any settings change so the session comes up or goes away to match. */
    public void applySessionState() {
        // v0.9: the session only makes sense in a process that stays running.
        if (Prefs.inputSession(this) && !Lean.on(this)) {
            acquireSession();
        } else {
            releaseSession();
        }
    }

    private void acquireSession() {
        if (mSession == null) {
            mSession = new MediaSession(this, "SwcBridge");
            mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    KeyRouter.handle(BridgeService.this, "session", ke);
                    return true;
                }
                @Override public void onSkipToNext() {
                    KeyRouter.fireCommand(BridgeService.this, Poweramp.CMD_NEXT, "transport");
                }
                @Override public void onSkipToPrevious() {
                    KeyRouter.fireCommand(BridgeService.this, Poweramp.CMD_PREVIOUS, "transport");
                }
                @Override public void onPlay() {
                    KeyRouter.fireCommand(BridgeService.this, Poweramp.CMD_RESUME, "transport");
                }
                @Override public void onPause() {
                    KeyRouter.fireCommand(BridgeService.this, Poweramp.CMD_PAUSE, "transport");
                }
            });
            mSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "SWC Bridge")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "steering wheel -> Poweramp")
                    .build());
            mSession.setActive(true);
            EventLog.add("media session acquired");
        }
        assertPriority("initial");
        scheduleReassert();
    }

    private void releaseSession() {
        mHandler.removeCallbacksAndMessages(null);
        if (mSession != null) {
            try {
                mSession.setActive(false);
                mSession.release();
            } catch (Throwable ignored) {}
            mSession = null;
            EventLog.add("media session released");
        }
    }

    /**
     * Bounce through an "always priority" state to move to the front of the
     * media button stack, then settle back on PLAYING.
     */
    private void assertPriority(String why) {
        if (mSession == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            mSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(PlaybackState.STATE_SKIPPING_TO_NEXT, 0, 1.0f)
                    .build());
            mSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build());
        } catch (Throwable t) {
            EventLog.add("! priority assert failed (" + why + "): " + t);
        }
    }

    private void scheduleReassert() {
        mHandler.removeCallbacksAndMessages(null);
        if (!Prefs.sessionReassert(this)) return;
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mSession != null && Prefs.sessionReassert(BridgeService.this)) {
                    assertPriority("periodic");
                    scheduleReassert();
                }
            }
        }, REASSERT_INTERVAL_MS);
    }

    // ------------------------------------------------------------- poweramp status

    /**
     * STATUS_CHANGED is sticky, so registering returns Poweramp's current state
     * immediately -- no polling needed for the initial value.
     */
    private void watchPowerampStatus() {
        mStatusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int state = intent.getIntExtra(Poweramp.EXTRA_STATE, Poweramp.STATE_NO_STATE);
                if (state != KeyRouter.powerampState()) {
                    KeyRouter.setPowerampState(state);
                    EventLog.add("poweramp state -> " + KeyRouter.stateName(state));
                }
            }
        };
        IntentFilter f = new IntentFilter(Poweramp.ACTION_STATUS_CHANGED);
        Intent sticky = registerReceiver(mStatusReceiver, f);
        if (sticky != null) {
            KeyRouter.setPowerampState(
                    sticky.getIntExtra(Poweramp.EXTRA_STATE, Poweramp.STATE_NO_STATE));
        }
    }

    // ------------------------------------------------------------- power events

    /**
     * v0.8 diagnostics. Screen and charger changes can only be received by a running
     * app (they are not deliverable to manifest receivers), and they are the closest
     * thing Android sees to "the car was switched off". Logging them gives a timeline
     * of the shutdown sequence -- and if the log simply stops after "screen off", that
     * pins down when the unit killed us.
     */
    private void watchPowerEvents() {
        // (POWER_CONNECTED is already logged by BootReceiver via the manifest.)
        mPowerReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String a = intent == null ? "null" : String.valueOf(intent.getAction());
                String what;
                if (Intent.ACTION_SCREEN_OFF.equals(a))              what = "screen OFF";
                else if (Intent.ACTION_SCREEN_ON.equals(a))          what = "screen on";
                else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) what = "power disconnected";
                else                                                 what = a;
                EventLog.add(what + " (uptime "
                        + Diagnostics.duration(SystemClock.elapsedRealtime()) + ")");
                RunHistory.heartbeat(BridgeService.this);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        try {
            registerReceiver(mPowerReceiver, f);
        } catch (Throwable t) {
            EventLog.add("! could not watch power events: " + t);
            mPowerReceiver = null;
        }
    }

    // ------------------------------------------------------------- notification

    @SuppressWarnings("deprecation")
    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            CHANNEL, "SWC Bridge", NotificationManager.IMPORTANCE_LOW));
                }
            }

            // FLAG_IMMUTABLE is mandatory for apps targeting S+; harmless below that,
            // and future-proofs this if targetSdk is ever raised.
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), piFlags);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);

            Notification n = b.setContentTitle("SWC Bridge")
                    .setContentText("Listening for steering wheel buttons")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIF_ID, n);
        } catch (Throwable t) {
            EventLog.add("! foreground start failed: " + t);
        }
    }
}
