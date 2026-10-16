package ua.sister.energy;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Детальні картки (накладка поверх головного екрана). Будуються при відкритті, звільняються при закритті. */
final class Popups {
    static final int FLOW = 1, BATT = 2, TODAY = 3, DAY = 4, INV = 5, WEEK = 6;
    private static final int TILE = 0xFF111A2E, ORG = 0xFFFB923C;

    private final MainActivity a;
    private final FrameLayout root;
    private final float u;
    private FrameLayout overlay;
    private Runnable refresh;
    private int open;
    private int tab; // 0/1 для перемикачів (сьогодні/вчора, тиждень/місяць)

    Popups(MainActivity a, FrameLayout root, float u) { this.a = a; this.root = root; this.u = u; }

    boolean isOpen() { return overlay != null; }

    void close() {
        if (overlay != null) root.removeView(overlay);
        overlay = null; refresh = null; open = 0;
    }

    void refresh() {
        if (refresh == null) return;
        try { refresh.run(); } catch (Exception ignored) { }
    }

    void show(int which) {
        close();
        open = which; tab = 0;
        overlay = new FrameLayout(a);
        overlay.setBackgroundColor(0xCC000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> close()); // торкання поза карткою
        LinearLayout panel = new LinearLayout(a); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(20), px(14), px(20), px(16));
        panel.setBackground(round(0xF5080F1F, px(10), 0x4094A3B8));
        panel.setClickable(true); // торкання всередині не закриває
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
        plp.setMargins(px(26), px(20), px(26), px(20));
        overlay.addView(panel, plp);

        FrameLayout hdr = new FrameLayout(a);
        TextView title = text("", 36, Color.WHITE); title.setTypeface(Typeface.DEFAULT_BOLD);
        hdr.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView x = text("✕  Закрити", 30, Color.WHITE); x.setTypeface(Typeface.DEFAULT_BOLD);
        x.setGravity(Gravity.CENTER); x.setPadding(px(26), px(12), px(26), px(12));
        x.setBackground(round(0xFF1E293B, px(8), 0xFF64748B));
        x.setOnClickListener(v -> close());
        hdr.addView(x, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        panel.addView(hdr, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(a); body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1); blp.setMargins(0, px(12), 0, 0);
        panel.addView(body, blp);

        switch (which) {
            case FLOW: flow(title, body); break;
            case BATT: batt(title, body); break;
            case TODAY: today(title, body); break;
            case DAY: day(title, body); break;
            case INV: inv(title, body); break;
            default: week(title, body); break;
        }
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        refresh();
    }

