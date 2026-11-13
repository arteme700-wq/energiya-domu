package ua.sister.energy.remote;

import java.util.ArrayList;
import java.util.List;

/** One outgoing MQTT message waiting for the broker. ts = original sample time (also inside the payload). */
final class Outgoing {
    enum Kind { STATE, IR }
    final long ts;
    final Kind kind;
    final String payload;
    Outgoing(long ts, Kind kind, String payload) {
        this.ts = ts;
        this.kind = kind;
        this.payload = payload;
    }
}

/**
 * Bounded offline outbox (pure logic, thread-safe). Keeps at most 24 h of data:
 *  - everything older than maxAgeMs is dropped;
 *  - STATE records older than fullResMs are thinned to one per thinStepMs (60 s);
 *  - hard caps protect memory. Order of delivery = oldest first.
 */
final class RemoteQueue {
    private final long maxAgeMs, fullResMs, thinStepMs;
    private final int softStateCap, hardStateCap, irCap;
    private final ArrayList<Outgoing> items = new ArrayList<>();

    RemoteQueue() { this(24 * 3600_000L, 120_000L, 59_000L, 30, 1500, 500); }

    RemoteQueue(long maxAgeMs, long fullResMs, long thinStepMs, int softStateCap, int hardStateCap, int irCap) {
        this.maxAgeMs = maxAgeMs;
        this.fullResMs = fullResMs;
        this.thinStepMs = thinStepMs;
        this.softStateCap = softStateCap;
        this.hardStateCap = hardStateCap;
        this.irCap = irCap;
    }

    synchronized int size() { return items.size(); }
    synchronized boolean isEmpty() { return items.isEmpty(); }
    synchronized List<Outgoing> snapshot() { return new ArrayList<>(items); }
    synchronized Outgoing peek() { return items.isEmpty() ? null : items.get(0); }
    synchronized void clear() { items.clear(); }

    synchronized boolean removeHead(Outgoing o) {
        if (!items.isEmpty() && items.get(0) == o) { items.remove(0); return true; }
        return false;
    }

    synchronized List<Outgoing> headStates(int max) {
        ArrayList<Outgoing> out = new ArrayList<>();
        for (Outgoing o : items) {
            if (o.kind != Outgoing.Kind.STATE || out.size() >= max) break;
            out.add(o);
        }
        return out;
    }

    synchronized void removeHeadRun(List<Outgoing> run) {
        int i = 0;
        while (i < run.size() && !items.isEmpty() && items.get(0) == run.get(i)) {
            items.remove(0);
            i++;
        }
    }

    synchronized void add(Outgoing o, long now) {
        int i = items.size();
        while (i > 0 && items.get(i - 1).ts > o.ts) i--;
        items.add(i, o);
        compact(now);
    }

    synchronized void addAll(List<Outgoing> list, long now) {
        for (Outgoing o : list) {
            int i = items.size();
            while (i > 0 && items.get(i - 1).ts > o.ts) i--;
            items.add(i, o);
        }
        compact(now);
    }

    synchronized void compact(long now) {
        items.removeIf(it -> now - it.ts > maxAgeMs);
        int states = 0;
        for (Outgoing o : items) if (o.kind == Outgoing.Kind.STATE) states++;
        if (states > softStateCap) {
            long lastKept = Long.MIN_VALUE;
            ArrayList<Outgoing> out = new ArrayList<>(items.size());
            for (Outgoing o : items) {
                if (o.kind == Outgoing.Kind.STATE && now - o.ts > fullResMs) {
                    if (lastKept != Long.MIN_VALUE && o.ts - lastKept < thinStepMs) continue;
                    lastKept = o.ts;
                }
                out.add(o);
            }
            items.clear();
            items.addAll(out);
        }
        trim(Outgoing.Kind.STATE, hardStateCap);
        trim(Outgoing.Kind.IR, irCap);
    }

    private void trim(Outgoing.Kind kind, int cap) {
        int extra = 0;
        for (Outgoing o : items) if (o.kind == kind) extra++;
        extra -= cap;
        if (extra <= 0) return;
        java.util.Iterator<Outgoing> it = items.iterator();
        while (extra > 0 && it.hasNext()) {
            if (it.next().kind == kind) { it.remove(); extra--; }
        }
    }

    /** One message per line: "<ts>\t<S|I>\t<json>" (JSON never contains raw newlines/tabs). */
    static String encode(List<Outgoing> list) {
        StringBuilder sb = new StringBuilder();
        for (Outgoing o : list) {
            sb.append(o.ts).append('\t').append(o.kind == Outgoing.Kind.IR ? 'I' : 'S')
                    .append('\t').append(o.payload).append('\n');
        }
        return sb.toString();
    }

    static List<Outgoing> decode(String text) {
        ArrayList<Outgoing> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            int a = line.indexOf('\t');
            if (a < 0) continue;
            int b = line.indexOf('\t', a + 1);
            if (b < 0) continue;
            try {
                long ts = Long.parseLong(line.substring(0, a));
                char k = line.charAt(a + 1);
                out.add(new Outgoing(ts, k == 'I' ? Outgoing.Kind.IR : Outgoing.Kind.STATE, line.substring(b + 1)));
            } catch (Exception ignored) { }
        }
        return out;
    }
}
