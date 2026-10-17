package ua.sister.energy.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Тиха червона смуга під рядком стану під час повітряної тривоги (Броварський район, запасний — Київська обл.).
 * Без звуку і без сповіщень. Вікно TYPE_APPLICATION_OVERLAY лежить під системним рядком стану,
 * тож червоне видно крізь його прозорий фон, а годинник і значки — поверх.
 */
final class StripOverlay {
    static final String SIREN = "https://siren.pp.ua/api/v3/alerts/";
    static final String REGION = "79", FALLBACK = "14";
    private static final String TAG = "Strip";

    /** Останній відомий стан тривоги (null — ще не знаємо). */
    static volatile Boolean alert = null;
    static volatile long checkedMs = 0;

    private static View view;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean test;

    private StripOverlay() { }

    static boolean enabled(Context c) { return c.getSharedPreferences("st", Context.MODE_PRIVATE).getBoolean("strip", true); }
    static void setEnabled(Context c, boolean on) { c.getSharedPreferences("st", Context.MODE_PRIVATE).edit().putBoolean("strip", on).apply(); apply(c); }
    static boolean canDraw(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    /** Показати/сховати відповідно до стану. */
    static void apply(Context c) {
        final Context a = c.getApplicationContext();
        ui.post(() -> {
            boolean want = test || (Boolean.TRUE.equals(alert) && enabled(a));
            if (want && canDraw(a)) show(a); else hide(a);
        });
    }

    static void test5s(Context c) {
        test = true; apply(c);
        ui.postDelayed(() -> { test = false; apply(c); }, 5000);
    }

    @SuppressWarnings("deprecation")
    private static void show(Context c) {
        if (view != null) return;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight(c, wm), type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START; lp.x = 0; lp.y = 0;
        lp.alpha = 0.8f; // ≤ 0.8 — Android 12+ пропускає дотики крізь вікно
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0); lp.setFitInsetsSides(0);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle("energiya-alert-strip");
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFE11D48, 0xE6E11D48, 0x00E11D48}));
        try { wm.addView(v, lp); view = v; } catch (Exception e) { Log.w(TAG, "addView", e); }
    }

    private static void hide(Context c) {
        if (view == null) return;
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(view); } catch (Exception ignored) { }
        view = null;
    }

    private static int statusBarHeight(Context c, WindowManager wm) {
        int h = 0;
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) h = c.getResources().getDimensionPixelSize(id);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowInsets wi = wm.getCurrentWindowMetrics().getWindowInsets();
                h = Math.max(h, wi.getInsets(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout()).top);
            } catch (Exception ignored) { }
        }
        return h > 0 ? h : (int) (24 * c.getResources().getDisplayMetrics().density);
    }

    // ---- опитування siren.pp.ua ----

    /** Цикл опитування: ~30 с при увімкненому екрані, ~2 хв — при вимкненому. Викликати в окремому потоці. */
    static void poll(Context c, java.util.function.BooleanSupplier running) {
        final Object lock = new Object();
        BroadcastReceiver on = new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) { synchronized (lock) { lock.notifyAll(); } }
        };
        c.registerReceiver(on, new IntentFilter(Intent.ACTION_SCREEN_ON));
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            while (running.getAsBoolean()) {
                try {
                    Boolean was = alert;
                    alert = fetch();
                    checkedMs = System.currentTimeMillis();
                    if (!alert.equals(was)) Log.i(TAG, "alert=" + alert);
                } catch (Exception e) { Log.w(TAG, "fetch: " + e); } // залишаємо останній відомий стан
                apply(c);
                long wait = pm.isInteractive() ? 30_000 : 120_000;
                synchronized (lock) { lock.wait(wait); }
            }
        } catch (InterruptedException ignored) {
        } finally {
            try { c.unregisterReceiver(on); } catch (Exception ignored) { }
            alert = null; apply(c);
        }
    }

    static boolean fetch() throws Exception {
        String body;
        try { body = get(SIREN + REGION); } catch (Exception e) { body = get(SIREN + FALLBACK); }
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) throw new Exception("empty");
        JSONArray act = arr.getJSONObject(0).optJSONArray("activeAlerts");
        if (act != null) for (int i = 0; i < act.length(); i++) {
            JSONObject x = act.getJSONObject(i);
            if ("AIR".equals(x.optString("type", "AIR"))) return true;
        }
        return false;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "energiya-notify/" + BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
                while ((r = in.read(b)) > 0) { bo.write(b, 0, r); if (bo.size() > 256 << 10) throw new java.io.IOException("too big"); }
                return bo.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }
}