    // ---------- 1. Потік ----------
    private void flow(TextView title, LinearLayout body) {
        title.setText("Потік енергії зараз"); title.setTextColor(Views.GRN);
        LinearLayout[] r = rows(body, 3);
        Tile sun = tile(r[0], "Сонце"), home = tile(r[0], "Дім"), batt = tile(r[0], "АКБ"), grid = tile(r[0], "Мережа");
        Tile pv1 = tile(r[1], "Панелі PV1"), pv2 = tile(r[1], "Панелі PV2"), gen = tile(r[1], "Генератор"), st = tile(r[1], "Інвертор");
        Tile gv = tile(r[2], "Напруга мережі"), hz = tile(r[2], "Частота"), light = tile(r[2], "Світло в мережі"), upd = tile(r[2], "Оновлено");
        refresh = () -> {
            Snapshot s = a.snapOk();
            if (s == null) { for (Tile t : new Tile[]{sun, home, batt, grid, pv1, pv2, gen, st, gv, hz, light}) t.set("—", "", "немає даних", Views.GRY); }
            else {
                sun.set(Main.n0(s.pv()), "Вт", "разом", Views.YEL);
                home.set(Main.n0(s.load), "Вт", "споживання", Views.PUR);
                double b = -s.batt;
                batt.set(sign(b) + Main.n0(b), "Вт", Math.abs(b) < 30 ? "очікує" : b > 0 ? "заряд" : "розряд", b >= 0 ? Views.GRN : ORG);
                grid.set(Main.n0(s.grid), "Вт", Math.abs(s.grid) < 30 ? "не бере, не віддає" : s.grid > 0 ? "беремо з мережі" : "віддаємо в мережу", Views.BLU);
                pv1.set(Main.n0(s.pv1), "Вт", s.pv1 > 5 ? "працює" : "немає сонця", Views.YEL);
                pv2.set(Main.n0(s.pv2), "Вт", s.pv2 > 5 ? "працює" : "не підключено", Views.YEL);
                double g = Double.isNaN(s.gen) ? 0 : s.gen;
                gen.set(Main.n0(g), "Вт", g > 5 ? "працює" : "вимкнено", Color.WHITE);
                state(st, s);
                gv.set(Main.n0(s.gridV), "В", s.gridOn() ? (s.gridV >= 207 && s.gridV <= 253 ? "норма" : "поза нормою") : "немає", Color.WHITE);
                hz.set(Main.f(s.gridHz, 1), "Гц", s.gridOn() ? "норма" : "", Color.WHITE);
                light.set(s.gridOn() ? "Є" : "Немає", "", "", s.gridOn() ? Views.GRN : Views.RED);
            }
            upd.set(a.snapOkMs == 0 ? "—" : hhmm(a.snapOkMs), "", "кожні 10 с", Color.WHITE);
        };
    }

