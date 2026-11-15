package com.mattc.swcbridge;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The whole config surface, designed to be usable from the driver's seat.
 * There is deliberately nothing here that needs a laptop or adb.
 */
public class MainActivity extends Activity implements EventLog.Listener {

    private TextView mStatus, mLog, mDebounceLabel, mWindowLabel, mVolumeLabel;
    private ScrollView mLogScroll;
    private final Handler mUi = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SwcApp.entered("user opened the app");
        // Mark the screen visible BEFORE starting the service, so a lean-mode service
        // doesn't decide it is unneeded and stop straight away.
        Lean.setUiVisible(this, true);
        setContentView(R.layout.activity_main);
        setTitle("SWC Bridge  " + Diagnostics.appVersion(this));
        EventLog.reloadFromFile();

        BridgeService.start(this);

        mStatus        = findViewById(R.id.status);
        mLog           = findViewById(R.id.log);
        mLogScroll     = findViewById(R.id.logScroll);
        mDebounceLabel = findViewById(R.id.tvDebounce);
        mWindowLabel   = findViewById(R.id.tvWindow);
        mVolumeLabel   = findViewById(R.id.tvVolume);

        bindSwitch(R.id.swEnabled,     Prefs.ENABLED,           Prefs.enabled(this),         false);
        bindSwitch(R.id.swKeepRunning, Prefs.KEEP_RUNNING,      Prefs.keepRunning(this),     true);
        bindSwitch(R.id.swLeanExit,    Prefs.LEAN_SELF_EXIT,    Prefs.leanSelfExit(this),    false);
        bindSwitch(R.id.swInReceiver,  Prefs.IN_RECEIVER,       Prefs.inputReceiver(this),   false);
        bindSwitch(R.id.swInSession,   Prefs.IN_SESSION,        Prefs.inputSession(this),    true);
        bindSwitch(R.id.swReassert,    Prefs.SESSION_REASSERT,  Prefs.sessionReassert(this), true);
        bindSwitch(R.id.swActOnDown,   Prefs.ACT_ON_DOWN,       Prefs.actOnDown(this),       false);
        bindSwitch(R.id.swSwap,        Prefs.SWAP_NEXT_PREV,    Prefs.swapNextPrev(this),    false);
        bindSwitch(R.id.swOnlyPlaying, Prefs.ONLY_WHEN_PLAYING, Prefs.onlyWhenPlaying(this), false);
        bindSwitch(R.id.swVolumeBoot,  Prefs.VOLUME_AT_BOOT,    Prefs.volumeAtBoot(this),    false);
        bindSwitch(R.id.swVolumeUi,    Prefs.VOLUME_SHOW_UI,    Prefs.volumeShowUi(this),    false);
        bindSwitch(R.id.swVolumeNext,  Prefs.VOLUME_ON_NEXT,    Prefs.volumeOnNext(this),    false);

