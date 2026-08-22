package com.graphicmiles.screenrecorder.recording;

public class TripleTapController {
    private final long timeWindowMs;
    private int tapCount = 0;
    private long firstTapAtMs = 0L;

    public TripleTapController() {
        this(700L);
    }

    public TripleTapController(long timeWindowMs) {
        this.timeWindowMs = timeWindowMs;
    }

    public boolean registerTap(long tapTimeMs) {
        if (tapCount == 0 || tapTimeMs - firstTapAtMs > timeWindowMs) {
            tapCount = 1;
            firstTapAtMs = tapTimeMs;
            return false;
        }

        tapCount += 1;
        if (tapCount >= 3) {
            reset();
            return true;
        }
        return false;
    }

    public void reset() {
        tapCount = 0;
        firstTapAtMs = 0L;
    }
}