    // ---------- 2. АКБ ----------
    private void batt(TextView title, LinearLayout body) {
        title.setTextColor(Views.GRN);
        LinearLayout r0 = rows(body, 1)[0];
        Tile soc = tile(r0, "Заряд"), volt = tile(r0, "Напруга"), cur = tile(r0, "Струм"), pow = tile(r0, "Потужність"), eta = tile(r0, "До повного");
        LinearLayout mid = new LinearLayout(a); mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(px(14), px(8), px(14), px(8)); mid.setBackground(round(TILE, px(8), 0));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, 1.6f); mlp.setMargins(px(4), px(4), px(4), px(4));
        body.addView(mid, mlp);
        TextView cellsHdr = text("", 22, 0xFFCBD5E1);
        mid.addView(cellsHdr);
        Views.Cells cells = new Views.Cells(a); cells.labels = px(19);
        mid.addView(cells, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout r2 = rows(body, 1)[0];
        Tile temp = tile(r2, "Температура"), mos = tile(r2, "MOSFET"), cyc = tile(r2, "Цикли"), cap = tile(r2, "Ємність"), soh = tile(r2, "Стан (SOH)");
        refresh = () -> {
            JkBmsProtocol.CellInfo ci = a.cellOk();
            Snapshot s = a.snapOk();
            double capAh = a.prefs.capAh();
            if (ci != null) {
                title.setText("Акумулятор · JK BMS");
                soc.set(ci.soc < 0 ? "—" : String.valueOf(ci.soc), "%", "", Views.GRN);
                volt.set(Main.f(ci.voltage, 1), "В", "", Color.WHITE);
                curTile(cur, ci.current);
                double p = !Double.isNaN(ci.power) ? Math.abs(ci.power) * Math.signum(ci.current) : ci.voltage * ci.current;
                pow.set(sign(p) + Main.f(p / 1000, 2), "кВт", "", p >= 0 ? Views.GRN : ORG);
                double rem = !Double.isNaN(ci.remainAh) && ci.remainAh > 0 ? Math.min(ci.remainAh, capAh) : capAh * Math.max(0, ci.soc) / 100.0;
                etaTile(eta, ci.current, rem, capAh);
                double mn = 9, mx = 0; int imn = 0, imx = 0;
                for (int i = 0; i < ci.cells.length; i++) { if (ci.cells[i] < mn) { mn = ci.cells[i]; imn = i; } if (ci.cells[i] > mx) { mx = ci.cells[i]; imx = i; } }
                SpannableStringBuilder sb = new SpannableStringBuilder("Напруга банок, В — ");
                if (ci.cells.length > 0) {
                    span(sb, "■ макс " + Main.f(mx, 3) + " (№" + (imx + 1) + ")  ", Views.YEL);
                    span(sb, "■ мін " + Main.f(mn, 3) + " (№" + (imn + 1) + ")  ", Views.BLU);
                    sb.append("різниця ").append(Main.n0((mx - mn) * 1000)).append(" мВ · балансування: ");
                    span(sb, ci.balancing ? "працює" : "немає потреби", Views.GRN);
                } else sb.append("немає даних");
                cellsHdr.setText(sb);
                cells.v = ci.cells; cells.invalidate();
                StringBuilder ts = new StringBuilder(); double tmax = Double.NaN;
                for (double t : ci.temps) { if (ts.length() > 0) ts.append(" / "); ts.append(Main.n0(t)); tmax = Double.isNaN(tmax) ? t : Math.max(tmax, t); }
                temp.set(ts.length() == 0 ? "—" : ts.toString(), "°C", "датчики " + ci.temps.length, Color.WHITE);
                mos.set(Main.n0(ci.tempMos), "°C", "", Color.WHITE);
                cyc.set(ci.cycles < 0 ? "—" : String.valueOf(ci.cycles), "", "", Color.WHITE);
                cap.set(Main.n0(Double.isNaN(ci.capacityAh) ? capAh : ci.capacityAh), "А·год", "залишок " + Main.n0(rem), Color.WHITE);
                soh.set(ci.soh < 0 ? "—" : String.valueOf(ci.soh), "%", "", Color.WHITE);
            } else {
                title.setText("Акумулятор · Без BMS");
                boolean ok = s != null;
                soc.set(ok ? Main.n0(s.invSoc) : "—", "%", "за інвертором", Views.GRN);
                volt.set(ok ? Main.f(s.invBattV, 1) : "—", "В", "за інвертором", Color.WHITE);
                double i = ok && s.invBattV > 10 ? -s.batt / s.invBattV : Double.NaN;
                curTile(cur, i);
                double p = ok ? -s.batt : Double.NaN;
                pow.set(Double.isNaN(p) ? "—" : sign(p) + Main.f(p / 1000, 2), "кВт", "", p >= 0 ? Views.GRN : ORG);
                etaTile(eta, i, ok ? capAh * Math.max(0, s.invSoc) / 100.0 : Double.NaN, capAh);
                cellsHdr.setText(a.prefs.bmsMac().isEmpty()
                        ? "BMS не вибрано. Напруги банок, температура і цикли видно лише з JK BMS (Налаштування → Шукати BMS)."
                        : "Немає зв'язку з JK BMS по Bluetooth. Показано дані інвертора. Напруги банок з'являться після підключення.");
                cells.v = new double[0]; cells.invalidate();
                temp.set("—", "", "немає BMS", Views.GRY); mos.set("—", "", "немає BMS", Views.GRY);
                cyc.set("—", "", "немає BMS", Views.GRY); cap.set(Main.n0(capAh), "А·год", "з налаштувань", Color.WHITE);
                soh.set("—", "", "немає BMS", Views.GRY);
            }
        };
    }

    private void curTile(Tile t, double i) {
        if (Double.isNaN(i)) { t.set("—", "", "", Views.GRY); return; }
        if (Math.abs(i) < 0.5) { t.set("0", "А", "очікує", Views.GRY); return; }
        t.set((i > 0 ? "▲ " : "▼ ") + Main.f(Math.abs(i), 1), "А", i > 0 ? "заряджається" : "розряджається", i > 0 ? Views.GRN : ORG);
    }

    private void etaTile(Tile t, double i, double rem, double cap) {
        if (Double.isNaN(i) || Double.isNaN(rem) || Math.abs(i) < 0.5) { t.label.setText("Запас"); t.set("—", "", "струм малий", Views.GRY); return; }
        if (i > 0) { t.label.setText("До повного"); t.set(hm((cap - rem) / i), "год", "", Color.WHITE); }
        else { t.label.setText("Вистачить"); t.set(hm(rem / -i), "год", "при такому споживанні", Color.WHITE); }
    }

