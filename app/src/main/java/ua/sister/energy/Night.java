package ua.sister.energy;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Нічний режим (окрема функція — вимикається в налаштуваннях: «Нічний режим → вимкнено»).
 * Темний екран з тьмяним теплим годинником, мінімальна яскравість. Дотик — звичайний вигляд на 30 с.
 * Повітряна тривога скасовує нічний режим (яскраво, банер, звук).
 */
final class Night {
    private static final long PEEK_MS = 30000;
    private static final int WARM = 0xFF9A3412, WARM2 = 0xFF7C2D12;
    private final MainActivity a;
    private final FrameLayout overlay;
    private final LinearLayout box;
    private final TextView clock, l1, l2;
    private long peekUntil;
    private float lastBright = -2;

    Night(MainActivity a, FrameLayout root, float u) {
        this.a = a;
        overlay = new FrameLayout(a);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> { peekUntil = System.currentTimeMillis() + PEEK_MS; update(); });
        box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = new TextView(a); clock.setTextSize(0, 330 * u); clock.setTextColor(WARM); clock.setIncludeFontPadding(false);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        l1 = new TextView(a); l1.setTextSize(0, 50 * u); l1.setTextColor(WARM2); l1.setGravity(Gravity.CENTER);
        l2 = new TextView(a); l2.setTextSize(0, 40 * u); l2.setTextColor(WARM2); l2.setGravity(Gravity.CENTER);
        l2.setPadding(0, Math.round(10 * u), 0, 0);
        box.addView(clock); box.addView(l1); box.addView(l2);
        overlay.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    void bringToFront() { overlay.bringToFront(); }

    boolean isNightTime() {
        Prefs p = a.prefs;
        String mode = p.nightMode();
        if ("off".equals(mode)) return false;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int from = mins(p.nightFrom(), 22 * 60), to = mins(p.nightTo(), 7 * 60);
        if ("sun".equals(mode)) {
            Info.Weather w = a.weatherNow();
            if (w != null && !w.sunset.isEmpty() && !w.sunrise.isEmpty()) {
                // темніє приблизно через 30 хв після заходу, світліє — за 30 хв до... після сходу
                from = mins(w.sunset, from) + 30; to = mins(w.sunrise, to) + 15;
            }
        }
        return from > to ? now >= from || now < to : now >= from && now < to;
    }

    private static int mins(String s, int def) {
        try { String[] x = s.trim().split(":"); return (Integer.parseInt(x[0]) * 60 + Integer.parseInt(x[1])) % 1440; } catch (Exception e) { return def; }
    }

    /** Викликається щокілька секунд і при зміні тривоги. */
    void update() {
        long now = System.currentTimeMillis();
        boolean alert = a.alertActive();
        boolean nightTime = isNightTime();
        boolean show = nightTime && !alert && now >= peekUntil;
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        // яскравість: тривога — максимум; ніч — мінімум (і під час 30 с перегляду — помірно); інакше — системна
        float b = alert ? 1f : show ? 0.01f : nightTime ? 0.3f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (b != lastBright) {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes(); lp.screenBrightness = b; a.getWindow().setAttributes(lp);
            lastBright = b;
        }
        if (!show) return;
        clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)));
        Boolean on = a.lightOn();
        double soc = a.socNow();
        l1.setText((on == null ? "світло: —" : on ? "світло є" : "світла немає") + "   ·   АКБ " + (Double.isNaN(soc) ? "—" : Main.n0(soc) + "%"));
        String[] cd = a.countdown();
        l2.setText(cd == null || cd[1].isEmpty() ? "" : cd[0].replace(", через", "") + " (" + cd[1] + ")");
        // невеликий зсув щохвилини проти вигорання екрана
        int m = (int) (now / 60000L) % 9;
        box.setTranslationX((m % 3 - 1) * 24f); box.setTranslationY((m / 3 - 1) * 18f);
    }
}
