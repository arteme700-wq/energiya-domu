package ua.sister.energy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Надсилає знімки на сервер (POST JSON, Bearer). Черга до MAX знімків у prefs, якщо сервер недоступний. */
public final class Uploader {
    private static final String TAG = "Uploader";
    static final long PERIOD_MS = 60000;
    private static final int MAX = 240; // ~4 год при 1/хв
    private final Context ctx;
    private final SharedPreferences q;
    private long last;

    public Uploader(Context c) { ctx = c.getApplicationContext(); q = ctx.getSharedPreferences("upload_q", Context.MODE_PRIVATE); }

    /** Викликати з потоку опитування. */
    public void maybe(Prefs p, Snapshot s, long sOk, JkBmsProtocol.CellInfo ci, long ciMs) {
        long now = System.currentTimeMillis();
        if (now - last < PERIOD_MS) return;
        last = now;
        String url = p.serverUrl().trim();
        if (url.isEmpty() || p.demo()) return;
        try {
            JSONArray queue = new JSONArray(q.getString("q", "[]"));
            queue.put(build(now, s, sOk, ci, ciMs));
            while (queue.length() > MAX) queue.remove(0);
            JSONObject body = new JSONObject();
            body.put("device", "sister-tablet");
            body.put("app_version", BuildConfig.VERSION_NAME);
            body.put("version_code", BuildConfig.VERSION_CODE);
            body.put("sent_ts", now);
            body.put("snapshots", queue);
            try { LanServer.extras(ctx, body, now); } catch (Throwable t) { Log.w(TAG, "extras", t); }
            int code = post(url, p.serverToken().trim(), body.toString());
            if (code >= 200 && code < 300) { q.edit().putString("q", "[]").putLong("ok_ts", now).putInt("last_code", code).apply(); }
            else q.edit().putString("q", queue.toString()).putInt("last_code", code).apply();
        } catch (Exception e) {
            Log.w(TAG, "upload", e);
            q.edit().putInt("last_code", -1).apply();
        }
    }

    public String status() {
        long ok = q.getLong("ok_ts", 0);
        int code = q.getInt("last_code", 0);
        int n = 0; try { n = new JSONArray(q.getString("q", "[]")).length(); } catch (Exception ignored) { }
        return "Останнє успішне: " + (ok == 0 ? "ще не було" : new java.text.SimpleDateFormat("dd.MM HH:mm:ss").format(new java.util.Date(ok)))
                + " · код " + code + " · у черзі " + n;
    }

    private static int post(String url, String token, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            return c.getResponseCode();
        } finally { c.disconnect(); }
    }

    private static void put(JSONObject o, String k, double v) throws Exception { if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(k, v); }

    static JSONObject build(long now, Snapshot s, long sOk, JkBmsProtocol.CellInfo ci, long ciMs) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ts", now);
        if (s != null) {
            JSONObject i = new JSONObject();
            i.put("ts", sOk); i.put("age_s", (now - sOk) / 1000);
            put(i, "pv1", s.pv1); put(i, "pv2", s.pv2); put(i, "pv", s.pv()); put(i, "load", s.load); put(i, "grid", s.grid); put(i, "batt", s.batt);
            put(i, "grid_v", s.gridV); put(i, "grid_hz", s.gridHz); put(i, "temp_hs", s.tempHs); put(i, "temp_dc", s.tempDc);
            put(i, "soc", s.invSoc); put(i, "batt_v", s.invBattV); put(i, "gen", s.gen);
            put(i, "today_pv", s.todayPv); put(i, "today_load", s.todayLoad); put(i, "today_imp", s.todayImp); put(i, "today_exp", s.todayExp);
            put(i, "today_chg", s.todayChg); put(i, "today_dis", s.todayDis);
            i.put("state", s.state); i.put("grid_relay", s.gridRelay); i.put("grid_on", s.gridOn());
            o.put("inv", i);
        }
        if (ci != null && now - ciMs < 120000) {
            JSONObject b = new JSONObject();
            b.put("ts", ciMs);
            put(b, "v", ci.voltage); put(b, "a", ci.current); put(b, "w", ci.power);
            b.put("soc", ci.soc); b.put("soh", ci.soh); b.put("cycles", ci.cycles);
            put(b, "remain_ah", ci.remainAh); put(b, "cap_ah", ci.capacityAh); put(b, "temp_mos", ci.tempMos);
            JSONArray cells = new JSONArray(); double mn = 99, mx = 0;
            for (double v : ci.cells) { if (v > 0.5) { cells.put(v); mn = Math.min(mn, v); mx = Math.max(mx, v); } }
            b.put("cells", cells);
            if (cells.length() > 0) { b.put("cell_min", mn); b.put("cell_max", mx); b.put("cell_diff_mv", Math.round((mx - mn) * 1000)); }
            JSONArray t = new JSONArray(); for (double v : ci.temps) if (!Double.isNaN(v)) t.put(v);
            b.put("temps", t);
            o.put("bms", b);
        }
        return o;
    }
}