    // ---------- 3. Сьогодні ----------
    private void today(TextView title, LinearLayout body) {
        title.setText("Сьогодні"); title.setTextColor(ORG);
        LinearLayout[] r = rows(body, 2);
        Tile pv = tile(r[0], "Вироблено сонцем"), ld = tile(r[0], "Спожито домом"), imp = tile(r[0], "Взято з мережі"), exp = tile(r[0], "Віддано в мережу");
        Tile chg = tile(r[1], "Заряд АКБ"), dis = tile(r[1], "Розряд АКБ"), self = tile(r[1], "Власне покриття"), peak = tile(r[1], "Пік споживання");
        refresh = () -> {
            Snapshot s = a.snapOk();
            long now = System.currentTimeMillis();
            double[] y = a.prefs.demo() ? new double[]{18.4, 13.5, 1.1, 0, 9.8, 6.1} : a.db.dayRow(HistoryDb.day(now - 86400000L));
            pv.set(s == null ? "—" : Main.f(s.todayPv, 1), "кВт·год", yd(y, 0), Views.YEL);
            ld.set(s == null ? "—" : Main.f(s.todayLoad, 1), "кВт·год", yd(y, 1), Views.PUR);
            imp.set(s == null ? "—" : Main.f(s.todayImp, 1), "кВт·год", yd(y, 2), Views.BLU);
            exp.set(s == null ? "—" : Main.f(s.todayExp, 1), "кВт·год", yd(y, 3), Views.BLU);
            chg.set(s == null ? "—" : Main.f(s.todayChg, 1), "кВт·год", yd(y, 4), Views.GRN);
            dis.set(s == null ? "—" : Main.f(s.todayDis, 1), "кВт·год", yd(y, 5), ORG);
            double sf = s != null && s.todayLoad > 0.1 ? Math.max(0, Math.min(100, 100 * (1 - s.todayImp / s.todayLoad))) : Double.NaN;
            double ys = y != null && y[1] > 0.1 ? Math.max(0, Math.min(100, 100 * (1 - y[2] / y[1]))) : Double.NaN;
            self.set(Main.n0(sf), "%", Double.isNaN(ys) ? "" : "вчора " + Main.n0(ys) + " %", Views.GRN);
            double[][] h = a.prefs.demo() ? Demo.hourly(now) : a.db.hourly(now);
            double pk = Double.NaN; int ph = 0;
            for (int i = 0; i < 24; i++) if (!Double.isNaN(h[1][i]) && (Double.isNaN(pk) || h[1][i] > pk)) { pk = h[1][i]; ph = i; }
            peak.set(Main.f(pk, 1), "кВт", Double.isNaN(pk) ? "" : String.format(Locale.US, "о %02d:00 (за годину)", ph), Views.PUR);
        };
    }

    private static String yd(double[] y, int i) { return y == null ? "вчора —" : "вчора " + Main.f(y[i], 1); }

