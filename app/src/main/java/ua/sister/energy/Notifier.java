package ua.sister.energy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Сповіщення через ntfy.sh: тема — випадкова, неможлива для вгадування.
 * Події дедуплікуються (повтор того самого ключа не частіше ніж раз на 10 хв, не більше 30 за годину)
 * і ставляться в чергу, якщо інтернету немає.
 */
public final class Notifier {
    private static final String TAG = "Notifier";
    public static final String SERVER = "https://ntfy.sh";
    private static final long SAME_KEY_MS = 10 * 60_000L;
    private static final int MAX_PER_HOUR = 30;
    private static final int MAX_QUEUE = 50;
    private final SharedPreferences p;

    public Notifier(Context c) { p = c.getSharedPreferences("ntfy", Context.MODE_PRIVATE); }

    public static String newTopic() {
        final String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder("energiya-");
        for (int i = 0; i < 24; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    public synchronized String topic() {
        String t = p.getString("topic", "");
        if (t.isEmpty()) { t = newTopic(); p.edit().putString("topic", t).apply(); }
        return t;
    }

    public synchronized void regenerate() { p.edit().putString("topic", newTopic()).apply(); }

    public boolean enabled() { return p.getBoolean("enabled", true); }
    public int socThreshold() { return p.getInt("soc_min", 20); }
    public SharedPreferences.Editor edit() { return p.edit(); }

    public static String pairUrl(String topic) { return SERVER + "/" + topic; }

    /** Сторінка встановлення телефонного додатка; код — у фрагменті (#), тому не потрапляє на сервер. */
    public static final String PAGE = "https://arteme700-wq.github.io/energiya-domu/p/#";
    public static String pageUrl(String topic) { return PAGE + topic; }

    /** Ставить подію в чергу (з дедуплікацією). priority: 1..5 (ntfy). */
    public synchronized void event(String key, String title, String msg, int priority, String tag) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("last_" + key, 0) < SAME_KEY_MS) return;
        try {
            JSONArray hist = new JSONArray(p.getString("hist", "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < hist.length(); i++) if (now - hist.getLong(i) < 3600_000L) keep.put(hist.getLong(i));
            if (keep.length() >= MAX_PER_HOUR) return;
            keep.put(now);
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now));
            JSONObject m = new JSONObject().put("title", title).put("message", msg + " (о " + time + ")")
                    .put("priority", priority).put("tags", new JSONArray().put(tag));
            q.put(m);
            while (q.length() > MAX_QUEUE) q.remove(0);
            p.edit().putString("queue", q.toString()).putString("hist", keep.toString()).putLong("last_" + key, now).apply();
        } catch (Exception e) { Log.w(TAG, "event", e); }
    }

    /** Надсилає чергу. Виконувати не в UI-потоці. @return кількість надісланих. */
    public int flush() {
        int sent = 0;
        while (true) {
            JSONObject m;
            synchronized (this) {
                try {
                    JSONArray q = new JSONArray(p.getString("queue", "[]"));
                    if (q.length() == 0) return sent;
                    m = q.getJSONObject(0);
                } catch (Exception e) { p.edit().remove("queue").apply(); return sent; }
            }
            try { post(topic(), m); } catch (Exception e) { Log.w(TAG, "flush: " + e); return sent; }
            synchronized (this) {
                try { JSONArray q = new JSONArray(p.getString("queue", "[]")); if (q.length() > 0) q.remove(0); p.edit().putString("queue", q.toString()).apply(); }
                catch (Exception ignored) { }
            }
            sent++;
        }
    }

    /** POST JSON на корінь ntfy (UTF-8 у тілі — без проблем з кирилицею в заголовках). */
    static void post(String topic, JSONObject m) throws Exception {
        JSONObject body = new JSONObject(m.toString()).put("topic", topic);
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + "/").openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        c.disconnect();
        if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
    }

    // ---------- стан для виявлення подій ----------
    private Boolean gridOn;
    private boolean socLow, fault, bmsLost, loggerLost;

    /** Викликається після кожного опитування (не в демо-режимі). */
    public void check(Snapshot s, long snapOkMs, JkBmsProtocol.CellInfo ci, long cellMs, boolean bmsConfigured, long startMs) {
        long now = System.currentTimeMillis();
        boolean invOk = s != null && now - snapOkMs < 60_000;
        if (invOk) {
            boolean on = s.gridOn();
            if (gridOn != null && gridOn != on) {
                if (!on) event("grid_off", "⚡ Світло зникло", "Немає напруги в мережі. Живлення від АКБ і сонця.", 5, "warning");
                else event("grid_on", "✅ Світло з'явилось", String.format(Locale.US, "Мережа повернулась: %.0f В.", s.gridV), 4, "white_check_mark");
            }
            gridOn = on;
            boolean f = s.state == 3 || s.state == 4;
            if (f && !fault) event("fault", s.state == 4 ? "⛔ Аварія інвертора" : "⚠ Попередження інвертора", "Перевірте інвертор або застосунок Deye Cloud.", 5, "rotating_light");
            fault = f;
        }
        double soc = ci != null && now - cellMs < 120_000 && ci.soc >= 0 ? ci.soc : invOk ? s.invSoc : Double.NaN;
        if (!Double.isNaN(soc)) {
            int th = socThreshold();
            if (soc < th && !socLow) { socLow = true; event("soc_low", "🔋 Низький заряд АКБ", String.format(Locale.US, "Заряд %.0f %% (поріг %d %%). Зменште споживання.", soc, th), 5, "battery"); }
            else if (soc >= th + 5) socLow = false;
        }
        if (bmsConfigured && now - startMs > 5 * 60_000L) {
            boolean lost = now - cellMs > 5 * 60_000L;
            if (lost && !bmsLost) event("bms_lost", "📵 Немає зв'язку з BMS", "Планшет не отримує дані АКБ по Bluetooth понад 5 хв.", 3, "warning");
            bmsLost = lost;
        }
        if (now - startMs > 10 * 60_000L) {
            boolean lost = now - snapOkMs > 10 * 60_000L;
            if (lost && !loggerLost) event("logger_lost", "📡 Інвертор не відповідає", "Немає даних з логера понад 10 хв (Wi-Fi або живлення логера).", 3, "warning");
            if (!lost && loggerLost) event("logger_ok", "📡 Зв'язок з інвертором відновлено", "Дані з логера знову надходять.", 2, "white_check_mark");
            loggerLost = lost;
        }
    }
}
