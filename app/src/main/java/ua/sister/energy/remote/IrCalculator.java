package ua.sister.energy.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Port of BMS LCD IrCalculator (and homeauto bms_ir.py): R = ΔU/ΔI on a current step
 * between consecutive frames. Pure logic, no Android, no BMS writes.
 */
final class IrCalculator {
    static final double MIN_STEP_A = 15.0;
    static final double MAX_DT_S = 6.5;
    static final double PRE_STABLE_A = 2.0;
    static final int SOC_MIN = 15, SOC_MAX = 95;
    static final double T_MIN = 5.0, T_MAX = 45.0;
    static final double PACK_MAX_MOHM = 60.0, GROUP_MAX_MOHM = 10.0;
    static final double OUTLIER_X = 5.0;
    static final int MIN_EVENTS_IR = 10, MIN_EVENTS_PRELIM = 3;

    static final class IrSample {
        final long tsMs;
        final double volts, amps;
        final double[] cells;
        final int soc;
        final Double tempC;
        final long errorsMask;
        IrSample(long tsMs, double volts, double amps, double[] cells, int soc, Double tempC, long errorsMask) {
            this.tsMs = tsMs; this.volts = volts; this.amps = amps; this.cells = cells;
            this.soc = soc; this.tempC = tempC; this.errorsMask = errorsMask;
        }
    }

    /** R in mΩ. */
    static final class IrEvent {
        final long tsMs;
        final double dtS, iA, iB, vA, vB, rPack;
        final List<Double> rGroups;
        final int soc;
        final Double tempC;
        final boolean ok;
        IrEvent(long tsMs, double dtS, double iA, double iB, double vA, double vB,
                double rPack, List<Double> rGroups, int soc, Double tempC, boolean ok) {
            this.tsMs = tsMs; this.dtS = dtS; this.iA = iA; this.iB = iB; this.vA = vA; this.vB = vB;
            this.rPack = rPack; this.rGroups = rGroups; this.soc = soc; this.tempC = tempC; this.ok = ok;
        }
        IrEvent withOk(boolean v) {
            return new IrEvent(tsMs, dtS, iA, iB, vA, vB, rPack, rGroups, soc, tempC, v);
        }
    }

    enum IrStatus { COLLECTING, PRELIMINARY, READY }

    static final class IrSummary {
        final IrStatus status;
        final int goodEvents, rejected;
        final Double rPack;
        final List<Double> rGroups;
        IrSummary(IrStatus status, int goodEvents, Double rPack, List<Double> rGroups, int rejected) {
            this.status = status; this.goodEvents = goodEvents; this.rPack = rPack;
            this.rGroups = rGroups; this.rejected = rejected;
        }
    }

    private IrSample pre, a;
    private final ArrayList<IrEvent> events = new ArrayList<>();
    private final int maxEvents;
    private final double minStepA;

    IrCalculator() { this(MIN_STEP_A, 200); }
    IrCalculator(double minStepA, int maxEvents) { this.minStepA = minStepA; this.maxEvents = maxEvents; }

    synchronized List<IrEvent> events() { return new ArrayList<>(events); }

    /** Feed a sample; returns an event (ok or outlier) if a step was detected. */
    synchronized IrEvent feed(IrSample b) {
        IrSample p = pre, prev = a;
        pre = prev;
        a = b;
        if (prev == null) return null;
        IrEvent ev = tryEvent(p, prev, b);
        if (ev == null) return null;
        ArrayList<Double> good = new ArrayList<>();
        for (IrEvent e : events) if (e.ok) good.add(e.rPack);
        if (good.size() > 200) good = new ArrayList<>(good.subList(good.size() - 200, good.size()));
        boolean ok = true;
        if (good.size() >= 10) {
            double m = median(good);
            if (m > 0 && (ev.rPack > m * OUTLIER_X || ev.rPack < m / OUTLIER_X)) ok = false;
        }
        IrEvent out = ev.withOk(ok);
        events.add(out);
        while (events.size() > maxEvents) events.remove(0);
        return out;
    }

    IrEvent tryEvent(IrSample pre, IrSample a, IrSample b) {
        if (a.cells == null || b.cells == null || a.cells.length == 0 || a.cells.length != b.cells.length) return null;
        double dt = (b.tsMs - a.tsMs) / 1000.0;
        if (dt <= 0 || dt > MAX_DT_S) return null;
        double di = b.amps - a.amps;
        if (Math.abs(di) < minStepA) return null;
        if (pre != null) {
            if (Math.abs(pre.amps - a.amps) > PRE_STABLE_A || (a.tsMs - pre.tsMs) / 1000.0 > MAX_DT_S * 2) return null;
        }
        if (a.errorsMask != 0L || b.errorsMask != 0L) return null;
        if (b.soc < SOC_MIN || b.soc > SOC_MAX) return null;
        Double t = b.tempC != null ? b.tempC : a.tempC;
        if (t == null) return null;
        if (t < T_MIN || t > T_MAX) return null;
        double r = (b.volts - a.volts) / di * 1000.0;
        if (!(r > 0 && r <= PACK_MAX_MOHM)) return null;
        ArrayList<Double> rg = new ArrayList<>(b.cells.length);
        for (int k = 0; k < b.cells.length; k++) {
            double g = (b.cells[k] - a.cells[k]) / di * 1000.0;
            rg.add(g > 0 && g <= GROUP_MAX_MOHM ? g : null);
        }
        return new IrEvent(b.tsMs, dt, a.amps, b.amps, a.volts, b.volts, r, rg, b.soc, t, true);
    }

    synchronized IrSummary summary() {
        ArrayList<IrEvent> good = new ArrayList<>();
        for (IrEvent e : events) if (e.ok) good.add(e);
        int rej = events.size() - good.size();
        IrStatus status = good.size() >= MIN_EVENTS_IR ? IrStatus.READY
                : good.size() >= MIN_EVENTS_PRELIM ? IrStatus.PRELIMINARY : IrStatus.COLLECTING;
        if (good.size() < MIN_EVENTS_PRELIM) return new IrSummary(status, good.size(), null, Collections.emptyList(), rej);
        int n = 0;
        for (IrEvent e : good) if (e.rGroups.size() > n) n = e.rGroups.size();
        ArrayList<Double> groups = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            ArrayList<Double> v = new ArrayList<>();
            for (IrEvent e : good) if (k < e.rGroups.size() && e.rGroups.get(k) != null) v.add(e.rGroups.get(k));
            groups.add(v.isEmpty() ? null : median(v));
        }
        ArrayList<Double> packs = new ArrayList<>();
        for (IrEvent e : good) packs.add(e.rPack);
        return new IrSummary(status, good.size(), median(packs), groups, rej);
    }

    static double median(List<Double> v) {
        ArrayList<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
    }
}
