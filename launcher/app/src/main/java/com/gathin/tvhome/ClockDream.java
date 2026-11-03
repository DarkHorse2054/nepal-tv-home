package com.gathin.tvhome;

import android.service.dreams.DreamService;

/** Full-screen mechanical clock. Any remote key dismisses the screensaver. */
public class ClockDream extends DreamService {

    private MechanicalClockSurfaceView clock;
    private boolean dreaming;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        releaseClock();
        setContentView(R.layout.dream_clock);
        clock = findViewById(R.id.dreamClock);
        clock.setOnRenderingFailure(this::finish);
        if (dreaming) clock.startClock();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (clock != null) clock.startClock();
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        if (clock != null) clock.stopClock();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        dreaming = false;
        releaseClock();
        super.onDetachedFromWindow();
    }

    @Override
    public void onDestroy() {
        dreaming = false;
        releaseClock();
        super.onDestroy();
    }

    private void releaseClock() {
        if (clock == null) return;
        clock.stopClock();
        clock.releaseResources();
        clock = null;
    }
}
