package ua.sister.energy.remote;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends battery telemetry to the MQTT broker (EMQX Serverless).
 *  - state every 60 s; every 5 s while the server says someone watches this login (bms/<login>/watch);
 *  - everything goes through a bounded RemoteQueue (outbox, 24 h);
 *  - backlog is batched and rate-limited; live state QoS 0, backlog/IR/meta QoS 1.
 * Runs entirely on its own I/O thread. Producers never block. Never touches BLE/BMS.
 */
final class RemoteMonitor {
    private static final String TAG = "BmsRemote";
    static final long SLOW_MS = 60_000L;
    static final long FAST_MS = 5_000L;
    static final long MAX_WATCH_MS = 10 * 60_000L;
    static final long LIVE_MS = 30_000L;
    static final long PUBLISH_GAP_MS = 250L;
    static final int MAX_MSGS_PER_PASS = 20;
    private static final long[] BACKOFF = {5_000, 10_000, 30_000, 60_000, 120_000, 300_000};

    enum Kind { OFF, NO_LOGIN, NOT_CONFIGURED, CONNECTING, CONNECTED, OFFLINE }

    static final class Status {
        final Kind kind;
        final long sent, lastSentAt;
        final int queued;
        final boolean watched;
        final String error;
        Status(Kind kind, long sent, long lastSentAt, int queued, boolean watched, String error) {
            this.kind = kind; this.sent = sent; this.lastSentAt = lastSentAt;
            this.queued = queued; this.watched = watched; this.error = error;
        }
        static Status of(Kind k) { return new Status(k, 0, 0, 0, false, null); }
    }

    interface LinkFactory { MqttLink create(String login); }

    private final BrokerConfig cfg;
    private final LinkFactory linkFactory;
    private final File queueFile;
    final RemoteQueue queue = new RemoteQueue();
    private volatile Status status = Status.of(Kind.OFF);
    private volatile boolean enabled;
    private volatile String login = "";
    private volatile long watchUntil;
    private volatile String meta, metaSent;
    private volatile boolean dirty, reconnectRequested, running;
    private final Object wake = new Object();
    private Thread thread;
    private MqttLink link;
    private int failures;
    private long nextTryAt, sent, lastSentAt, lastPersist;

    RemoteMonitor(BrokerConfig cfg, LinkFactory linkFactory, File queueFile) {
        this.cfg = cfg;
        this.linkFactory = linkFactory;
        this.queueFile = queueFile;
    }

    boolean active() { return enabled && RemotePayload.isValidLogin(login) && cfg.configured(); }
    boolean watched() { return System.currentTimeMillis() < watchUntil; }
    long intervalMs() { return watched() ? FAST_MS : SLOW_MS; }
    synchronized Status status() { return status; }

    void start() {
        if (thread != null && thread.isAlive()) return;
        running = true;
        thread = new Thread(this::loop, "bms-remote");
        thread.setDaemon(true);
        thread.start();
    }

    void update(boolean enabled, String login) {
        boolean changedLogin = login == null || !login.equals(this.login);
        this.enabled = enabled;
        this.login = login == null ? "" : login;
        if (changedLogin) { queue.clear(); watchUntil = 0; metaSent = null; }
        kick();
    }

    void setMeta(String json) {
        if (json != null && !json.equals(meta)) { meta = json; kick(); }
    }

    void submitState(long tsMs, String record) {
        if (!active()) return;
        queue.add(new Outgoing(tsMs, Outgoing.Kind.STATE, record), now());
        dirty = true;
        kick();
    }

    void submitIr(long tsMs, String payload) {
        if (!active()) return;
        queue.add(new Outgoing(tsMs, Outgoing.Kind.IR, payload), now());
        dirty = true;
        kick();
    }

    void onWatch(String payload) {
        Long until = RemotePayload.parseWatchUntilMs(payload);
        if (until == null) return;
        watchUntil = Math.min(until, now() + MAX_WATCH_MS);
        kick();
    }

    void requestReconnect() {
        reconnectRequested = true;
        kick();
    }

    void shutdown() {
        running = false;
        kick();
        List<Outgoing> snap = queue.snapshot();
        MqttLink l = link;
        link = null;
        Thread t = new Thread(() -> {
            if (queueFile != null) {
                try { writeText(queueFile, RemoteQueue.encode(snap)); } catch (Exception ignored) { }
            }
            if (l != null) try { l.disconnect(); } catch (Exception ignored) { }
        }, "bms-remote-stop");
        t.start();
        try { if (thread != null) thread.join(3_000); } catch (InterruptedException ignored) { }
    }