    // ---------- 4. Графік дня ----------
    private void day(TextView title, LinearLayout body) {
        title.setText("Графік дня"); title.setTextColor(Views.YEL);
        LinearLayout bar = new LinearLayout(a); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView t0 = chip("Сьогодні", Views.YEL), t1 = chip("Вчора", Views.YEL);
        bar.addView(t0); bar.addView(t1);
        TextView info = text("", 24, 0xFFCBD5E1); info.setGravity(Gravity.END);
        bar.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(bar);
        Views.DayChart ch = new Views.DayChart(a, u * 1.3f);
        FrameLayout box = new FrameLayout(a); box.setBackground(round(TILE, px(8), 0)); box.setPadding(px(10), px(10), px(10), px(6));
        box.addView(ch, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1); lp.setMargins(0, px(10), 0, px(6));
        body.addView(box, lp);
        body.addView(text("— Сонце     — Дім     - - Мережа      (значення — середні за годину)", 22, 0xFFCBD5E1));
        refresh = () -> {
            long now = System.currentTimeMillis();
            ch.d = a.prefs.demo() ? Demo.hourly(now, tab) : a.db.hourly(now, tab);
            int H = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            ch.nowH = tab == 0 ? H : -100; ch.invalidate();
            sel(t0, tab == 0, Views.YEL); sel(t1, tab == 1, Views.YEL);
            int hh = tab == 0 ? H : 12;
            info.setText(String.format(Locale.US, "о %02d:00 → сонце %s кВт · дім %s · мережа %s", hh,
                    Main.f(ch.d[0][hh], 1), Main.f(ch.d[1][hh], 1), Main.f(ch.d[2][hh], 1)));
        };
        t0.setOnClickListener(v -> { tab = 0; refresh(); });
        t1.setOnClickListener(v -> { tab = 1; refresh(); });
    }

