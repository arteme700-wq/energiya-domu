package ua.sister.energy.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Підписка на тему ntfy (JSON-потік) у фоновому сервісі з перепідключенням. */
public final class ListenService extends Service {
    private static final String TAG = "ListenService";
    static final String CH_SERVICE = "service", CH_ALERT = "alerts2";
    private volatile boolean running;
    private Thread th, upd, siren;
    private volatile HttpURLConnection conn;

    static void start(Context c) {
        if (new Store(c).topic().isEmpty()) return;
        Intent i = new Intent(c, ListenService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void restart(Context c) { c.stopService(new Intent(c, ListenService.class)); start(c); }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Фонова робота", NotificationManager.IMPORTANCE_MIN);
        s.setShowBadge(false); nm.createNotificationChannel(s);
        NotificationChannel a = new NotificationChannel(CH_ALERT, "Події: світло, АКБ, інвертор", NotificationManager.IMPORTANCE_HIGH);
        a.enableVibration(true); a.setVibrationPattern(new long[]{0, 400, 200, 400}); nm.createNotificationChannel(a);
    }

    @Override public void onCreate() {
        super.onCreate();
        channels(this);
        Notification n = builder(CH_SERVICE).setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Стежу за сповіщеннями").setContentText("Енергія дому")
                .setPriority(Notification.PRIORITY_MIN).setOngoing(true).setContentIntent(openApp()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        running = true;
        th = new Thread(this::loop, "ntfy"); th.start();
        // самооновлення: ~1 хв після старту, далі кожні 6 год
        upd = new Thread(() -> {
            try {
                Thread.sleep(60_000);
                Updater.check(this, false, 30 * 60_000L); // після старту — якщо не перевіряли останні 30 хв
                while (running) { Thread.sleep(6 * 3600_000L); Updater.check(this, false, 6 * 3600_000L - 60_000L); }
            } catch (InterruptedException ignored) { }
        }, "upd");
        upd.start();
        // тиха червона смуга під час тривоги (без звуку і сповіщень)
        siren = new Thread(() -> StripOverlay.poll(this, () -> running), "siren"); siren.start();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        running = false;
        HttpURLConnection c = conn; if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        if (th != null) th.interrupt();
        if (upd != null) upd.interrupt();
        if (siren != null) siren.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification.Builder builder(String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private void loop() {
        Store st = new Store(this);
        long backoff = 2000;
        while (running) {
            String topic = st.topic();
            String since = st.lastId().isEmpty() ? "10m" : st.lastId();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(Store.SERVER + "/" + topic + "/json?since=" + since).openConnection();
                conn = c;
                c.setConnectTimeout(20000); c.setReadTimeout(120_000); // ntfy шле keepalive кожні ~45 с
                c.setRequestProperty("User-Agent", "energiya-notify/" + BuildConfig.VERSION_NAME);
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                st.setStatus("Підключено · " + now());
                backoff = 2000;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        JSONObject e = new JSONObject(line);
                        String ev = e.optString("event");
                        if ("message".equals(ev)) { st.setLastId(e.getString("id")); show(e); }
                        else if ("keepalive".equals(ev) || "open".equals(ev)) st.setStatus("Підключено · " + now());
                    }
                }
            } catch (Exception ex) {
                if (!running) break;
                Log.w(TAG, "stream: " + ex);
                st.setStatus("Немає зв'язку, повтор… (" + now() + ")");
            }
            try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private static String now() { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date()); }

    private void show(JSONObject e) {
        String title = e.optString("title", "Енергія дому"), msg = e.optString("message", "");
        int prio = e.optInt("priority", 3);
        new Store(this).addMessage(safe(e, title, msg));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        try { pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "energiya:notify").acquire(3000); } catch (Exception ignored) { }
        Notification n = builder(CH_ALERT).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setPriority(prio >= 4 ? Notification.PRIORITY_MAX : Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL).setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true).setContentIntent(openApp()).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
        sendBroadcast(new Intent(MainActivity.ACTION_NEW).setPackage(getPackageName()));
    }

    private static JSONObject safe(JSONObject e, String title, String msg) {
        try { return new JSONObject().put("t", e.optLong("time", System.currentTimeMillis() / 1000)).put("title", title).put("msg", msg); }
        catch (Exception x) { return new JSONObject(); }
    }
}
