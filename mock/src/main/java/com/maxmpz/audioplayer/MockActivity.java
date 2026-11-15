package com.maxmpz.audioplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shows everything the fake Poweramp receivers were handed, and can announce a
 * playback state so SWC Bridge's "only act while Poweramp is playing" gate can be
 * exercised too.
 */
public class MockActivity extends Activity {

    private TextView mLog;
    private ScrollView mScroll;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private String mLastRendered = "";

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            String now = MockLog.dump();
            if (!now.equals(mLastRendered)) {
                mLastRendered = now;
                mLog.setText(now);
                mScroll.post(new Runnable() {
                    @Override public void run() { mScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
            mHandler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mock);

        mLog    = findViewById(R.id.log);
        mScroll = findViewById(R.id.scroll);

        ((TextView) findViewById(R.id.subtitle)).setText(
                "package " + getPackageName() + ", posing as build 1025.\n"
                        + "Receives what SWC Bridge sends, so the dispatch chain can be "
                        + "verified without the real (ARM-only) Poweramp APK.");

        findViewById(R.id.btnPlaying).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { announce(1, "PLAYING"); }
        });
        findViewById(R.id.btnPaused).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { announce(2, "PAUSED"); }
        });
        ((Button) findViewById(R.id.btnClear)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { MockLog.clear(); }
        });
    }

    /**
     * Real Poweramp sends STATUS_CHANGED as a sticky broadcast, so a listener that
     * registers later still gets the current state. Reproduced here.
     */
    @SuppressWarnings("deprecation")
    private void announce(int state, String name) {
        Intent i = new Intent("com.maxmpz.audioplayer.STATUS_CHANGED")
                .putExtra("state", state)
                .putExtra("paused", state == 2)
                .putExtra("ts", System.currentTimeMillis());
        sendStickyBroadcast(i);
        MockLog.add("--> announced STATUS_CHANGED state=" + state + " (" + name + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        super.onPause();
    }
}