    // ---------- 5. Інвертор ----------
    private void inv(TextView title, LinearLayout body) {
        title.setText("Інвертор Deye " + Main.n0(a.prefs.ratedW() / 1000) + " кВт"); title.setTextColor(Views.BLU);
        LinearLayout[] r = rows(body, 2);
        Tile st = tile(r[0], "Стан"), ld = tile(r[0], "Навантаження"), th = tile(r[0], "Темп. радіатора"), td = tile(r[0], "Темп. DC");
        Tile gv = tile(r[1], "Напруга мережі"), hz = tile(r[1], "Частота"), mode = tile(r[1], "Режим"), rel = tile(r[1], "Реле мережі");
        LinearLayout list = new LinearLayout(a); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(16), px(10), px(16), px(10)); list.setBackground(round(TILE, px(8), 0));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1.3f); llp.setMargins(px(4), px(4), px(4), px(4));
        body.addView(list, llp);
        refresh = () -> {
            Snapshot s = a.snapOk();
            if (s == null) { for (Tile t : new Tile[]{st, ld, th, td, gv, hz, mode, rel}) t.set("—", "", "немає даних", Views.GRY); }
            else {
                state(st, s);
                double pct = 100 * s.load / a.prefs.ratedW();
                ld.set(Main.n0(pct), "%", Main.n0(s.load) + " Вт з " + Main.n0(a.prefs.ratedW()), pct > 80 ? Views.RED : Color.WHITE);
                th.set(Main.n0(s.tempHs), "°C", "", s.tempHs > 70 ? Views.RED : Color.WHITE);
                td.set(Main.n0(s.tempDc), "°C", "", s.tempDc > 70 ? Views.RED : Color.WHITE);
                gv.set(s.gridOn() ? Main.n0(s.gridV) : "немає", s.gridOn() ? "В" : "", "норма 207–253", s.gridOn() ? Color.WHITE : Views.RED);
                hz.set(Main.f(s.gridHz, 1), "Гц", "", Color.WHITE);
                double sur = s.pv() - s.load;
                if (!s.gridOn()) mode.set("Без мережі", "", "живлення від АКБ і сонця", ORG);
                else if (s.pv() > s.load) mode.set("Від сонця", "", sur > 30 && -s.batt > 30 ? "надлишок — в АКБ" : "", Views.YEL);
                else if (s.grid > 50) mode.set("Від мережі", "", "сонця замало", Views.BLU);
                else mode.set("Від АКБ", "", "", ORG);
                rel.set(s.gridRelay < 0 ? "—" : s.gridRelay == 0 ? "розімкнене" : "замкнене", "", "", Color.WHITE);
            }
            long now = System.currentTimeMillis();
            List<long[]> os;
            if (a.prefs.demo()) { os = new java.util.ArrayList<>(); os.add(new long[]{now - 86400000L - 3 * 3600000L, now - 86400000L - 12 * 60000L}); }
            else os = a.db.outages(now - 7 * 86400000L);
            list.removeAllViews();
            long tot = 0; for (long[] o : os) tot += (o[1] == 0 ? now : o[1]) - o[0];
            SpannableStringBuilder h = new SpannableStringBuilder("Відключення світла за 7 днів — ");
            span(h, os.isEmpty() ? "не було" : os.size() + " раз(и), разом " + hm(tot / 3600000.0), Color.WHITE);
            TextView ht = text("", 26, 0xFFCBD5E1); ht.setText(h); ht.setTypeface(Typeface.DEFAULT_BOLD);
            list.addView(ht);
            int n = 0;
            for (long[] o : os) {
                if (n++ >= 5) break;
                LinearLayout row = new LinearLayout(a); row.setPadding(0, px(8), 0, 0);
                String w = dayWord(o[0], now) + ", " + hhmm(o[0]) + " – " + (o[1] == 0 ? "триває" : hhmm(o[1]));
                row.addView(text(w, 26, 0xFFE2E8F0), new LinearLayout.LayoutParams(0, -2, 1));
                TextView d = text(hm(((o[1] == 0 ? now : o[1]) - o[0]) / 3600000.0), 28, o[1] == 0 ? Views.RED : Color.WHITE);
                d.setTypeface(Typeface.DEFAULT_BOLD); row.addView(d);
                list.addView(row);
            }
        };
    }

    // ---------- 6. Тиждень і місяць ----------
    private void week(TextView title, LinearLayout body) {
        title.setText("Тиждень і місяць"); title.setTextColor(Views.PUR);
        LinearLayout row = new LinearLayout(a);
        body.addView(row, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout left = new LinearLayout(a); left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(px(12), px(10), px(12), px(8)); left.setBackground(round(TILE, px(8), 0));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -1, 2.6f); l1.setMargins(px(4), px(4), px(4), px(4));
        row.addView(left, l1);
        LinearLayout bar = new LinearLayout(a);
        TextView t0 = chip("Тиждень", Views.PUR), t1 = chip("Місяць", Views.PUR);
        bar.addView(t0); bar.addView(t1); left.addView(bar);
        Views.WeekChart ch = new Views.WeekChart(a, u * 1.25f); ch.big = true;
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1); clp.setMargins(0, px(8), 0, px(4));
        left.addView(ch, clp);
        left.addView(text("■ Сонце   ■ Спожито, кВт·год", 22, 0xFFCBD5E1));
        LinearLayout right = new LinearLayout(a); right.setOrientation(LinearLayout.VERTICAL);
        row.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        Tile mon = vtile(right, "За 30 днів"), best = vtile(right, "Найкращий день"), avg = vtile(right, "Сер. споживання");
        refresh = () -> {
            long now = System.currentTimeMillis();
            Snapshot s = a.snapOk();
            Object[][] m;
            if (a.prefs.demo()) m = s == null ? new Object[0][] : Demo.days(now, 30, s);
            else m = a.db.days(now, 30);
            int n = tab == 0 ? Math.min(7, m.length) : m.length;
            Object[][] d = new Object[n][];
            System.arraycopy(m, m.length - n, d, 0, n);
            ch.d = d; ch.invalidate();
            sel(t0, tab == 0, Views.PUR); sel(t1, tab == 1, Views.PUR);
            double sp = 0, sl = 0, bp = 0; long bt = 0; int k = 0;
            for (Object[] r : m) { double p = (Double) r[1], l = (Double) r[2]; sp += p; sl += l; if (l > 0) k++; if (p > bp) { bp = p; bt = (Long) r[0]; } }
            mon.set(Main.n0(sp), "кВт·год", "сонце · спожито " + Main.n0(sl), Views.YEL);
            best.set(Main.f(bp, 1), "кВт·год", bt == 0 ? "" : new SimpleDateFormat("dd.MM", Locale.US).format(new Date(bt)), Views.YEL);
            avg.set(k == 0 ? "—" : Main.f(sl / k, 1), "кВт·год", "на добу", Views.PUR);
        };
        t0.setOnClickListener(v -> { tab = 0; refresh(); });
        t1.setOnClickListener(v -> { tab = 1; refresh(); });
    }

    // ---------- спільне ----------
    private void state(Tile t, Snapshot s) {
        String[] st = {"Очікування", "Самотест", "Працює", "Попередження", "Аварія"};
        t.set(s.state >= 0 && s.state < st.length ? st[s.state] : "—", "", s.state == 2 ? "помилок немає" : s.state >= 3 ? "перевірте інвертор" : "",
                s.state == 2 ? Views.GRN : s.state >= 3 ? Views.RED : Color.WHITE);
    }

    final class Tile {
        final TextView label, value, sub;
        Tile(LinearLayout parent, String l, boolean vertical) {
            LinearLayout c = new LinearLayout(a); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(px(16), px(8), px(12), px(8)); c.setBackground(round(TILE, px(8), 0));
            LinearLayout.LayoutParams lp = vertical ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1);
            lp.setMargins(px(4), px(4), px(4), px(4));
            parent.addView(c, lp);
            label = text(l, 24, 0xFFCBD5E1); value = text("—", 48, Color.WHITE); sub = text("", 20, Views.GRY);
            value.setTypeface(Typeface.DEFAULT_BOLD); value.setPadding(0, px(4), 0, px(4));
            label.setSingleLine(); value.setSingleLine(); sub.setMaxLines(2);
            c.addView(label); c.addView(value); c.addView(sub);
        }
        void set(String v, String unit, String s, int col) {
            SpannableStringBuilder sb = new SpannableStringBuilder(v);
            if (!unit.isEmpty()) {
                int st = sb.length(); sb.append(" ").append(unit);
                sb.setSpan(new RelativeSizeSpan(0.5f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFFCBD5E1), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            value.setText(sb); value.setTextColor(col); sub.setText(s);
        }
    }

    private Tile tile(LinearLayout row, String l) { return new Tile(row, l, false); }
    private Tile vtile(LinearLayout col, String l) { return new Tile(col, l, true); }

    private LinearLayout[] rows(LinearLayout body, int n) {
        LinearLayout[] r = new LinearLayout[n];
        for (int i = 0; i < n; i++) { r[i] = new LinearLayout(a); body.addView(r[i], new LinearLayout.LayoutParams(-1, 0, 1)); }
        return r;
    }

    private TextView chip(String s, int col) {
        TextView t = text(s, 26, Color.WHITE); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(px(24), px(10), px(24), px(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, px(10), 0); t.setLayoutParams(lp);
        return t;
    }

    private void sel(TextView t, boolean on, int col) {
        t.setBackground(round(on ? col : 0xFF0F172A, px(8), on ? 0 : 0xFF475569));
        t.setTextColor(on ? 0xFF0B1020 : Color.WHITE);
    }

    private static void span(SpannableStringBuilder sb, String s, int col) {
        int st = sb.length(); sb.append(s);
        sb.setSpan(new ForegroundColorSpan(col), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String sign(double v) { return v > 0.5 ? "+" : ""; }
    private static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }
    private static String hm(double h) {
        if (Double.isNaN(h) || h < 0 || h > 99) return "—";
        int m = (int) Math.round(h * 60); return m / 60 + ":" + String.format(Locale.US, "%02d", m % 60);
    }
    private static String dayWord(long ts, long now) {
        String d = HistoryDb.day(ts);
        if (d.equals(HistoryDb.day(now))) return "Сьогодні";
        if (d.equals(HistoryDb.day(now - 86400000L))) return "Вчора";
        return new SimpleDateFormat("EE dd.MM", new Locale("uk")).format(new Date(ts));
    }

    private int px(float v) { return Math.round(v * u); }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(a); t.setText(s); t.setTextSize(0, px(size)); t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable round(int fill, int r, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(r);
        if (stroke != 0) g.setStroke(2, stroke);
        return g;
    }
}
