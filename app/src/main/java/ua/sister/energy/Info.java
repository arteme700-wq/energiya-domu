package ua.sister.energy;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Тривоги, графік відключень, погода. Лише читання з публічних джерел, кеш на диску для роботи без інтернету. */
final class Info {
    private static final String TAG = "SisterEnergy";
    static final String SIREN = "https://siren.pp.ua/api/v3/alerts/";
    static final String OUTAGE = "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv-region.json";
    static final String METEO = "https://api.open-meteo.com/v1/forecast";
    static final TimeZone KYIV = TimeZone.getTimeZone("Europe/Kiev");

    // ---- тривога ----
    static final class Alert {
        boolean active; long since; String region = ""; long fetchedMs; boolean fallback;
    }

    // ---- графік ----
    static final class Day {
        long start;            // початок доби (мс)
        boolean[] off = new boolean[48]; // півгодини без світла
        boolean[] maybe = new boolean[48];
    }
    static final class Outage {
        List<Day> days = new ArrayList<>(); String updated = ""; long fetchedMs; String queue = "";
        Day day(long t) { for (Day d : days) if (t >= d.start && t < d.start + 86400000L) return d; return null; }
        /** Чи вимкнено за графіком у момент t; null — немає даних. */
        Boolean offAt(long t) { Day d = day(t); if (d == null) return null; return d.off[(int) ((t - d.start) / 1800000L)]; }
        /** Наступна зміна стану після t (мс) або 0, якщо невідомо. */
        long nextChange(long t) {
            Boolean cur = offAt(t); if (cur == null) return 0;
            long s = (t / 1800000L + 1) * 1800000L;
            for (long x = s; x < t + 2 * 86400000L; x += 1800000L) { Boolean o = offAt(x); if (o == null) return 0; if (o != cur) return x; }
            return 0;
        }
    }

