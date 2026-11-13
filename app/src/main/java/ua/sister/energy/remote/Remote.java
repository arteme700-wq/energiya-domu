package ua.sister.energy.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.util.UUID;

import ua.sister.energy.BuildConfig;
import ua.sister.energy.JkBmsProtocol;

/**
 * Віддалений збір стану JK BMS: публікує компактний знімок у EMQX Cloud MQTT
 * під вшитим логіном {@link #LOGIN}. Немає UI для зміни логіна.
 * Демо-дані ніколи не надсилаються.
 */
public final class Remote {
    public static final String LOGIN = "Hmarskie";
    private static final String TAG = "BmsRemote";
    private static final long FRESH_MS = 15_000L;

    private static volatile Remote INSTANCE;

    public static Remote get() { return INSTANCE; }

    private final Context app;
    private final RemoteMonitor monitor;
    private final IrCalculator ir = new IrCalculator();
    private final Handler tick;
    private final HandlerThread tickThread;
    private volatile JkBmsProtocol.CellInfo cell;
    private volatile long cellMs;
    private volatile JkBmsProtocol.DeviceInfo device;
    private volatile boolean demo;
    private long lastStateAt;
    private ConnectivityManager.NetworkCallback netCb;

    public Remote(Context ctx) {
        INSTANCE = this;
        app = ctx.getApplicationContext();
        BrokerConfig cfg = new BrokerConfig(
                BuildConfig.REMOTE_HOST, BuildConfig.REMOTE_PORT,
                BuildConfig.REMOTE_USER, BuildConfig.REMOTE_PASS, true);
        String cid = "energiya-" + LOGIN + "-" + clientSuffix();
        monitor = new RemoteMonitor(cfg, login -> new PahoLink(cfg, cid),
                new File(app.getFilesDir(), "bms-remote-queue.txt"));
        tickThread = new HandlerThread("bms-remote-tick");
        tickThread.start();
        tick = new Handler(tickThread.getLooper());
    }

    public void start() {
        monitor.update(true, LOGIN);
        monitor.start();
        tick.removeCallbacksAndMessages(null);
        tick.post(ticker);
        registerNetwork();
    }

    public void setDemo(boolean demo) { this.demo = demo; }

    public void shutdown() {
        tick.removeCallbacksAndMessages(null);
        tickThread.quitSafely();
        unregisterNetwork();
        monitor.shutdown();
        if (INSTANCE == this) INSTANCE = null;
    }

    public void onCell(JkBmsProtocol.CellInfo ci, long now) {
        if (ci == null) return;
        cell = ci;
        cellMs = now;
        if (demo) return;
        Double temp = null;
        if (!Double.isNaN(ci.tempMos)) temp = ci.tempMos;
        else if (ci.temps != null) {
            for (double t : ci.temps) if (!Double.isNaN(t)) { temp = t; break; }
        }
        IrCalculator.IrEvent ev = ir.feed(new IrCalculator.IrSample(
                now, ci.voltage, ci.current, ci.cells, ci.soc, temp, ci.errorsMask));
        if (ev != null) monitor.submitIr(ev.tsMs, RemotePayload.irEvent(ev, ir.summary()));
    }

    public void onDevice(JkBmsProtocol.DeviceInfo di) { if (di != null) device = di; }

    /** Український рядок стану для екрана налаштувань (великий шрифт). */
    public static String statusUk() {
        Remote r = INSTANCE;
        if (r == null) return "Віддалений моніторинг ще не запущено";
        RemoteMonitor.Status s = r.monitor.status();
        switch (s.kind) {
            case OFF: return "Вимкнено";
            case NO_LOGIN: return "Немає логіна";
            case NOT_CONFIGURED: return "Брокер не налаштований у цій збірці";
            case CONNECTING: return "Підключаюсь до брокера…  черга " + s.queued;
            case CONNECTED:
                String when = s.lastSentAt == 0 ? "ще не надсилалось"
                        : ("останнє " + Math.max(0, (System.currentTimeMillis() - s.lastSentAt) / 1000) + " с тому");
                return "На зв'язку · логін " + LOGIN
                        + (s.watched ? " · зараз дивляться (кожні 5 с)" : " · раз на 60 с")
                        + " · " + when
                        + (s.queued > 0 ? " · у черзі " + s.queued : "");
            case OFFLINE:
                return "Немає зв'язку з брокером"
                        + (s.error != null ? " (" + s.error + ")" : "")
                        + (s.queued > 0 ? " · у черзі " + s.queued : "");
            default: return s.kind.name();
        }
    }

    public static int statusColor() {
        Remote r = INSTANCE;
        if (r == null) return 0xFFD97706;
        switch (r.monitor.status().kind) {
            case CONNECTED: return 0xFF16A34A;
            case CONNECTING: return 0xFFD97706;
            case OFFLINE:
            case NOT_CONFIGURED:
            case NO_LOGIN: return 0xFFDC2626;
            default: return 0xFF64748B;
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try { produce(); } catch (Exception e) { Log.w(TAG, "tick", e); }
            tick.postDelayed(this, 1000);
        }
    };

    private void produce() {
        if (demo || !monitor.active()) return;
        JkBmsProtocol.CellInfo ci = cell;
        long now = System.currentTimeMillis();
        if (ci == null || now - cellMs > FRESH_MS) return;
        JkBmsProtocol.DeviceInfo di = device;
        Integer n = (ci.cells != null && ci.cells.length > 0) ? ci.cells.length : null;
        String model = di != null && notBlank(di.vendor) ? di.vendor.trim() : null;
        String hw = di != null ? emptyToNull(di.hardware) : null;
        String sw = di != null ? emptyToNull(di.software) : null;
        String name = di != null ? emptyToNull(di.name) : null;
        monitor.setMeta(RemotePayload.meta(model, hw, sw, name,
                "energiya-" + BuildConfig.VERSION_NAME, null, n));
        if (now - lastStateAt < monitor.intervalMs() - 500) return;
        lastStateAt = now;
        monitor.submitState(now, RemotePayload.stateRecord(now, ci));
    }

    private String clientSuffix() {
        SharedPreferences sp = app.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        String s = sp.getString("remote_cid", null);
        if (s == null || s.isEmpty()) {
            s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            sp.edit().putString("remote_cid", s).apply();
        }
        return s;
    }

    private void registerNetwork() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { monitor.requestReconnect(); }
            @Override public void onLost(Network network) { monitor.requestReconnect(); }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception e) { Log.w(TAG, "net", e); }
    }

    private void unregisterNetwork() {
        if (netCb == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCb);
        } catch (Exception ignored) { }
        netCb = null;
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String emptyToNull(String s) { return notBlank(s) ? s.trim() : null; }
}
