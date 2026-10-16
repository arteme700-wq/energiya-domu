package ua.sister.energy;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** Звук тривоги лише на планшеті: кілька повторів, не безкінечно. */
final class AlertSound {
    private static final int REPEATS = 3, TONE_MS = 6000, GAP_MS = 20000;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ToneGenerator tg;
    private int left;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (left-- <= 0) { release(); return; }
            tone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, TONE_MS);
            h.postDelayed(this, TONE_MS + GAP_MS);
        }
    };

    void alarm() { stop(); left = REPEATS; h.post(step); }

    /** Короткий сигнал «відбій». */
    void allClear() { stop(); tone(ToneGenerator.TONE_PROP_ACK, 1500); h.postDelayed(this::release, 2500); }

    void stop() { h.removeCallbacksAndMessages(null); left = 0; if (tg != null) tg.stopTone(); release(); }

    boolean playing() { return left > 0; }

    private void tone(int t, int ms) {
        try { if (tg == null) tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100); tg.startTone(t, ms); } catch (Exception ignored) { }
    }

    private void release() { if (tg != null) { try { tg.release(); } catch (Exception ignored) { } tg = null; } }
}
