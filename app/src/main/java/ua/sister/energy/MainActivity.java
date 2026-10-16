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
    Prefs prefs;
    HistoryDb db;
    private Popups popups;
    private Notifier notifier;
    private final long startMs = System.currentTimeMillis();
    private BmsReader bms;
    private float u;

    private volatile Snapshot snap;
    volatile long snapOkMs;
    private volatile JkBmsProtocol.CellInfo cell;
    private volatile long cellMs;
    private volatile boolean bmsLink;
    private long lastSample;
    private Boolean lastGridOn;

    private TextView clock, wxHdr, socBig, battSub, battEta;
    private TextView lightBig, lightLbl, lightCount, lightNote, sunNow, sunToday, sunTom, homeNow, homeToday, homeGrid;
    private TextView iState, iErr, iLoad, iGrid, iTemp;
    private TextView p2Status, schToday, schTomorrow, schFoot, wxNow, wxDesc, wxFoot, solTom, solFoot;
    private TextView[] wxDay = new TextView[3], wxT = new TextView[3], wxR = new TextView[3];
    private TextView tPv, tLoad, tGrid, tBatt, tSelf;
    private ScheduleView sch1, sch2;
    private View[] dots = new View[2];
    private HouseFlowView flow;
    private Views.WeekChart week;
    private Pager pager;
    private FrameLayout banner; private TextView bannerT, bannerS, bannerMute;
    private Night night;

    // інформація (тривоги, графік, погода)
    private Handler info;
    private volatile Info.Alert alert;
    private volatile Info.Outage outage;
    private volatile Info.Weather weather;
    private volatile long alertFailMs;
    private Boolean alertWas;
    private final AlertSound sound = new AlertSound();
    private boolean muted;
    private String infoSig = "";
    private String builtQueue;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Home.installCrashGuard(this);
        Home.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        prefs = new Prefs(this);
        db = new HistoryDb(this);
        notifier = new Notifier(this);
        int wpx = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        u = wpx / 1280f;
        builtQueue = prefs.queue();
        setContentView(build());
        worker = new HandlerThread("poll"); worker.start(); bg = new Handler(worker.getLooper());
        String was = getSharedPreferences("cfg", MODE_PRIVATE).getString("last_version", null);
        if (was != null && !was.equals(BuildConfig.VERSION_NAME))
            android.widget.Toast.makeText(this, "Оновлено до " + BuildConfig.VERSION_NAME, android.widget.Toast.LENGTH_LONG).show();
        prefs.edit().putString("last_version", BuildConfig.VERSION_NAME).apply();
        HandlerThread ut = new HandlerThread("update"); ut.start(); upd = new Handler(ut.getLooper());
        upd.postDelayed(updateTask, 30000);
        HandlerThread it = new HandlerThread("info"); it.start(); info = new Handler(it.getLooper());
        info.post(() -> {
            try { outage = Info.outage(this, prefs, true); } catch (Exception ignored) { }
            try { weather = Info.weather(this, prefs, true); } catch (Exception ignored) { }
        });
        info.post(alertTask); info.postDelayed(outageTask, 2000); info.postDelayed(weatherTask, 4000);
    }

    // ---------- інформація ----------
    private final Runnable alertTask = new Runnable() {
        @Override public void run() {
            try { alert = Info.alert(prefs); alertFailMs = 0; }
            catch (Exception e) { if (alertFailMs == 0) alertFailMs = System.currentTimeMillis(); Log.w(TAG, "alert " + e); }
            ui.post(MainActivity.this::alertChanged);
            info.postDelayed(this, 30000);
        }
    };
    private final Runnable outageTask = new Runnable() {
        @Override public void run() {
            long next = 10 * 60000L;
            try { outage = Info.outage(MainActivity.this, prefs, false); } catch (Exception e) { next = 2 * 60000L; Log.w(TAG, "outage " + e); }
            info.postDelayed(this, next);
        }
    };
    private final Runnable weatherTask = new Runnable() {
        @Override public void run() {
            long next = 30 * 60000L;
            try { weather = Info.weather(MainActivity.this, prefs, false); } catch (Exception e) { next = 5 * 60000L; Log.w(TAG, "weather " + e); }
            info.postDelayed(this, next);
        }
    };

    private void alertChanged() {
        Info.Alert a = alert;
        boolean on = a != null && a.active;
        if (alertWas != null && alertWas != on || alertWas == null && on) {
            muted = false;
            if (on) { if (prefs.alertSound()) sound.alarm(); }
            else if (prefs.alertSound()) sound.allClear();
        }
        alertWas = on;
        renderBanner();
        if (night != null) night.update();
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
        if (!prefs.queue().equals(builtQueue)) { recreate(); return; }
        String sig = prefs.queue() + prefs.lat() + prefs.lon() + prefs.pvTilt() + prefs.pvAzimuth() + prefs.pvKwp() + prefs.pvEff() + prefs.alertRegion();
        if (!sig.equals(infoSig) && !infoSig.isEmpty()) {
            info.removeCallbacksAndMessages(null);
            info.post(alertTask); info.post(outageTask); info.post(weatherTask);
        }
        infoSig = sig;
        if (night != null) night.update();
    }

    @Override protected void onPause() { super.onPause(); flow.setRunning(false); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bms != null) bms.stop();
        worker.quitSafely();
        info.getLooper().quitSafely();
        sound.stop();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    /** Головний екран — домашній: «Назад» лише закриває детальну картку. */
    @Override public void onBackPressed() {
        if (popups != null && popups.isOpen()) popups.close();
        else if (pager != null && pager.page() > 0) pager.go(0);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (popups != null) popups.close(); if (pager != null) pager.go(0); } // кнопка «Додому»

    Snapshot snapOk() { Snapshot s = snap; return s != null && System.currentTimeMillis() - snapOkMs < 60000 ? s : null; }

    JkBmsProtocol.CellInfo cellOk() { JkBmsProtocol.CellInfo c = cell; return c != null && System.currentTimeMillis() - cellMs < 60000 ? c : null; }

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
                    if (lastGridOn != null && lastGridOn != on) { db.gridChanged(on, now); final boolean g = on; ui.post(() -> gridSound(g)); }
                    lastGridOn = on;
                }
            } catch (Exception e) {
                Log.w(TAG, "poll", e);
            }
            try {
                if (!prefs.demo() && !prefs.ip().isEmpty())
                    notifier.check(snap, snapOkMs, cell, cellMs, !prefs.bmsMac().isEmpty(), startMs);
                notifier.flush();
            } catch (Exception e) { Log.w(TAG, "notify", e); }
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

    private void gridSound(boolean on) {
        boolean isNight = night != null && night.isNightTime();
        if (isNight ? prefs.outageSoundNight() : prefs.outageSound()) sound.allClear();
    }

    /** Стан світла: з інвертора (якщо є зв'язок), інакше — за графіком. */
    Boolean lightOn() {
        Snapshot s = snapOk();
        if (s != null && !Double.isNaN(s.gridV)) return s.gridOn();
        Info.Outage o = outage; if (o == null) return null;
        Boolean off = o.offAt(System.currentTimeMillis());
        return off == null ? null : !off;
    }

    /** Текст до наступної зміни за графіком: {підпис, відлік} або null. */
    String[] countdown() {
        Info.Outage o = outage; if (o == null) return null;
        long now = System.currentTimeMillis();
        Boolean off = o.offAt(now); if (off == null) return null;
        long t = o.nextChange(now);
        if (t == 0) return new String[]{off ? "увімкнення за графіком невідоме" : "відключень за графіком не заплановано", ""};
        Boolean on = lightOn();
        if (on != null && on == off) // фактичний стан не збігається з графіком
            return new String[]{(off ? "за графіком без світла до " : "за графіком світло до ") + hhmm(t), Main.dur((t - now) / 3600000.0)};
        return new String[]{(off ? "увімкнуть о " : "вимкнуть о ") + hhmm(t) + ", через", Main.dur((t - now) / 3600000.0).replace(" г ", " г ")};
    }

    private void render() {
        long now = System.currentTimeMillis();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)));
        Info.Weather w = weather;
        wxHdr.setText(w == null || Double.isNaN(w.temp) ? "" : Info.icon(w.code) + " " + Main.n0(w.temp) + "°");
        Snapshot s = snapOk();
        JkBmsProtocol.CellInfo ci = cellOk();

        flow.ok = s != null;
        if (s != null) {
            flow.pv1 = s.pv1; flow.pv2 = s.pv2; flow.batt = s.batt; flow.load = s.load; flow.grid = s.grid; flow.gen = Double.isNaN(s.gen) ? 0 : s.gen;
            flow.gridOn = s.gridOn(); flow.soc = ci != null && ci.soc >= 0 ? ci.soc : (int) s.invSoc;
        }
        flow.invalidate();

        // АКБ
        double soc = ci != null && ci.soc >= 0 ? ci.soc : s != null ? s.invSoc : Double.NaN;
        socBig.setText(Double.isNaN(soc) ? "—" : Main.n0(soc) + "%");
        double cur = ci != null ? ci.current : s != null && s.invBattV > 10 ? -s.batt / s.invBattV : Double.NaN;
        battCurrent(cur);
        double cap = prefs.capAh();
        double rem = ci != null && !Double.isNaN(ci.remainAh) && ci.remainAh > 0 ? Math.min(ci.remainAh, cap) : Double.isNaN(soc) ? Double.NaN : cap * soc / 100.0;
        if (Double.isNaN(cur) || Double.isNaN(rem) || Math.abs(cur) < 0.5) battEta.setText("");
        else if (cur > 0) battEta.setText(val2("до повного ", "~" + hrs((cap - rem) / cur)));
        else battEta.setText(val2("вистачить ", "~" + hrs(rem / -cur)));

        // Світло
        Boolean on = lightOn();
        lightBig.setText(on == null ? "—" : on ? "Світло є" : "Немає світла");
        lightBig.setTextColor(on == null ? Views.GRY : on ? Views.GRN : 0xFFF87171);
        String[] cd = countdown();
        lightLbl.setText(cd == null ? "графіка немає" : cd[0]);
        lightCount.setText(cd == null ? "" : cd[1]);
        lightCount.setVisibility(cd == null || cd[1].isEmpty() ? View.GONE : View.VISIBLE);
        String note = reminder(now, soc);
        lightNote.setText(note == null ? "" : note); lightNote.setVisibility(note == null ? View.GONE : View.VISIBLE);

        // Сонце, дім
        if (s != null) {
            sunNow.setText(val(Main.f(s.pv() / 1000, s.pv() < 9950 ? 1 : 0), "кВт"));
            sunToday.setText(val(Main.f(s.todayPv, 1), "кВт·год"));
            homeNow.setText(val(Main.f(s.load / 1000, 2), "кВт"));
            homeToday.setText(val(Main.f(s.todayLoad, 1), "кВт·год"));
            homeGrid.setText("з мережі сьогодні " + Main.f(s.todayImp, 1) + " кВт·год");
        } else { sunNow.setText("—"); sunToday.setText("—"); homeNow.setText("—"); homeToday.setText("—"); homeGrid.setText("немає зв'язку з інвертором"); }
        sunTom.setText(w != null && w.pvKwh.length > 1 ? "завтра прогноз " + Main.f(w.pvKwh[1], 1) + " кВт·год" : "");

        // Інвертор
        if (s != null) {
            String[] st = {"Очікування", "Самотест", "Працює", "Попередження", "Аварія"};
            iState.setText(s.state >= 0 && s.state < st.length ? st[s.state] : "—");
            iState.setTextColor(s.state == 2 ? Views.GRN : s.state >= 3 ? Views.RED : Views.TXT);
            iErr.setText(s.state == 2 ? "помилок немає" : s.state >= 3 ? "перевірте інвертор" : "");
            iLoad.setText(Main.n0(100 * s.load / prefs.ratedW()) + " %");
            if (s.gridOn()) { iGrid.setText(Main.n0(s.gridV) + " В"); iGrid.setTextColor(Color.WHITE); } else { iGrid.setText("немає"); iGrid.setTextColor(0xFFF87171); }
            iTemp.setText(Main.n0(Math.max(s.tempHs, s.tempDc)) + " °C");
        } else { iState.setText("Немає зв'язку"); iState.setTextColor(0xFFFBBF24); iErr.setText(prefs.ip().isEmpty() && !prefs.demo() ? "вкажіть логер у налаштуваннях" : "перевірте Wi-Fi логера"); iLoad.setText("—"); iGrid.setText("—"); iTemp.setText("—"); }

        renderPage2(now, s, w);
        renderBanner();
        popups.refresh();
        if (night != null) night.update();
    }

    private String reminder(long now, double soc) {
        Info.Outage o = outage;
        if (o == null) return "⚠ Графік відключень ще не завантажено";
        if (now - o.fetchedMs > 3 * 3600000L) return "⚠ Графік оновлено " + ago(o.fetchedMs) + " тому";
        Boolean off = o.offAt(now);
        long t = o.nextChange(now);
        if (off != null && !off && t > 0 && t - now < 4 * 3600000L && !Double.isNaN(soc) && soc < 95) return "🔋 Зарядіть АКБ до " + hhmm(t);
        if (o.day(Info.dayStart(now) + 86400000L + 3600000L) == null && Calendar.getInstance(Info.KYIV).get(Calendar.HOUR_OF_DAY) >= 16)
            return "Завтрашній графік ще не опубліковано";
        return null;
    }

    private void renderPage2(long now, Snapshot s, Info.Weather w) {
        Info.Alert a = alert;
        if (a == null) p2Status.setText(alertFailMs > 0 ? "● тривоги: немає зв'язку" : "● тривоги: перевіряю…");
        else {
            boolean stale = now - a.fetchedMs > 3 * 60000L;
            p2Status.setText((a.active ? "● ТРИВОГА · " : "● Тривоги немає · ") + shortRegion(a.region) + (a.fallback ? " (обл.)" : "") + " · " + ago(a.fetchedMs));
            p2Status.setTextColor(a.active ? 0xFFF87171 : stale ? 0xFFFBBF24 : Views.GRN);
        }
        Info.Outage o = outage;
        long d0 = Info.dayStart(now), d1 = d0 + 86400000L;
        sch1.day = o == null ? null : o.day(d0 + 3600000L); sch1.now = now; sch1.invalidate();
        sch2.day = o == null ? null : o.day(d1 + 3600000L); sch2.now = now; sch2.invalidate();
        schToday.setText(periods(sch1.day));
        schTomorrow.setText(sch2.day == null ? "ще не опубліковано" : periods(sch2.day));
        schTomorrow.setTextColor(sch2.day == null ? Views.GRY : 0xFFF87171);
        schFoot.setText(o == null ? "графік ще не завантажено" : "ДТЕК оновив " + (o.updated.length() >= 16 ? o.updated.substring(11) : o.updated) + " · отримано " + ago(o.fetchedMs) + " тому · дані outage-data-ua");
        if (w != null) {
            wxNow.setText(val(Main.n0(w.temp), "°C"));
            wxDesc.setText(Info.icon(w.code) + "  " + Info.desc(w.code) + " · " + Main.n0(w.hum) + "%");
            String[] dn = {"Нд", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
            for (int i = 0; i < 3; i++) {
                int k = i + 1;
                if (k >= w.day.length) { wxDay[i].setText(""); wxT[i].setText(""); wxR[i].setText(""); continue; }
                Calendar c = Calendar.getInstance(); try { c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(w.day[k])); } catch (Exception ignored) { }
                wxDay[i].setText(dn[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + Info.icon(w.dcode[k]));
                wxT[i].setText(Main.n0(w.tmin[k]) + "…" + Main.n0(w.tmax[k]) + "°");
                wxR[i].setText("дощ " + Main.n0(w.rain[k]) + "%");
            }
            wxFoot.setText("схід " + w.sunrise + " · захід " + w.sunset + " · Open-Meteo · " + ago(w.fetchedMs) + " тому");
            solTom.setText(w.pvKwh.length > 1 ? Main.f(w.pvKwh[1], 1) : "—");
            StringBuilder sb = new StringBuilder("сьогодні " + (w.pvKwh.length > 0 ? Main.f(w.pvKwh[0], 1) : "—"));
            for (int k = 2; k < w.pvKwh.length && k < 4; k++) sb.append(" · ").append(k == 2 ? "післязавтра " : "далі ").append(Main.f(w.pvKwh[k], 1));
            solFoot.setText(sb + "\nнахил " + prefs.pvTilt() + "°, азимут " + prefs.pvAzimuth() + "° · дані Open-Meteo");
        } else { wxNow.setText("—"); wxDesc.setText("погода ще не завантажена"); solTom.setText("—"); }
        if (s != null) {
            tPv.setText(val(Main.f(s.todayPv, 1), "кВт·год")); tLoad.setText(val(Main.f(s.todayLoad, 1), "кВт·год"));
            tGrid.setText(val(Main.f(s.todayImp, 1), "кВт·год")); tBatt.setText(Main.f(s.todayChg, 1) + " / " + Main.f(s.todayDis, 1));
            double self = s.todayLoad > 0.1 ? Math.max(0, Math.min(100, 100 * (1 - s.todayImp / s.todayLoad))) : Double.NaN;
            tSelf.setText(val(Main.n0(self), "%"));
        }
        week.d = prefs.demo() ? (s != null ? Demo.week(now, s) : new Object[0][]) : db.week(now); week.invalidate();
    }

    private static String shortRegion(String r) { return r == null ? "" : r.replace(" район", " р-н").replace(" область", " обл."); }

    private static String periods(Info.Day d) {
        if (d == null) return "немає даних";
        StringBuilder sb = new StringBuilder(); int tot = 0;
        for (int i = 0; i < 48; i++) {
            if (!d.off[i] || (i > 0 && d.off[i - 1])) continue;
            int j = i; while (j < 48 && d.off[j]) j++;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(hm2(i)).append("–").append(hm2(j)); tot += j - i;
        }
        if (sb.length() == 0) return "без відключень";
        return sb + " (" + (tot % 2 == 0 ? String.valueOf(tot / 2) : String.format(Locale.US, "%.1f", tot / 2.0)) + " год)";
    }

    private static String hm2(int half) { return String.format(Locale.US, "%02d:%02d", half / 2, half % 2 * 30); }
    static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }
    private static String hrs(double h) { if (Double.isNaN(h) || h < 0 || h > 999) return "—"; return h < 10 ? Main.f(h, 1).replace(".0", "") + " год" : Main.n0(h) + " год"; }
    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000L;
        if (m < 1) return Math.max(0, (System.currentTimeMillis() - t) / 1000L) + " с";
        if (m < 120) return m + " хв";
        return m / 60 + " год";
    }

    private void renderBanner() {
        Info.Alert a = alert;
        boolean on = a != null && a.active;
        banner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        long now = System.currentTimeMillis();
        bannerT.setText("ПОВІТРЯНА ТРИВОГА · " + shortRegion(a.region));
        String since = a.since <= 0 ? "" : now - a.since < 20 * 3600000L ? "з " + hhmm(a.since) + " · триває " + Main.dur((now - a.since) / 3600000.0)
                : "з " + new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date(a.since));
        bannerS.setText(since + " · пройдіть в укриття" + (now - a.fetchedMs > 3 * 60000L ? " · дані " + ago(a.fetchedMs) + " тому" : ""));
        bannerMute.setVisibility(sound.playing() && !muted ? View.VISIBLE : View.GONE);
    }

    /** Струм АКБ зі стрілкою: ▲ заряд (зелений), ▼ розряд (помаранчевий). */
    private void battCurrent(double a) {
        if (Double.isNaN(a)) { battSub.setText("—"); battSub.setTextColor(Views.GRY); socBig.setTextColor(Views.GRN); return; }
        if (Math.abs(a) < 0.5) { battSub.setText("● 0 А  очікує"); battSub.setTextColor(Views.GRY); socBig.setTextColor(Views.GRN); return; }
        boolean chg = a > 0;
        SpannableStringBuilder sb = new SpannableStringBuilder((chg ? "▲ " : "▼ ") + Main.f(Math.abs(a), 1) + " А");
        int st = sb.length(); sb.append(chg ? "  заряд" : "  розряд");
        sb.setSpan(new RelativeSizeSpan(0.6f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        battSub.setText(sb);
        int col = chg ? Views.GRN : 0xFFFB923C;
        battSub.setTextColor(col); socBig.setTextColor(col);
    }

    private SpannableStringBuilder val(String big, String unit) {
        SpannableStringBuilder sb = new SpannableStringBuilder(big);
        int st = sb.length();
        sb.append(" ").append(unit);
        sb.setSpan(new RelativeSizeSpan(0.45f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Views.DIM), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private SpannableStringBuilder val2(String small, String big) {
        SpannableStringBuilder sb = new SpannableStringBuilder(small);
        int st = sb.length(); sb.append(big);
        sb.setSpan(new RelativeSizeSpan(1.5f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Color.WHITE), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ---------- побудова інтерфейсу ----------

    private int px(float v) { return Math.round(v * u); }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        ImageView bgImg = new ImageView(this);
        bgImg.setImageResource(R.drawable.bg); bgImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bgImg, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackgroundColor(0xC8020617);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // заголовок: логотип (= налаштування), годинник, погода, крапки сторінок
        FrameLayout hdr = new FrameLayout(this); hdr.setPadding(px(12), 0, px(14), 0);
        col.addView(hdr, new LinearLayout.LayoutParams(-1, px(72)));
        TextView logo = text("⚡", 30, Color.WHITE); logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(0xFF059669, px(7), 0));
        logo.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        hdr.addView(logo, new FrameLayout.LayoutParams(px(52), px(52), Gravity.START | Gravity.CENTER_VERTICAL));
        p2Status = text("", 20, Views.GRN);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL); sl.leftMargin = px(68);
        hdr.addView(p2Status, sl); p2Status.setVisibility(View.GONE);
        clock = text("--:--", 62, Color.WHITE);
        clock.setTypeface(Typeface.DEFAULT_BOLD); clock.setLetterSpacing(0.06f);
        hdr.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout right = new LinearLayout(this); right.setGravity(Gravity.CENTER_VERTICAL);
        wxHdr = text("", 32, Views.TXT); right.addView(wxHdr);
        LinearLayout dl = new LinearLayout(this); dl.setPadding(px(18), 0, 0, 0);
        for (int i = 0; i < 2; i++) {
            dots[i] = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(15), px(15)); lp.setMargins(px(5), 0, px(5), 0);
            dl.addView(dots[i], lp); final int k = i; dots[i].setOnClickListener(v -> pager.go(k));
        }
        dl.setPadding(px(18), px(20), px(4), px(20));
        right.addView(dl);
        hdr.addView(right, new FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));

        pager = new Pager(this);
        col.addView(pager, new LinearLayout.LayoutParams(-1, 0, 1));
        pager.addView(page1(), new FrameLayout.LayoutParams(-1, -1));
        pager.addView(page2(), new FrameLayout.LayoutParams(-1, -1));
        pager.setListener(p -> { setDots(p); p2Status.setVisibility(p == 1 ? View.VISIBLE : View.GONE); });
        setDots(0);

        popups = new Popups(this, root, u);

        // банер тривоги поверх усього
        banner = new FrameLayout(this); banner.setBackgroundColor(0xFFB91C1C); banner.setClickable(true);
        banner.setPadding(px(26), 0, px(22), 0);
        LinearLayout bl = new LinearLayout(this); bl.setGravity(Gravity.CENTER_VERTICAL);
        bl.addView(text("🚨", 60, Color.WHITE));
        LinearLayout bt = new LinearLayout(this); bt.setOrientation(LinearLayout.VERTICAL); bt.setPadding(px(20), 0, 0, 0);
        bannerT = text("", 42, Color.WHITE); bannerT.setTypeface(Typeface.DEFAULT_BOLD); bannerT.setSingleLine();
        bannerS = text("", 24, 0xFFFEE2E2); bannerS.setPadding(0, px(6), 0, 0);
        bt.addView(bannerT); bt.addView(bannerS);
        bl.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        bannerMute = text("🔇 Без звуку", 28, Color.WHITE); bannerMute.setTypeface(Typeface.DEFAULT_BOLD);
        bannerMute.setPadding(px(22), px(14), px(22), px(14)); bannerMute.setBackground(round(0xFF7F1D1D, px(10), 0xFFFFFFFF));
        bannerMute.setOnClickListener(v -> { muted = true; sound.stop(); renderBanner(); });
        bl.addView(bannerMute);
        banner.addView(bl, new FrameLayout.LayoutParams(-1, -1));
        root.addView(banner, new FrameLayout.LayoutParams(-1, px(120), Gravity.TOP));
        banner.setVisibility(View.GONE);

        night = new Night(this, root, u);
        return root;
    }

    private void setDots(int p) {
        for (int i = 0; i < 2; i++) dots[i].setBackground(round(i == p ? 0xFFE2E8F0 : 0xFF334155, px(8), 0));
    }

    private View page1() {
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(px(4), 0, px(4), px(4));
        LinearLayout r1 = row(grid), r2 = row(grid);

        LinearLayout c1 = card(r1, "Потік зараз", Views.GRN, 1);
        flow = new HouseFlowView(this); c1.addView(flow, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout c2 = card(r1, "Акумулятор", Views.GRN, 1);
        LinearLayout m2 = mid(c2);
        socBig = text("—", 128, Views.GRN); socBig.setTypeface(Typeface.DEFAULT_BOLD); m2.addView(socBig);
        battSub = text("", 42, Views.GRY); battSub.setTypeface(Typeface.DEFAULT_BOLD); battSub.setPadding(0, px(10), 0, 0); m2.addView(battSub);
        battEta = text("", 28, 0xFFCBD5E1); battEta.setPadding(0, px(14), 0, 0); m2.addView(battEta);

        LinearLayout c3 = card(r1, "Світло · черга " + prefs.queue(), 0xFFFBBF24, 1);
        LinearLayout m3 = mid(c3);
        lightBig = text("—", 62, Views.GRY); lightBig.setTypeface(Typeface.DEFAULT_BOLD); lightBig.setGravity(Gravity.CENTER); m3.addView(lightBig);
        lightLbl = text("", 26, 0xFFCBD5E1); lightLbl.setGravity(Gravity.CENTER); lightLbl.setPadding(0, px(14), 0, 0); m3.addView(lightLbl);
        lightCount = text("", 64, Color.WHITE); lightCount.setTypeface(Typeface.DEFAULT_BOLD); lightCount.setPadding(0, px(6), 0, 0); m3.addView(lightCount);
        lightNote = text("", 23, 0xFFFDE68A); lightNote.setGravity(Gravity.CENTER); lightNote.setBackground(round(0xFF422006, px(8), 0));
        lightNote.setPadding(px(14), px(8), px(14), px(8));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.topMargin = px(14); m3.addView(lightNote, nl);

        LinearLayout c4 = card(r2, "Сонце", Views.YEL, 1);
        LinearLayout m4 = mid(c4);
        sunNow = text("—", 96, Views.YEL); sunNow.setTypeface(Typeface.DEFAULT_BOLD); m4.addView(sunNow);
        TextView l4 = text("сьогодні вироблено", 27, 0xFFCBD5E1); l4.setPadding(0, px(16), 0, 0); m4.addView(l4);
        sunToday = text("—", 46, Views.YEL); sunToday.setTypeface(Typeface.DEFAULT_BOLD); m4.addView(sunToday);
        sunTom = text("", 20, Views.GRY); sunTom.setPadding(0, px(8), 0, 0); m4.addView(sunTom);

        LinearLayout c5 = card(r2, "Дім споживає", Views.PUR, 1);
        LinearLayout m5 = mid(c5);
        homeNow = text("—", 96, Views.PUR); homeNow.setTypeface(Typeface.DEFAULT_BOLD); m5.addView(homeNow);
        TextView l5 = text("сьогодні спожито", 27, 0xFFCBD5E1); l5.setPadding(0, px(16), 0, 0); m5.addView(l5);
        homeToday = text("—", 46, Views.PUR); homeToday.setTypeface(Typeface.DEFAULT_BOLD); m5.addView(homeToday);
        homeGrid = text("", 20, Views.GRY); homeGrid.setPadding(0, px(8), 0, 0); m5.addView(homeGrid);

        LinearLayout c6 = card(r2, "Інвертор", Views.BLU, 1);
        iState = text("—", 56, Views.GRN); iState.setTypeface(Typeface.DEFAULT_BOLD); iState.setGravity(Gravity.CENTER);
        c6.addView(iState, new LinearLayout.LayoutParams(-1, -2));
        iErr = text("", 20, Views.GRY); iErr.setGravity(Gravity.CENTER); iErr.setPadding(0, px(4), 0, px(8));
        c6.addView(iErr, new LinearLayout.LayoutParams(-1, -2));
        iLoad = kv(c6, "Навантаження"); iGrid = kv(c6, "Мережа"); iTemp = kv(c6, "Температура");

        c1.setOnClickListener(v -> popups.show(Popups.FLOW));
        c2.setOnClickListener(v -> popups.show(Popups.BATT));
        c3.setOnClickListener(v -> pager.go(1));
        c4.setOnClickListener(v -> popups.show(Popups.DAY));
        c5.setOnClickListener(v -> popups.show(Popups.TODAY));
        c6.setOnClickListener(v -> popups.show(Popups.INV));
        return grid;
    }

    private View page2() {
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(px(4), 0, px(4), px(4));
        LinearLayout r1 = row(grid), r2 = row(grid);

        LinearLayout c1 = card(r1, "Графік відключень · черга " + prefs.queue() + " · ДТЕК КРЕМ", 0xFFFBBF24, 2);
        schToday = dayRow(c1, "Сьогодні");
        sch1 = new ScheduleView(this, u); c1.addView(sch1, new LinearLayout.LayoutParams(-1, px(68)));
        schTomorrow = dayRow(c1, "Завтра");
        sch2 = new ScheduleView(this, u); c1.addView(sch2, new LinearLayout.LayoutParams(-1, px(68)));
        View sp = new View(this); c1.addView(sp, new LinearLayout.LayoutParams(-1, 0, 1));
        schFoot = text("", 19, Views.GRY); c1.addView(schFoot);

        LinearLayout c2 = card(r1, "Погода", Views.BLU, 1);
        LinearLayout now = new LinearLayout(this); now.setGravity(Gravity.CENTER_VERTICAL);
        wxNow = text("—", 64, Color.WHITE); wxNow.setTypeface(Typeface.DEFAULT_BOLD); now.addView(wxNow);
        wxDesc = text("", 22, 0xFFCBD5E1); wxDesc.setPadding(px(14), 0, 0, 0); now.addView(wxDesc, new LinearLayout.LayoutParams(0, -2, 1));
        c2.addView(now);
        for (int i = 0; i < 3; i++) {
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(px(2), px(8), px(2), px(8));
            wxDay[i] = text("", 26, 0xFFCBD5E1); r.addView(wxDay[i], new LinearLayout.LayoutParams(0, -2, 1));
            wxT[i] = text("", 34, Color.WHITE); wxT[i].setTypeface(Typeface.DEFAULT_BOLD); r.addView(wxT[i], new LinearLayout.LayoutParams(0, -2, 1.2f));
            wxR[i] = text("", 20, Views.GRY); wxR[i].setGravity(Gravity.END); r.addView(wxR[i], new LinearLayout.LayoutParams(0, -2, 0.9f));
            c2.addView(r);
            View line = new View(this); line.setBackgroundColor(0x1A94A3B8); c2.addView(line, new LinearLayout.LayoutParams(-1, 1));
        }
        View sp2 = new View(this); c2.addView(sp2, new LinearLayout.LayoutParams(-1, 0, 1));
        wxFoot = text("", 17, Views.GRY); c2.addView(wxFoot);

        LinearLayout c3 = card(r2, "Сонце завтра", Views.YEL, 1);
        LinearLayout m3 = mid(c3);
        solTom = text("—", 104, Views.YEL); solTom.setTypeface(Typeface.DEFAULT_BOLD); m3.addView(solTom);
        m3.addView(text("кВт·год (" + Main.f(prefs.pvKwp(), 1).replace(".0", "") + " кВт панелей)", 26, 0xFFCBD5E1));
        solFoot = text("", 19, Views.GRY); solFoot.setGravity(Gravity.CENTER); solFoot.setPadding(0, px(12), 0, 0); m3.addView(solFoot);

        LinearLayout c4 = card(r2, "Сьогодні", 0xFFFB923C, 1);
        tPv = kv(c4, "Вироблено"); tPv.setTextColor(Views.YEL);
        tLoad = kv(c4, "Спожито"); tLoad.setTextColor(Views.PUR);
        tGrid = kv(c4, "З мережі"); tGrid.setTextColor(Views.BLU);
        tBatt = kv(c4, "Заряд / розряд");
        tSelf = kv(c4, "Власне покриття"); tSelf.setTextColor(Views.GRN);

        LinearLayout c5 = card(r2, "Тиждень", Views.PUR, 1);
        week = new Views.WeekChart(this, u); c5.addView(week, new LinearLayout.LayoutParams(-1, 0, 1));
        c5.addView(legend(new String[]{"сонце", "спожито"}, new int[]{Views.YEL, Views.PUR}));

        c3.setOnClickListener(v -> popups.show(Popups.DAY));
        c4.setOnClickListener(v -> popups.show(Popups.TODAY));
        c5.setOnClickListener(v -> popups.show(Popups.WEEK));
        return grid;
    }

    private TextView dayRow(LinearLayout card, String label) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, px(10), 0, px(6));
        r.addView(text(label, 26, 0xFFCBD5E1), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text("—", 30, 0xFFF87171); v.setTypeface(Typeface.DEFAULT_BOLD); r.addView(v);
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private LinearLayout mid(LinearLayout card) {
        LinearLayout m = new LinearLayout(this) {
            @Override public void addView(View child) { if (child instanceof TextView) ((TextView) child).setGravity(Gravity.CENTER); super.addView(child); }
        };
        m.setOrientation(LinearLayout.VERTICAL); m.setGravity(Gravity.CENTER);
        card.addView(m, new LinearLayout.LayoutParams(-1, 0, 1));
        return m;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        parent.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
        return r;
    }

    private LinearLayout card(LinearLayout row, String title, int color, float weight) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(10), px(16), px(10));
        c.setBackground(round(0xDB020617, px(8), 0x2994A3B8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight); lp.setMargins(px(3), px(3), px(3), px(3));
        row.addView(c, lp);
        TextView t = text(title.toUpperCase(new Locale("uk")), 23, color);
        t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.06f); t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, px(4));
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private TextView kv(LinearLayout card, String label) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(2), px(8), px(2), px(8));
        TextView lbl = text(label, 25, 0xFFCBD5E1);
        r.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text("—", 34, Color.WHITE); v.setTypeface(Typeface.DEFAULT_BOLD); v.setGravity(Gravity.END);
        r.addView(v);
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this); line.setBackgroundColor(0x1A94A3B8);
        card.addView(line, new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    private View legend(String[] names, int[] cols) {
        LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER);
        for (int i = 0; i < names.length; i++) {
            View sw = new View(this); sw.setBackgroundColor(cols[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(22), px(8)); lp.setMargins(px(16), 0, px(8), 0);
            l.addView(sw, lp);
            l.addView(text(names[i], 22, 0xFFCBD5E1));
        }
        return l;
    }

    TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(0, px(size)); t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(int fill, int r, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(r);
        if (stroke != 0) g.setStroke(2, stroke);
        return g;
    }

    /** Банер тривоги і нічний екран — завжди над детальними картками. */
    void raiseOverlays() { banner.bringToFront(); night.bringToFront(); }

    boolean alertActive() { Info.Alert a = alert; return a != null && a.active; }
    Info.Weather weatherNow() { return weather; }
    double socNow() { JkBmsProtocol.CellInfo ci = cellOk(); Snapshot s = snapOk(); return ci != null && ci.soc >= 0 ? ci.soc : s != null ? s.invSoc : Double.NaN; }
}
