package ua.sister.energy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "SisterEnergy";
    private static final long POLL_MS = 10000, SAMPLE_MS = 60000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler bg, upd;
    private final Runnable updateTask = new Runnable() {
        @Override public void run() { Log.i(TAG, "update: " + Updater.check(MainActivity.this)); upd.postDelayed(this, 6 * 3600 * 1000L); }
    };
    private Prefs prefs;
    private HistoryDb db;
    private BmsReader bms;
    private float u;

    private volatile Snapshot snap;
    private volatile long snapOkMs;
    private volatile JkBmsProtocol.CellInfo cell;
    private volatile long cellMs;
    private volatile boolean bmsLink;
    private long lastSample;
    private Boolean lastGridOn;

    private TextView clock, badgeInv, badgeBms, socBig, battSub, cellMinMax, cellDelta, battEta, battEtaLbl;
    private TextView tPv, tLoad, tGrid, tBatt, tSelf, iState, iLoad, iTemp, iGrid, iOutage, weekAvg;
    private HouseFlowView flow;
    private Views.Bar socBar, loadBar;
    private Views.Cells cellsView;
    private Views.DayChart day;
    private Views.WeekChart week;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        prefs = new Prefs(this);
        db = new HistoryDb(this);
        int wpx = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        u = wpx / 1280f;
        setContentView(build());
        worker = new HandlerThread("poll"); worker.start(); bg = new Handler(worker.getLooper());
        String was = getSharedPreferences("cfg", MODE_PRIVATE).getString("last_version", null);
        if (was != null && !was.equals(BuildConfig.VERSION_NAME))
            android.widget.Toast.makeText(this, "Оновлено до " + BuildConfig.VERSION_NAME, android.widget.Toast.LENGTH_LONG).show();
        prefs.edit().putString("last_version", BuildConfig.VERSION_NAME).apply();
        HandlerThread ut = new HandlerThread("update"); ut.start(); upd = new Handler(ut.getLooper());
        upd.postDelayed(updateTask, 30000);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        prefs = new Prefs(this);
        bg.removeCallbacksAndMessages(null);
        bg.post(poll);
        if (bms != null) bms.stop();
        bms = null; bmsLink = false; cell = null; snap = null;
        if (!prefs.demo() && !prefs.bmsMac().isEmpty()) {
            bms = new BmsReader(this, prefs.bmsMac(), new BmsReader.Listener() {
                @Override public void onCell(JkBmsProtocol.CellInfo ci) { cell = ci; cellMs = System.currentTimeMillis(); }
                @Override public void onStatus(boolean c) { bmsLink = c; }
            });
            bms.start();
        }
        ui.removeCallbacksAndMessages(null);
        ui.post(tick);
        flow.setRunning(true);
    }

    @Override protected void onPause() { super.onPause(); flow.setRunning(false); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bms != null) bms.stop();
        worker.quitSafely();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @Override public void onBackPressed() { /* кіоск: назад не закриває */ }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------- опитування ----------

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            try {
                if (prefs.demo()) {
                    Snapshot s = Demo.snapshot(now);
                    snap = s; snapOkMs = now;
                    cell = Demo.bms(now, s); cellMs = now; bmsLink = true;
                } else if (!prefs.ip().isEmpty() && !prefs.serial().isEmpty()) {
                    long serial = Long.parseLong(prefs.serial().trim());
                    Snapshot s = new DeyeReader(new SolarmanV5(prefs.ip().trim(), prefs.port(), serial, prefs.slave())).read();
                    snap = s; snapOkMs = now;
                    if (now - lastSample >= SAMPLE_MS) {
                        JkBmsProtocol.CellInfo ci = cell;
                        db.addSample(s, ci != null ? ci.soc : s.invSoc);
                        lastSample = now;
                    }
                    boolean on = s.gridOn();
                    if (lastGridOn != null && lastGridOn != on) db.gridChanged(on, now);
                    lastGridOn = on;
                }
            } catch (Exception e) {
                Log.w(TAG, "poll", e);
            }
            bg.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { render(); } catch (Exception e) { Log.w(TAG, "render", e); }
            ui.postDelayed(this, 5000);
        }
    };

    // ---------- відображення ----------

    private void render() {
        long now = System.currentTimeMillis();
        boolean demo = prefs.demo();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)));
        Snapshot s = snap;
        boolean invOk = s != null && now - snapOkMs < 60000;
        String t = s == null ? "—" : new SimpleDateFormat("HH:mm", Locale.US).format(new Date(snapOkMs));
        badge(badgeInv, invOk, (demo ? "демо · " : "") + "логер · " + t);
        JkBmsProtocol.CellInfo ci = cell;
        boolean bmsOk = ci != null && now - cellMs < 60000;
        badge(badgeBms, bmsOk, (demo ? "демо · " : "") + "BMS · " + (bmsOk ? "BLE" : bmsLink ? "з'єднання" : prefs.bmsMac().isEmpty() ? "не вибрано" : "немає"));

        flow.ok = invOk;
        if (invOk) {
            flow.pv1 = s.pv1; flow.pv2 = s.pv2; flow.batt = s.batt; flow.load = s.load; flow.grid = s.grid; flow.gen = Double.isNaN(s.gen) ? 0 : s.gen;
            flow.gridOn = s.gridOn(); flow.soc = cell != null && cell.soc >= 0 ? cell.soc : (int) s.invSoc;
        }
        flow.invalidate();

        // АКБ
        double soc = bmsOk && ci.soc >= 0 ? ci.soc : invOk ? s.invSoc : Double.NaN;
        socBig.setText(Double.isNaN(soc) ? "—" : Main.n0(soc) + "%");
        socBar.frac = Double.isNaN(soc) ? 0 : (float) (soc / 100); socBar.invalidate();
        if (bmsOk) {
            battCurrent(ci.current);
            double mn = 9, mx = 0;
            for (double v : ci.cells) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
            cellsView.v = ci.cells; cellsView.invalidate();
            cellMinMax.setText(ci.cells.length == 0 ? "—" : val(Main.f(mn, 3) + " / " + Main.f(mx, 3), "В"));
            double tmax = Double.NaN; for (double x : ci.temps) tmax = Double.isNaN(tmax) ? x : Math.max(tmax, x);
            SpannableStringBuilder d = val(ci.cells.length == 0 ? "—" : Main.n0((mx - mn) * 1000), "мВ");
            d.append(" · "); d.append(val(Main.f(tmax, 0), "°C"));
            cellDelta.setText(d);
            double cap = prefs.capAh();
            double rem = !Double.isNaN(ci.remainAh) && ci.remainAh > 0 ? Math.min(ci.remainAh, cap) : cap * Math.max(0, ci.soc) / 100.0;
            if (ci.current > 0.5) {
                battEtaLbl.setText("До повного"); battEta.setText(Main.dur((cap - rem) / ci.current));
            } else if (ci.current < -0.5) {
                battEtaLbl.setText("Вистачить"); battEta.setText(Main.dur(rem / -ci.current));
            } else { battEtaLbl.setText("Струм"); battEta.setText("очікує"); }
        } else {
            if (invOk && !Double.isNaN(s.invBattV) && s.invBattV > 10) battCurrent(-s.batt / s.invBattV); else { battSub.setText("немає даних"); battSub.setTextColor(Views.GRY); }
            cellsView.v = new double[0]; cellsView.invalidate();
            cellMinMax.setText("—"); cellDelta.setText("—"); battEta.setText("—");
        }

        // Сьогодні
        if (invOk) {
            tPv.setText(val(Main.f(s.todayPv, 1), "кВт·год"));
            tLoad.setText(val(Main.f(s.todayLoad, 1), "кВт·год"));
            tGrid.setText(Main.f(s.todayImp, 1) + " / " + Main.f(s.todayExp, 1));
            tBatt.setText(Main.f(s.todayChg, 1) + " / " + Main.f(s.todayDis, 1));
            double self = s.todayLoad > 0.1 ? Math.max(0, Math.min(100, 100 * (1 - s.todayImp / s.todayLoad))) : Double.NaN;
            tSelf.setText(val(Main.n0(self), "%"));
            String[] st = {"Очікування", "Самотест", "Працює", "Попередження", "Аварія"};
            iState.setText(s.state >= 0 && s.state < st.length ? st[s.state] : "—");
            iState.setTextColor(s.state == 2 ? Views.GRN : s.state >= 3 ? Views.RED : Views.TXT);
            double pct = 100 * s.load / prefs.ratedW();
            iLoad.setText(val(Main.n0(pct), "% від " + Main.n0(prefs.ratedW() / 1000) + " кВт"));
            loadBar.frac = (float) (pct / 100); loadBar.invalidate();
            iTemp.setText(val(Main.n0(s.tempHs) + " / " + Main.n0(s.tempDc), "°C"));
            SpannableStringBuilder g = val(Main.f(s.gridV, 1), "В"); g.append(" "); g.append(val(Main.f(s.gridHz, 2), "Гц"));
            iGrid.setText(g);
        }
        long[] o = demo ? new long[]{now - 86400000L - 3 * 3600000L, now - 86400000L - 12 * 60000L} : db.lastOutage();
        if (o == null) iOutage.setText("не було");
        else if (o[1] == 0) { iOutage.setText("триває " + Main.dur((now - o[0]) / 3600000.0)); iOutage.setTextColor(Views.RED); }
        else { iOutage.setText(dayWord(o[0], now) + " " + Main.dur((o[1] - o[0]) / 3600000.0)); iOutage.setTextColor(Views.TXT); }

        // Графіки
        day.d = demo ? Demo.hourly(now) : db.hourly(now);
        Calendar c = Calendar.getInstance(); day.nowH = c.get(Calendar.HOUR_OF_DAY); day.invalidate();
        week.d = demo ? (invOk ? Demo.week(now, s) : new Object[0][]) : db.week(now);
        double sum = 0; int n = 0;
        for (Object[] r : week.d) if ((Double) r[2] > 0) { sum += (Double) r[2]; n++; }
        weekAvg.setText(n == 0 ? "" : "сер. " + Main.f(sum / n, 1) + " кВт·год/добу");
        week.invalidate();
    }

    /** Струм АКБ зі стрілкою: ▲ заряд (зелений), ▼ розряд (помаранчевий). */
    private void battCurrent(double a) {
        if (Double.isNaN(a)) { battSub.setText("—"); return; }
        if (Math.abs(a) < 0.5) { battSub.setText("● 0 А  очікує"); battSub.setTextColor(Views.GRY); socBig.setTextColor(Views.GRN); return; }
        boolean chg = a > 0;
        SpannableStringBuilder sb = new SpannableStringBuilder((chg ? "▲ " : "▼ ") + Main.f(Math.abs(a), 1) + " А");
        int st = sb.length(); sb.append(chg ? "  заряд" : "  розряд");
        sb.setSpan(new RelativeSizeSpan(0.55f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        battSub.setText(sb);
        int col = chg ? Views.GRN : 0xFFFB923C;
        battSub.setTextColor(col); socBig.setTextColor(col);
    }

    private static String sign(double v) { return v > 0.05 ? "+" : ""; }

    private static String dayWord(long ts, long now) {
        String a = HistoryDb.day(ts);
        if (a.equals(HistoryDb.day(now))) return "сьогодні";
        if (a.equals(HistoryDb.day(now - 86400000L))) return "вчора";
        return new SimpleDateFormat("dd.MM", Locale.US).format(new Date(ts));
    }

    private void badge(TextView v, boolean ok, String text) {
        v.setText("● " + text);
        v.setTextColor(ok ? Views.GRN : 0xFFFBBF24);
    }

    private SpannableStringBuilder val(String big, String unit) {
        SpannableStringBuilder sb = new SpannableStringBuilder(big);
        int st = sb.length();
        sb.append(" ").append(unit);
        sb.setSpan(new RelativeSizeSpan(0.55f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Views.DIM), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ---------- побудова інтерфейсу ----------

    private int px(float v) { return Math.round(v * u); }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        ImageView bgImg = new ImageView(this);
        bgImg.setImageResource(R.drawable.bg); bgImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bgImg, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackgroundColor(0xB8020617);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // заголовок
        FrameLayout hdr = new FrameLayout(this); hdr.setPadding(px(10), 0, px(10), 0);
        col.addView(hdr, new LinearLayout.LayoutParams(-1, px(50)));
        LinearLayout left = new LinearLayout(this); left.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("⚡", 20, Color.WHITE); logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(0xFF059669, px(5), 0));
        left.addView(logo, new LinearLayout.LayoutParams(px(32), px(32)));
        TextView title = text("  Енергія", 23, Color.WHITE); title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        left.addView(text("   Deye 8 кВт · JK BMS", 16, Views.DIM));
        left.setOnLongClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); return true; });
        hdr.addView(left, new FrameLayout.LayoutParams(-2, -1, Gravity.START | Gravity.CENTER_VERTICAL));
        clock = text("--:--", 32, Views.TXT);
        clock.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        clock.setLetterSpacing(0.08f);
        hdr.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout right = new LinearLayout(this); right.setGravity(Gravity.CENTER_VERTICAL);
        badgeInv = badgeView(); badgeBms = badgeView();
        right.addView(badgeInv); right.addView(badgeBms);
        TextView gear = text("  ⚙", 24, Views.GRY);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        right.addView(gear);
        hdr.addView(right, new FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));

        // сітка 3×2
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(px(4), 0, px(4), px(4));
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout r1 = row(grid), r2 = row(grid);

        // 1 потік
        LinearLayout c1 = card(r1, "Потік зараз", Views.GRN);
        flow = new HouseFlowView(this); c1.addView(flow, new LinearLayout.LayoutParams(-1, 0, 1));

        // 2 АКБ
        LinearLayout c2 = card(r1, "Акумулятор · JK BMS", Views.GRN);
        // головний вигляд: лише SOC і струм; решта даних — для майбутньої детальної картки
        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL); mid.setGravity(Gravity.CENTER);
        socBig = text("—", 120, Views.GRN); socBig.setTypeface(Typeface.DEFAULT_BOLD); socBig.setGravity(Gravity.CENTER);
        battSub = text("", 44, Views.GRY); battSub.setTypeface(Typeface.DEFAULT_BOLD); battSub.setGravity(Gravity.CENTER);
        battSub.setPadding(0, px(10), 0, 0);
        mid.addView(socBig); mid.addView(battSub);
        c2.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1));
        c2.setOnClickListener(v -> android.widget.Toast.makeText(this, "Детальна картка АКБ — у наступній версії", android.widget.Toast.LENGTH_SHORT).show());
        // поза розміткою (дані оновлюються, показ — у детальній картці)
        socBar = new Views.Bar(this, 0xFF10B981);
        cellsView = new Views.Cells(this);
        LinearLayout detail = new LinearLayout(this); detail.setOrientation(LinearLayout.VERTICAL);
        cellMinMax = kv(detail, "Комірки мін / макс");
        cellDelta = kv(detail, "Різниця · темп.");
        battEtaLbl = new TextView(this);
        battEta = kv(detail, "До повного", battEtaLbl);

        // 3 сьогодні
        LinearLayout c3 = card(r1, "Сьогодні", 0xFFFBBF24);
        tPv = kv(c3, "☀ Вироблено");
        tLoad = kv(c3, "🏠 Спожито");
        tGrid = kv(c3, "🔌 З мережі / в мережу");
        tBatt = kv(c3, "🔋 Заряд / розряд");
        tSelf = kv(c3, "Власне покриття"); tSelf.setTextColor(Views.GRN);

        // 4 графік дня
        LinearLayout c4 = card(r2, "Графік дня", Views.YEL);
        day = new Views.DayChart(this, u); c4.addView(day, new LinearLayout.LayoutParams(-1, 0, 1));
        c4.addView(legend(new String[]{"СЕС", "Дім", "Мережа"}, new int[]{Views.YEL, Views.PUR, Views.BLU}, null));

        // 5 інвертор
        LinearLayout c5 = card(r2, "Інвертор", Views.BLU);
        iState = kv(c5, "Стан");
        iLoad = kv(c5, "Навантаження");
        loadBar = new Views.Bar(this, Views.BLU);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, px(8)); blp.setMargins(0, px(4), 0, px(4));
        c5.addView(loadBar, blp);
        iTemp = kv(c5, "Темп. радіатор / DC");
        iGrid = kv(c5, "Мережа");
        iOutage = kv(c5, "Останнє відключення");

        // 6 тиждень
        LinearLayout c6 = card(r2, "Тиждень", Views.PUR);
        week = new Views.WeekChart(this, u); c6.addView(week, new LinearLayout.LayoutParams(-1, 0, 1));
        weekAvg = text("", 15, Views.GRY);
        c6.addView(legend(new String[]{"СЕС", "Спожито"}, new int[]{Views.YEL, Views.PUR}, weekAvg));
        return root;
    }

    private TextView badgeView() {
        TextView t = text("", 15, Views.GRN);
        t.setPadding(px(9), px(3), px(9), px(3));
        t.setBackground(round(0, px(5), 0xFF334155));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(px(6), 0, 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        parent.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
        return r;
    }

    private LinearLayout card(LinearLayout row, String title, int color) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(14), px(10), px(14), px(10));
        c.setBackground(round(0xD9020617, px(6), 0x2694A3B8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(px(2), px(2), px(2), px(2));
        row.addView(c, lp);
        TextView t = text(title.toUpperCase(new Locale("uk")), 15, color);
        t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.15f); t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, px(4));
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private TextView kv(LinearLayout card, String label) { return kv(card, label, new TextView(this)); }

    private TextView kv(LinearLayout card, String label, TextView lbl) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(2), px(5), px(2), px(5));
        lbl.setText(label); lbl.setTextSize(0, px(18)); lbl.setTextColor(Views.GRY);
        r.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text("—", 26, Views.TXT); v.setTypeface(Typeface.DEFAULT_BOLD); v.setGravity(Gravity.END);
        r.addView(v);
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this); line.setBackgroundColor(0x1A94A3B8);
        card.addView(line, new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    private View legend(String[] names, int[] cols, TextView extra) {
        LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER);
        for (int i = 0; i < names.length; i++) {
            View sw = new View(this); sw.setBackgroundColor(cols[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(16), px(4)); lp.setMargins(px(12), 0, px(6), 0);
            l.addView(sw, lp);
            l.addView(text(names[i], 15, Views.GRY));
        }
        if (extra != null) { extra.setPadding(px(20), 0, 0, 0); l.addView(extra); }
        return l;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(0, px(size)); t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable round(int fill, int r, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(r);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }
}
