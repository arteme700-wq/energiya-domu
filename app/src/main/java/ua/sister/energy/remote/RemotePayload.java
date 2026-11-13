package ua.sister.energy.remote;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ua.sister.energy.JkBmsProtocol;

/**
 * Remote-monitoring payloads, schema 2 — identical to BMS LCD 0.3.1 so bms-remote
 * can show this login with no server changes.
 *
 * Topics (login = [A-Za-z0-9_-]{3,32}):
 *  bms/<login>/state  — JSON array of positional records
 *  bms/<login>/ir     — one IR event + current IR summary (rare), QoS 1
 *  bms/<login>/meta   — retained: board model/fw/app (only on connect / change)
 *  bms/<login>/online — retained "1"/"0" (LWT)
 *  bms/<login>/watch  — server → tablet: {"until":<epoch s>}
 *
 * State record (positional, ints with fixed scaling):
 *  [0] schema=2  [1] ts (epoch s)  [2] SOC %  [3] pack mV  [4] current cA (0.01 A, + = charge)  [5] power W
 *  [6] remaining Ah×10  [7] cycles  [8] flags (1 charge MOS, 2 discharge MOS, 4 balancing, 8 heating)  [9] error mask
 *  [10] cell[0] mV  [11] cell deltas mV vs cell[0]  [12] temps ×10 °C  [13] MOS temp ×10 °C or null  [14] SOH %
 *  [15] nominal Ah  [16] balance current cA
 */
final class RemotePayload {
    static final int SCHEMA = 2;
    static final int BATCH = 30;
    private static final Pattern LOGIN_RE = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    private static final Pattern UNTIL_RE = Pattern.compile("\"until\"\\s*:\\s*(\\d+)");

    static boolean isValidLogin(String s) { return s != null && LOGIN_RE.matcher(s).matches(); }
    static String stateTopic(String login) { return "bms/" + login + "/state"; }
    static String irTopic(String login) { return "bms/" + login + "/ir"; }
    static String metaTopic(String login) { return "bms/" + login + "/meta"; }
    static String onlineTopic(String login) { return "bms/" + login + "/online"; }
    static String watchTopic(String login) { return "bms/" + login + "/watch"; }