    private void kick() {
        synchronized (wake) { wake.notifyAll(); }
    }

    private long now() { return System.currentTimeMillis(); }

    private void loop() {
        if (queueFile != null && queueFile.isFile()) {
            try { queue.addAll(RemoteQueue.decode(readText(queueFile)), now()); }
            catch (Exception e) { Log.w(TAG, "queue load", e); }
        }
        while (running) {
            try {
                if (!enabled) { drop(); queue.clear(); set(Status.of(Kind.OFF)); }
                else if (!RemotePayload.isValidLogin(login)) { drop(); set(Status.of(Kind.NO_LOGIN)); }
                else if (!cfg.configured()) { drop(); set(Status.of(Kind.NOT_CONFIGURED)); }
                else step(login);
                persistMaybe();
            } catch (Exception e) {
                Log.w(TAG, "loop", e);
            }
            synchronized (wake) {
                try { wake.wait(1_000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void step(String login) {
        if (reconnectRequested) { reconnectRequested = false; nextTryAt = 0; failures = 0; drop(); }
        long t = now();
        MqttLink l = link;
        if (l == null || !l.isConnected()) {
            if (t < nextTryAt) { set(new Status(Kind.OFFLINE, sent, lastSentAt, queue.size(), watched(), lastError)); return; }
            set(new Status(Kind.CONNECTING, sent, lastSentAt, queue.size(), watched(), null));
            drop();
            try {
                l = linkFactory.create(login);
                l.connect(login);
                l.publish(RemotePayload.onlineTopic(login), "1", 1, true);
                l.subscribe(RemotePayload.watchTopic(login), this::onWatch);
                link = l;
                failures = 0;
                lastError = null;
                metaSent = null;
            } catch (Throwable e) {
                try { if (l != null) l.disconnect(); } catch (Exception ignored) { }
                fail(e);
                return;
            }
        }
        try {
            String m = meta;
            if (m != null && !m.equals(metaSent)) {
                l.publish(RemotePayload.metaTopic(login), m, 1, true);
                metaSent = m;
            }
            int n = 0;
            while (n < MAX_MSGS_PER_PASS) {
                Outgoing head = queue.peek();
                if (head == null) break;
                if (head.kind == Outgoing.Kind.IR) {
                    l.publish(RemotePayload.irTopic(login), head.payload, 1, false);
                    queue.removeHead(head);
                } else {
                    List<Outgoing> run = queue.headStates(RemotePayload.BATCH);
                    List<String> recs = new ArrayList<>(run.size());
                    for (Outgoing o : run) recs.add(o.payload);
                    boolean live = run.size() == 1 && now() - run.get(0).ts < LIVE_MS;
                    l.publish(RemotePayload.stateTopic(login), RemotePayload.stateMessage(recs), live ? 0 : 1, false);
                    queue.removeHeadRun(run);
                }
                dirty = true;
                sent++;
                lastSentAt = now();
                n++;
                if (!queue.isEmpty()) {
                    try { Thread.sleep(PUBLISH_GAP_MS); } catch (InterruptedException ie) { return; }
                }
            }
        } catch (Throwable e) {
            drop();
            fail(e);
            return;
        }
        if (!queue.isEmpty()) kick();
        set(new Status(Kind.CONNECTED, sent, lastSentAt, queue.size(), watched(), null));
    }

    private String lastError;

    private void fail(Throwable e) {
        failures++;
        lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Log.w(TAG, "mqtt", e);
        int i = Math.max(0, Math.min(failures - 1, BACKOFF.length - 1));
        nextTryAt = now() + BACKOFF[i];
        set(new Status(Kind.OFFLINE, sent, lastSentAt, queue.size(), watched(), lastError));
    }

    private void drop() {
        MqttLink l = link;
        link = null;
        if (l != null) try { l.disconnect(); } catch (Exception ignored) { }
    }

    private void set(Status s) { status = s; }

    private void persistMaybe() {
        File f = queueFile;
        if (f == null) return;
        long t = now();
        if (!dirty || t - lastPersist < 60_000) return;
        dirty = false;
        lastPersist = t;
        try {
            File tmp = new File(f.getPath() + ".tmp");
            writeText(tmp, RemoteQueue.encode(queue.snapshot()));
            if (!tmp.renameTo(f)) { writeText(f, RemoteQueue.encode(queue.snapshot())); tmp.delete(); }
        } catch (Exception e) { Log.w(TAG, "queue save", e); }
    }

    private static void writeText(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(s.getBytes(StandardCharsets.UTF_8)); } finally { out.close(); }
    }

    private static String readText(File f) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } finally { in.close(); }
    }
}