    // ---- погода ----
    static final class Weather {
        double temp = Double.NaN, hum = Double.NaN, wind = Double.NaN; int code = -1; long fetchedMs;
        String[] day = new String[0]; int[] dcode = new int[0]; double[] tmin = new double[0], tmax = new double[0], rain = new double[0], pvKwh = new double[0];
        String sunrise = "", sunset = "";
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "EnergiyaDomu/" + BuildConfig.VERSION_NAME);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) { bo.write(b, 0, n); if (bo.size() > 2_000_000) throw new Exception("too big"); }
                return bo.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    static long iso(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.length() > 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) { return 0; }
    }

    /** Тривога в районі; якщо район недоступний — область. */
    static Alert alert(Prefs p) throws Exception {
        Alert a = new Alert();
        String body;
        try { body = get(SIREN + p.alertRegion()); }
        catch (Exception e) { body = get(SIREN + p.alertFallback()); a.fallback = true; }
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) throw new Exception("empty");
        JSONObject r = arr.getJSONObject(0);
        a.region = r.optString("regionName");
        JSONArray act = r.optJSONArray("activeAlerts");
        if (act != null) for (int i = 0; i < act.length(); i++) {
            JSONObject x = act.getJSONObject(i);
            if (!"AIR".equals(x.optString("type", "AIR"))) continue;
            a.active = true;
            long t = iso(x.optString("lastUpdate"));
            if (a.since == 0 || (t > 0 && t < a.since)) a.since = t;
        }
        a.fetchedMs = System.currentTimeMillis();
        return a;
    }

    static Outage outage(Context c, Prefs p, boolean useCache) throws Exception {
        File f = new File(c.getCacheDir(), "outage.json");
        String body;
        if (useCache) { body = read(f); if (body == null) return null; }
        else { body = get(OUTAGE); write(f, body); }
        Outage o = parseOutage(body, p.queue());
        o.fetchedMs = useCache ? f.lastModified() : System.currentTimeMillis();
        return o;
    }

    static Outage parseOutage(String body, String queue) throws Exception {
        JSONObject j = new JSONObject(body);
        Outage o = new Outage(); o.queue = queue;
        JSONObject fact = j.getJSONObject("fact");
        o.updated = fact.optString("update");
        JSONObject data = fact.getJSONObject("data");
        String key = "GPV" + queue.trim();
        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String ts = it.next();
            JSONObject g = data.getJSONObject(ts).optJSONObject(key);
            if (g == null) continue;
            Day d = new Day(); d.start = Long.parseLong(ts) * 1000L;
            for (int h = 1; h <= 24; h++) {
                String v = g.optString(String.valueOf(h), "yes");
                int a = (h - 1) * 2, b = a + 1;
                switch (v) {
                    case "no": d.off[a] = d.off[b] = true; break;
                    case "first": d.off[a] = true; break;
                    case "second": d.off[b] = true; break;
                    case "maybe": d.maybe[a] = d.maybe[b] = true; break;
                    case "mfirst": d.maybe[a] = true; break;
                    case "msecond": d.maybe[b] = true; break;
                    default: break;
                }
            }
            o.days.add(d);
        }
        java.util.Collections.sort(o.days, (x, y) -> Long.compare(x.start, y.start));
        return o;
    }

    static Weather weather(Context c, Prefs p, boolean useCache) throws Exception {
        File f = new File(c.getCacheDir(), "weather.json");
        String body;
        if (useCache) { body = read(f); if (body == null) return null; }
        else {
            String url = String.format(Locale.US, "%s?latitude=%.2f&longitude=%.2f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                    + "&hourly=global_tilted_irradiance&tilt=%d&azimuth=%d&timezone=Europe%%2FKyiv&forecast_days=4",
                    METEO, p.lat(), p.lon(), p.pvTilt(), p.pvAzimuth());
            body = get(url); write(f, body);
        }
        JSONObject j = new JSONObject(body);
        Weather w = new Weather();
        JSONObject cur = j.optJSONObject("current");
        if (cur != null) { w.temp = cur.optDouble("temperature_2m"); w.hum = cur.optDouble("relative_humidity_2m"); w.wind = cur.optDouble("wind_speed_10m"); w.code = cur.optInt("weather_code", -1); }
        JSONObject d = j.getJSONObject("daily");
        JSONArray t = d.getJSONArray("time"); int n = t.length();
        w.day = new String[n]; w.dcode = new int[n]; w.tmin = new double[n]; w.tmax = new double[n]; w.rain = new double[n]; w.pvKwh = new double[n];
        for (int i = 0; i < n; i++) {
            w.day[i] = t.getString(i); w.dcode[i] = d.getJSONArray("weather_code").optInt(i, -1);
            w.tmin[i] = d.getJSONArray("temperature_2m_min").optDouble(i); w.tmax[i] = d.getJSONArray("temperature_2m_max").optDouble(i);
            w.rain[i] = d.getJSONArray("precipitation_probability_max").optDouble(i);
        }
        String sr = d.getJSONArray("sunrise").optString(0), ss = d.getJSONArray("sunset").optString(0);
        w.sunrise = sr.length() >= 16 ? sr.substring(11, 16) : ""; w.sunset = ss.length() >= 16 ? ss.substring(11, 16) : "";
        JSONObject h = j.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time"), g = h.getJSONArray("global_tilted_irradiance");
        for (int i = 0; i < ht.length(); i++) {
            String day = ht.getString(i).substring(0, 10);
            for (int k = 0; k < n; k++) if (w.day[k].equals(day)) { double v = g.optDouble(i); if (!Double.isNaN(v)) w.pvKwh[k] += v / 1000.0 * p.pvKwp() * p.pvEff(); }
        }
        w.fetchedMs = useCache ? f.lastModified() : System.currentTimeMillis();
        return w;
    }

    static String icon(int code) {
        if (code < 0) return "·";
        if (code == 0) return "☀️";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫";
        if (code <= 67) return "🌧";
        if (code <= 77) return "🌨";
        if (code <= 82) return "🌦";
        if (code <= 86) return "🌨";
        return "⛈";
    }

    static String desc(int code) {
        if (code < 0) return "";
        if (code == 0) return "ясно";
        if (code <= 2) return "мінлива хмарність";
        if (code == 3) return "хмарно";
        if (code <= 48) return "туман";
        if (code <= 55) return "мряка";
        if (code <= 57) return "крижана мряка";
        if (code == 61) return "невеликий дощ";
        if (code <= 65) return "дощ";
        if (code <= 67) return "крижаний дощ";
        if (code <= 77) return "сніг";
        if (code <= 82) return "злива";
        if (code <= 86) return "снігопад";
        return "гроза";
    }

    private static String read(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    private static void write(File f, String s) {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); } catch (Exception e) { Log.w(TAG, "cache", e); }
    }

    static long dayStart(long t) {
        Calendar c = Calendar.getInstance(KYIV); c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