        Spinner sp = findViewById(R.id.spOutput);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Prefs.OUTPUT_MODE_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Prefs.outputMode(this));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != Prefs.outputMode(MainActivity.this)) {
                    Prefs.put(MainActivity.this, Prefs.OUTPUT_MODE, pos);
                    EventLog.add("output mode -> " + Prefs.OUTPUT_MODE_NAMES[pos]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        Spinner spg = findViewById(R.id.spGesture);
        ArrayAdapter<String> gad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Prefs.GESTURE_NAMES);
        gad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spg.setAdapter(gad);
        spg.setSelection(Prefs.gestureMode(this));
        spg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != Prefs.gestureMode(MainActivity.this)) {
                    Prefs.put(MainActivity.this, Prefs.GESTURE_MODE, pos);
                    EventLog.add("gesture -> " + Prefs.GESTURE_NAMES[pos]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        final SeekBar sb = findViewById(R.id.sbDebounce);
        sb.setProgress(Prefs.debounceMs(this));
        updateDebounceLabel(Prefs.debounceMs(this));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                updateDebounceLabel(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                Prefs.put(MainActivity.this, Prefs.DEBOUNCE_MS, s.getProgress());
                EventLog.add("debounce -> " + s.getProgress() + "ms");
            }
        });

        // SeekBar has no setMin below API 26, so the floor is enforced on read.
        final SeekBar sbw = findViewById(R.id.sbWindow);
        sbw.setProgress(Prefs.doubleWindowMs(this));
        updateWindowLabel(Prefs.doubleWindowMs(this));
        sbw.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                updateWindowLabel(Math.max(150, v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int v = Math.max(150, s.getProgress());
                s.setProgress(v);
                Prefs.put(MainActivity.this, Prefs.DOUBLE_WINDOW_MS, v);
                EventLog.add("double-press window -> " + v + "ms");
            }
        });

        final SeekBar sbv = findViewById(R.id.sbVolume);
        sbv.setProgress(Prefs.volumePct(this));
        updateVolumeLabel(Prefs.volumePct(this));
        sbv.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                updateVolumeLabel(Math.max(10, v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int v = Math.max(10, s.getProgress());
                s.setProgress(v);
                Prefs.put(MainActivity.this, Prefs.VOLUME_PCT, v);
                EventLog.add("volume target -> " + v + "%");
            }
        });

        Spinner spv = findViewById(R.id.spVolumeMethod);
        ArrayAdapter<String> vad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Prefs.VOLUME_METHOD_NAMES);
        vad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spv.setAdapter(vad);
        spv.setSelection(Prefs.volumeMethod(this));
        spv.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != Prefs.volumeMethod(MainActivity.this)) {
                    Prefs.put(MainActivity.this, Prefs.VOLUME_METHOD, pos);
                    EventLog.add("volume method -> " + Prefs.VOLUME_METHOD_NAMES[pos]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        click(R.id.btnVolumeNow, new Runnable() { public void run() {
            VolumeControl.apply(MainActivity.this, "button");
            refreshStatus();
        } });

        click(R.id.btnVolumeDiag, new Runnable() { public void run() {
            VolumeControl.dumpStreams(MainActivity.this, "snapshot");
            refreshStatus();
        } });

        click(R.id.btnSafeVolume, new Runnable() { public void run() {
            VolumeControl.disableSafeLimit(MainActivity.this);
            refreshStatus();
        } });

        click(R.id.btnPrev,      new Runnable() { public void run() {
            KeyRouter.fireCommand(MainActivity.this, Poweramp.CMD_PREVIOUS, "test button"); } });
        click(R.id.btnNext,      new Runnable() { public void run() {
            KeyRouter.fireCommand(MainActivity.this, Poweramp.CMD_NEXT, "test button"); } });
        click(R.id.btnPlayPause, new Runnable() { public void run() {
            KeyRouter.fireCommand(MainActivity.this, Poweramp.CMD_TOGGLE_PLAY_PAUSE, "test button"); } });

        click(R.id.btnSimulate,     new Runnable() { public void run() { simulate(false); } });
        click(R.id.btnSimulateGood, new Runnable() { public void run() { simulate(true);  } });

        click(R.id.btnClear, new Runnable() { public void run() {
            File archived = EventLog.clearAndArchive(MainActivity.this);
            Toast.makeText(MainActivity.this,
                    archived == null ? "Log cleared" : "Log cleared -- old log saved as "
                            + archived.getName(),
                    Toast.LENGTH_LONG).show();
        } });
        click(R.id.btnSave,  new Runnable() { public void run() {
            File f = EventLog.save(MainActivity.this);
            Toast.makeText(MainActivity.this,
                    f == null ? "Save failed" : "Saved: " + f.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } });

        EventLog.add("--- config screen opened ---");
    }

    /**
     * Reproduces the head unit's fault on any device, including an emulator:
     * a virtual KEYCODE_MEDIA_NEXT with ACTION_DOWN and no matching ACTION_UP.
     * With the bridge off, nothing happens - exactly the symptom in the car.
     */
    private void simulate(boolean wellFormed) {
        long t = SystemClock.uptimeMillis();
        int code = KeyEvent.KEYCODE_MEDIA_NEXT;
        EventLog.add(wellFormed
                ? ">>> simulating a NORMAL press (DOWN + UP)"
                : ">>> simulating the HEAD UNIT BUG (DOWN only, no UP)");
        Poweramp.dispatchGlobal(this, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        if (wellFormed) {
            Poweramp.dispatchGlobal(this, new KeyEvent(t, t + 60, KeyEvent.ACTION_UP, code, 0));
        }
    }

    private void bindSwitch(int id, final String key, boolean initial, final boolean restartSession) {
        Switch s = findViewById(id);
        s.setChecked(initial);
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.put(MainActivity.this, key, checked);
                EventLog.add("setting " + key + " -> " + checked);
                if (restartSession) BridgeService.start(MainActivity.this);
                refreshStatus();
            }
        });
    }

    private void click(int id, final Runnable r) {
        Button b = findViewById(id);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
    }

    private void updateDebounceLabel(int v) {
        mDebounceLabel.setText(v + "ms");
    }

    private void updateWindowLabel(int v) {
        mWindowLabel.setText(v + "ms");
    }

    private void updateVolumeLabel(int v) {
        mVolumeLabel.setText(v + "%");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Lean.setUiVisible(this, true);
    }

    /**
     * Lean mode: leaving the screen finishes it, and the process then ends itself a
     * couple of seconds later (see Lean).
     *
     * v0.9.1: finish() ONLY. v0.9 used finishAndRemoveTask(), and on this head unit
     * removing an app's task force-stops the whole app -- exactly like swiping it out of
     * recent apps. Proven 26 Sep: the process died within milliseconds of the task
     * removal, before the service could even log that it had stopped, and Force stop was
     * greyed out afterwards. The (hidden) task is therefore left in place.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) return;
        Lean.setUiVisible(this, false);
        if (Lean.on(this) && !isFinishing()) {
            EventLog.add("config screen closed (lean mode; task kept -- removing it force-stops the app)");
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        EventLog.addListener(this);
        refreshStatus();
        renderLog();
    }

    @Override
    protected void onPause() {
        EventLog.removeListener(this);
        super.onPause();
    }

    private void refreshStatus() {
        Context c = this;
        String pa = Poweramp.isInstalled(c)
                ? Poweramp.packageName(c) + " build " + Poweramp.build(c)
                : "NOT INSTALLED";
        mStatus.setText("SWC Bridge " + Diagnostics.appVersion(c)
                + "   |   " + (Lean.on(c) ? "lean mode" : "keep-running mode")
                + "   |   uptime " + Diagnostics.duration(SystemClock.elapsedRealtime())
                + "\nPoweramp: " + pa
                + "\nstate: " + KeyRouter.stateName(KeyRouter.powerampState())
                + "   |   " + VolumeControl.summary(c)
                + "\n" + Diagnostics.oneLine(c));
    }

    @Override
    public void onLogChanged() {
        mUi.post(new Runnable() { @Override public void run() { renderLog(); } });
    }

    private void renderLog() {
        mLog.setText(EventLog.dump());
        mLogScroll.post(new Runnable() {
            @Override public void run() { mLogScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }
}
