package ua.sister.energy;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Мінімальний HTTP-сервер (лише GET) у локальній мережі: сторінка для старого iPad + /api/status.json. */
public final class LanServer {
    private static final String TAG = "LanServer";
    public static final int DEFAULT_PORT = 8080;
    private static volatile Snapshot snap; private static volatile long snapMs;
    private static volatile JkBmsProtocol.CellInfo cell; private static volatile long cellMs;
    private static ServerSocket ss; private static int runningPort = -1;
    private static byte[] html, adbHtml;
    private static volatile Context app;

    public static void update(Snapshot s, long sMs, JkBmsProtocol.CellInfo c, long cMs) { snap = s; snapMs = sMs; cell = c; cellMs = cMs; }

    /** Запустити/перезапустити/зупинити відповідно до налаштувань. */
    public static synchronized void apply(Context ctx) {
        app = ctx.getApplicationContext();
        Prefs p = new Prefs(ctx);
        int port = p.lanPort();
        if (!p.lanOn()) { stop(); return; }
        if (ss != null && !ss.isClosed() && runningPort == port) return;
        stop();
        try { html = read(ctx.getAssets().open("ipad.html")); } catch (Exception e) { html = "<h1>no page</h1>".getBytes(StandardCharsets.UTF_8); }
        try { adbHtml = read(ctx.getAssets().open("adb.html")); } catch (Exception e) { adbHtml = "<h1>no page</h1>".getBytes(StandardCharsets.UTF_8); }
        try {
            final ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("0.0.0.0", port));
            ss = s; runningPort = port;
            Thread t = new Thread(() -> loop(s), "lan-http"); t.setDaemon(true); t.start();
            Log.i(TAG, "listening on " + port);
        } catch (Exception e) { Log.w(TAG, "bind " + port, e); ss = null; runningPort = -1; }
    }

    public static synchronized void stop() {
        if (ss != null) try { ss.close(); } catch (Exception ignored) { }
        ss = null; runningPort = -1;
    }

    public static synchronized boolean running() { return ss != null && !ss.isClosed(); }

    private static void loop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                final Socket c = s.accept();
                new Thread(() -> handle(c), "lan-req").start();
            } catch (Exception e) { if (!s.isClosed()) Log.w(TAG, "accept", e); }
        }
    }

    private static void handle(Socket c) {
        try {
            c.setSoTimeout(10000);
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder(); int b, n = 0;
            while ((b = in.read()) != -1 && n++ < 8192) { sb.append((char) b); if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break; }
            String line = sb.toString().split("\r\n", 2)[0];
            String[] parts = line.split(" ");
            OutputStream out = c.getOutputStream();
            if (parts.length < 2) { send(out, 405, "text/plain", "bad".getBytes(StandardCharsets.UTF_8), false); return; }
            String path = parts[1]; int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
            if (parts[0].equals("POST")) {
                String hdr = sb.toString().toLowerCase(java.util.Locale.ROOT);
                // лише з власної сторінки: свій заголовок (крос-доменна форма його не поставить) + Origin, якщо є, на цей самий хост
                boolean okHdr = hdr.contains("\r\nx-sister: 1");
                String origin = header(hdr, "origin"), host = header(hdr, "host");
                boolean okOrigin = origin == null || (host != null && (origin.equals("http://" + host)));
                if (!okHdr || !okOrigin) { send(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8), false); return; }
                org.json.JSONObject r;
                if (path.equals("/api/adb-wifi")) r = AdbWifi.enable(app);
                else if (path.equals("/api/adb-wifi-off")) r = AdbWifi.disable(app);
                else { send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false); return; }
                send(out, 200, "application/json; charset=utf-8", r.toString().getBytes(StandardCharsets.UTF_8), false); return;
            }
            if (!(parts[0].equals("GET") || parts[0].equals("HEAD"))) { send(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8), false); return; }
            boolean head = parts[0].equals("HEAD");
            if (path.equals("/") || path.equals("/index.html") || path.equals("/ipad") || path.equals("/ipad/")) send(out, 200, "text/html; charset=utf-8", html, head);
            else if (path.equals("/api/status.json")) send(out, 200, "application/json; charset=utf-8", status().getBytes(StandardCharsets.UTF_8), head);
            else if (path.equals("/adb") || path.equals("/adb/") || path.equals("/service")) send(out, 200, "text/html; charset=utf-8", adbHtml, head);
            else if (path.equals("/api/adb.json")) send(out, 200, "application/json; charset=utf-8", AdbWifi.status(app).toString().getBytes(StandardCharsets.UTF_8), head);
            else send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), head);
        } catch (Exception e) { Log.w(TAG, "req", e); }
        finally { try { c.close(); } catch (Exception ignored) { } }
    }

    static String status() {
        long now = System.currentTimeMillis();
        try {
            Snapshot s = snap; long sMs = snapMs;
            JSONObject o = Uploader.build(now, s != null && now - sMs < 120000 ? s : null, sMs, cell, cellMs);
            o.put("app_version", BuildConfig.VERSION_NAME);
            extras(o, now);
            return o.toString();
        } catch (Exception e) { return "{\"ts\":" + now + ",\"error\":\"build\"}"; }
    }

    /** Дані для 2-ї сторінки iPad: лише з кешу/БД, без мережевих запитів. Кожен блок — окремо, щоб збій одного не ламав інші. */
    private static void extras(JSONObject o, long now) { extras(app, o, now); }

    static void extras(Context c, JSONObject o, long now) {
        if (c == null) return;
        Prefs p = new Prefs(c);
        try {
            Info.Weather w = Info.weather(c, p, true);
            if (w != null) {
                JSONObject j = new JSONObject();
                if (!Double.isNaN(w.temp)) j.put("temp", w.temp);
                if (!Double.isNaN(w.hum)) j.put("hum", w.hum);
                if (!Double.isNaN(w.wind)) j.put("wind", w.wind);
                j.put("icon", Info.icon(w.code)); j.put("desc", Info.desc(w.code));
                j.put("sunrise", w.sunrise); j.put("sunset", w.sunset);
                org.json.JSONArray d = new org.json.JSONArray();
                for (int i = 0; i < w.day.length && i < 4; i++) {
                    JSONObject x = new JSONObject(); x.put("day", w.day[i]); x.put("icon", Info.icon(w.dcode[i]));
                    if (!Double.isNaN(w.tmin[i])) x.put("tmin", w.tmin[i]); if (!Double.isNaN(w.tmax[i])) x.put("tmax", w.tmax[i]);
                    if (!Double.isNaN(w.rain[i])) x.put("rain", w.rain[i]);
                    if (i < w.pvKwh.length && !Double.isNaN(w.pvKwh[i])) x.put("pv_kwh", w.pvKwh[i]);
                    d.put(x);
                }
                j.put("days", d); o.put("weather", j);
            }
        } catch (Exception e) { Log.w(TAG, "weather", e); }
        try {
            Info.Outage ou = Info.outage(c, p, true);
            if (ou != null) {
                JSONObject j = new JSONObject(); j.put("queue", ou.queue); j.put("updated", ou.updated);
                org.json.JSONArray days = new org.json.JSONArray();
                long d0 = Info.dayStart(now);
                for (int k = 0; k < 2; k++) {
                    Info.Day d = ou.day(d0 + k * 86400000L + 3600000L); if (d == null) continue;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 48; i++) sb.append(d.off[i] ? '0' : d.maybe[i] ? '?' : '1');
                    JSONObject x = new JSONObject(); x.put("start", d.start); x.put("slots", sb.toString()); days.put(x);
                }
                j.put("days", days);
                Boolean off = ou.offAt(now); if (off != null) j.put("off_now", off);
                long nx = ou.nextChange(now); if (nx > 0) j.put("next_change", nx);
                o.put("schedule", j);
            }
        } catch (Exception e) { Log.w(TAG, "outage", e); }
        try (HistoryDb db = new HistoryDb(c)) {
            Object[][] wk = db.week(now); org.json.JSONArray a = new org.json.JSONArray();
            for (Object[] r : wk) { JSONObject x = new JSONObject(); x.put("ts", r[0]); x.put("pv", r[1]); x.put("load", r[2]); a.put(x); }
            o.put("week", a);
            long[] lo = db.lastOutage();
            if (lo != null) { JSONObject x = new JSONObject(); x.put("start", lo[0]); x.put("end", lo[1]); o.put("last_outage", x); }
        } catch (Exception e) { Log.w(TAG, "history", e); }
    }

    private static void send(OutputStream out, int code, String type, byte[] body, boolean head) throws Exception {
        String st = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Method Not Allowed";
        String h = "HTTP/1.1 " + code + " " + st + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\n" + (code == 403 ? "" : "Access-Control-Allow-Origin: *\r\n") + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII)); if (!head) out.write(body); out.flush();
    }

    private static String header(String lowerHdr, String name) {
        for (String l : lowerHdr.split("\r\n")) if (l.startsWith(name + ":")) return l.substring(name.length() + 1).trim();
        return null;
    }

    private static byte[] read(InputStream in) throws Exception {
        try { ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) > 0) bo.write(buf, 0, r); return bo.toByteArray(); }
        finally { in.close(); }
    }

    /** IPv4 адреса в локальній мережі (Wi‑Fi/Ethernet) або null. */
    public static String lanIp() {
        try {
            String any = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return ip;
                    if (any == null && a.isSiteLocalAddress()) any = ip;
                }
            }
            return any;
        } catch (Exception e) { return null; }
    }
}