    static String stateRecord(long tsMs, JkBmsProtocol.CellInfo ci) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('[').append(SCHEMA).append(',').append(tsMs / 1000);
        sb.append(',').append(ci.soc);
        sb.append(',').append(round(ci.voltage * 1000));
        sb.append(',').append(round(ci.current * 100));
        sb.append(',').append(round(ci.power));
        sb.append(',').append(round(ci.remainAh * 10));
        sb.append(',').append(ci.cycles < 0 ? 0 : ci.cycles);
        int f = 0;
        if (ci.charging) f |= 1;
        if (ci.discharging) f |= 2;
        if (ci.balancing) f |= 4;
        if (ci.heating) f |= 8;
        sb.append(',').append(f).append(',').append(ci.errorsMask);
        int n = ci.cells == null ? 0 : ci.cells.length;
        int base = 0;
        if (n > 0 && !Double.isNaN(ci.cells[0])) base = (int) Math.round(ci.cells[0] * 1000);
        sb.append(',').append(base).append(",[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            double v = ci.cells[i];
            int mv = Double.isNaN(v) ? base : (int) Math.round(v * 1000);
            sb.append(mv - base);
        }
        sb.append("],[");
        int nt = ci.temps == null ? 0 : ci.temps.length;
        boolean firstT = true;
        for (int i = 0; i < nt; i++) {
            if (Double.isNaN(ci.temps[i])) continue;
            if (!firstT) sb.append(',');
            firstT = false;
            sb.append((int) Math.round(ci.temps[i] * 10));
        }
        sb.append("],");
        if (Double.isNaN(ci.tempMos)) sb.append("null");
        else sb.append((int) Math.round(ci.tempMos * 10));
        sb.append(',').append(ci.soh < 0 ? 0 : ci.soh);
        sb.append(',').append(Double.isNaN(ci.capacityAh) ? 0 : (int) Math.round(ci.capacityAh));
        sb.append(',').append(Double.isNaN(ci.balanceCurrent) ? 0 : (int) Math.round(ci.balanceCurrent * 100));
        sb.append(']');
        return sb.toString();
    }

    static String stateMessage(List<String> records) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(records.get(i));
        }
        return sb.append(']').toString();
    }

    /** Board/app description. chemistry may be null (omitted). */
    static String meta(String model, String hw, String sw, String name, String app, String chemistry, Integer cells) {
        Json j = new Json();
        j.num("v", SCHEMA);
        j.str("app", app);
        if (notBlank(model)) j.str("mdl", model);
        if (notBlank(hw)) j.str("hw", hw);
        if (notBlank(sw)) j.str("sw", sw);
        if (notBlank(name)) j.str("nm", name);
        if (notBlank(chemistry)) j.str("chem", chemistry);
        if (cells != null) j.num("n", cells);
        return j.end();
    }

    static String irEvent(IrCalculator.IrEvent e, IrCalculator.IrSummary sum) {
        Json j = new Json();
        j.num("v", SCHEMA);
        j.num("ts", e.tsMs / 1000);
        j.dec("dt", e.dtS, 2);
        j.dec("ia", e.iA, 2);
        j.dec("ib", e.iB, 2);
        j.dec("va", e.vA, 3);
        j.dec("vb", e.vB, 3);
        j.dec("r", e.rPack, 3);
        j.arrN("g", e.rGroups, 3);
        j.num("soc", e.soc);
        if (e.tempC != null) j.dec("t", e.tempC, 1);
        j.bool("ok", e.ok);
        if (sum != null) {
            j.obj("S", s -> {
                s.str("st", sum.status.name());
                s.num("n", sum.goodEvents);
                s.num("rej", sum.rejected);
                if (sum.rPack != null) s.dec("rp", sum.rPack, 3);
                if (sum.rGroups != null && !sum.rGroups.isEmpty()) s.arrN("rg", sum.rGroups, 3);
            });
        }
        return j.end();
    }

    /** Parses the server's watch message: {"until":<epoch s>} or a bare number. Returns epoch ms or null. */
    static Long parseWatchUntilMs(String payload) {
        if (payload == null) return null;
        String t = payload.trim();
        Matcher m = UNTIL_RE.matcher(t);
        String num = m.find() ? m.group(1) : (t.matches("\\d+") ? t : null);
        if (num == null) return null;
        try {
            long v = Long.parseLong(num);
            return v < 100_000_000_000L ? v * 1000 : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    private static long round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v);
    }

    /** Minimal JSON object writer (numbers in Locale.US, NaN/Inf → null). */
    static final class Json {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;
        private void key(String k) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(k).append("\":");
        }
        void num(String k, Number v) { key(k); sb.append(v); }
        void dec(String k, double v, int d) { key(k); appendDec(v, d); }
        void bool(String k, boolean v) { key(k); sb.append(v ? 1 : 0); }
        void str(String k, String v) { key(k); appendStr(v); }
        void obj(String k, java.util.function.Consumer<Json> body) {
            key(k);
            Json inner = new Json();
            body.accept(inner);
            sb.append(inner.end());
        }
        void arrN(String k, List<Double> v, int d) {
            key(k);
            sb.append('[');
            for (int i = 0; i < v.size(); i++) {
                if (i > 0) sb.append(',');
                Double x = v.get(i);
                if (x == null) sb.append("null");
                else appendDec(x, d);
            }
            sb.append(']');
        }
        private void appendDec(double v, int d) {
            if (Double.isNaN(v) || Double.isInfinite(v)) { sb.append("null"); return; }
            if (d == 0) { sb.append(Math.round(v)); return; }
            String s = String.format(Locale.US, "%." + d + "f", v);
            if (s.indexOf('.') >= 0) {
                int end = s.length();
                while (end > 0 && s.charAt(end - 1) == '0') end--;
                if (end > 0 && s.charAt(end - 1) == '.') end--;
                s = s.substring(0, end);
            }
            if ("-0".equals(s)) s = "0";
            sb.append(s);
        }
        private void appendStr(String v) {
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '"') sb.append("\\\"");
                else if (c == '\\') sb.append("\\\\");
                else if (c < ' ') sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                else sb.append(c);
            }
            sb.append('"');
        }
        String end() { return sb.append('}').toString(); }
    }
}
