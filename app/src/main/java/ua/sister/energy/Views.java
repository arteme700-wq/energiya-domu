package ua.sister.energy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Calendar;

final class Views {
    static final int YEL = 0xFFFACC15, PUR = 0xFFC084FC, BLU = 0xFF38BDF8, GRN = 0xFF34D399, GRY = 0xFF94A3B8,
            DIM = 0xFF64748B, TXT = 0xFFE2E8F0, RED = 0xFFF87171, LINE = 0xFF1E293B;

    private Views() { }

    static Paint paint(int color, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setTextSize(size); return p;
    }

    /** Схема потоку енергії. */
    static final class Flow extends View {
        final float u;
        double pv1, pv2, batt, load, grid, gen; int soc = -1; boolean gridOn = true, ok; double gridV, gridHz;
        Flow(Context c, float u) { super(c); this.u = u; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (!ok) { Paint p = paint(DIM, 22 * u); p.setTextAlign(Paint.Align.CENTER); c.drawText("немає даних", w / 2, h / 2, p); return; }
            double pv = pv1 + pv2;
            float cx = w / 2, cy = h * 0.48f;
            node(c, cx, h * 0.14f, "☀", Main.w(pv), pv > 20 ? YEL : DIM, pv2 > 20 ? "PV1 " + Main.n0(pv1) + " · PV2 " + Main.n0(pv2) : "СЕС · MPPT1");
            node(c, w * 0.17f, cy, "🔋", (batt < -20 ? "+" : "") + Main.w(-batt), batt < -20 ? GRN : batt > 20 ? PUR : DIM,
                    batt < -20 ? "АКБ · заряд" : batt > 20 ? "АКБ · розряд" : "АКБ · очікує");
            node(c, cx, cy, "🏠", Main.w(load), PUR, "Дім");
            node(c, w * 0.83f, cy, "🔌", Main.w(grid), !gridOn ? RED : grid > 20 ? BLU : grid < -20 ? GRN : DIM,
                    !gridOn ? "Мережа" : grid > 20 ? "Мережа · бере" : grid < -20 ? "Мережа · віддає" : "Мережа");
            float ay = h * 0.66f;
            if (pv > 20) arrow(c, cx, h * 0.30f, cx, h * 0.37f, YEL);
            if (batt < -20) arrow(c, w * 0.40f, ay, w * 0.25f, ay, GRN);
            if (batt > 20) arrow(c, w * 0.25f, ay, w * 0.40f, ay, PUR);
            if (gridOn && grid > 20) arrow(c, w * 0.75f, ay, w * 0.60f, ay, BLU);
            if (gridOn && grid < -20) arrow(c, w * 0.60f, ay, w * 0.75f, ay, GRN);
            Paint s = paint(gridOn ? GRN : RED, 22 * u); s.setFakeBoldText(true); s.setTextAlign(Paint.Align.CENTER);
            c.drawText(gridOn ? "● Світло є" : "● Світла немає", cx, h * 0.84f, s);
            Paint t = paint(GRY, 17 * u); t.setTextAlign(Paint.Align.CENTER);
            String ln = gridOn ? String.format(java.util.Locale.US, "%.1f В · %.2f Гц", gridV, gridHz) : "живлення від АКБ і СЕС";
            if (gen > 20) ln += " · ГЕН " + Main.w(gen);
            c.drawText(ln, cx, h * 0.95f, t);
        }

        private void node(Canvas c, float x, float y, String icon, String val, int col, String sub) {
            Paint ip = paint(TXT, 32 * u); ip.setTextAlign(Paint.Align.CENTER);
            c.drawText(icon, x, y, ip);
            Paint vp = paint(col, 27 * u); vp.setFakeBoldText(true); vp.setTextAlign(Paint.Align.CENTER);
            c.drawText(val, x, y + 32 * u, vp);
            Paint sp = paint(GRY, 16 * u); sp.setTextAlign(Paint.Align.CENTER);
            c.drawText(sub, x, y + 52 * u, sp);
        }

        private void arrow(Canvas c, float x1, float y1, float x2, float y2, int col) {
            Paint p = paint(col, 0); p.setStrokeWidth(4 * u); p.setStyle(Paint.Style.STROKE);
            p.setPathEffect(new DashPathEffect(new float[]{8 * u, 5 * u}, 0));
            c.drawLine(x1, y1, x2, y2, p);
            double a = Math.atan2(y2 - y1, x2 - x1); float L = 13 * u;
            Path hd = new Path(); hd.moveTo(x2, y2);
            hd.lineTo((float) (x2 - L * Math.cos(a - 0.5)), (float) (y2 - L * Math.sin(a - 0.5)));
            hd.lineTo((float) (x2 - L * Math.cos(a + 0.5)), (float) (y2 - L * Math.sin(a + 0.5))); hd.close();
            Paint f = paint(col, 0); c.drawPath(hd, f);
        }
    }

    /** Горизонтальна смуга заповнення. */
    static final class Bar extends View {
        float frac; int color;
        Bar(Context c, int color) { super(c); this.color = color; }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            Paint bg = paint(LINE, 0); c.drawRoundRect(0, 0, w, h, h / 2, h / 2, bg);
            Paint f = paint(color, 0); c.drawRoundRect(0, 0, Math.max(h, w * Math.min(1, frac)), h, h / 2, h / 2, f);
        }
    }

    /** Напруги банок. */
    static final class Cells extends View {
        double[] v = new double[0];
        Cells(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            if (v.length == 0) return;
            double mn = 9, mx = 0;
            for (double x : v) { mn = Math.min(mn, x); mx = Math.max(mx, x); }
            float w = getWidth(), h = getHeight(), gap = 3, bw = (w - gap * (v.length - 1)) / v.length;
            for (int i = 0; i < v.length; i++) {
                float fr = (float) (mx - mn < 0.002 ? 0.9 : 0.55 + 0.45 * (v[i] - mn) / (mx - mn));
                int col = v[i] == mx && mx - mn > 0.03 ? YEL : v[i] == mn && mx - mn > 0.03 ? BLU : 0xFF10B981;
                c.drawRoundRect(i * (bw + gap), h * (1 - fr), i * (bw + gap) + bw, h, 2, 2, paint(col, 0));
            }
        }
    }

    /** Графік дня: кВт по годинах. */
    static final class DayChart extends View {
        final float u; double[][] d = new double[3][24]; int nowH;
        DayChart(Context c, float u) { super(c); this.u = u; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), padB = 34 * u, top = 26 * u;
            double mx = 2;
            for (double[] a : d) for (double x : a) if (!Double.isNaN(x)) mx = Math.max(mx, x);
            mx = Math.ceil(mx / 2) * 2;
            Paint gl = paint(0xFF334155, 20 * u), tl = paint(0xFF94A3B8, 20 * u); tl.setTextAlign(Paint.Align.RIGHT);
            for (int k = 2; k <= mx; k += 2) {
                float y = (float) (h - padB - k / mx * (h - padB - top));
                c.drawLine(0, y, w, y, gl); c.drawText(k + " кВт", w, y - 3 * u, tl);
            }
            Paint xl = paint(0xFFCBD5E1, 22 * u); xl.setTextAlign(Paint.Align.CENTER);
            for (int hh : new int[]{0, 6, 12, 18}) c.drawText(String.format("%02d", hh), x(hh, w), h - 4 * u, xl);
            int[] cols = {YEL, PUR, BLU};
            for (int k = 0; k < 3; k++) {
                Path p = new Path(); boolean st = false;
                for (int i = 0; i < 24; i++) {
                    double v = d[k][i];
                    if (Double.isNaN(v)) { st = false; continue; }
                    float y = (float) (h - padB - v / mx * (h - padB - top));
                    if (!st) { p.moveTo(x(i, w), y); st = true; c.drawCircle(x(i, w), y, 3 * u, paint(cols[k], 0)); } else p.lineTo(x(i, w), y);
                }
                Paint lp = paint(cols[k], 0); lp.setStyle(Paint.Style.STROKE); lp.setStrokeWidth(5f * u);
                lp.setStrokeJoin(Paint.Join.ROUND);
                if (k == 2) lp.setPathEffect(new DashPathEffect(new float[]{9 * u, 6 * u}, 0));
                c.drawPath(p, lp);
            }
            Paint nl = paint(GRY, 0); nl.setStyle(Paint.Style.STROKE); nl.setStrokeWidth(1.5f * u);
            nl.setPathEffect(new DashPathEffect(new float[]{4 * u, 5 * u}, 0));
            float nx = x(nowH, w); c.drawLine(nx, top, nx, h - padB, nl);
        }
        private float x(int i, float w) { return 14 * u + i * (w - 28 * u) / 23f; }
    }

    /** Тиждень: стовпчики СЕС/спожито. */
    static final class WeekChart extends View {
        final float u; Object[][] d = new Object[0][];
        static final String[] DN = {"Нд", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
        WeekChart(Context c, float u) { super(c); this.u = u; }
        @Override protected void onDraw(Canvas c) {
            if (d.length == 0) return;
            float w = getWidth(), h = getHeight(), padB = 34 * u, top = 30 * u;
            double mx = 5;
            for (Object[] r : d) mx = Math.max(mx, Math.max((Double) r[1], (Double) r[2]));
            float slot = w / d.length, bw = slot * 0.34f;
            Paint lp = paint(DIM, 22 * u); lp.setTextAlign(Paint.Align.CENTER);
            Paint vp = paint(0xFFE2E8F0, 20 * u); vp.setTextAlign(Paint.Align.CENTER);
            Calendar cal = Calendar.getInstance();
            for (int i = 0; i < d.length; i++) {
                double pv = (Double) d[i][1], ld = (Double) d[i][2];
                float x0 = i * slot + slot / 2;
                float hp = (float) (pv / mx * (h - padB - top)), hl = (float) (ld / mx * (h - padB - top));
                c.drawRoundRect(x0 - bw - 1, h - padB - hp, x0 - 1, h - padB, 3, 3, paint(YEL, 0));
                c.drawRoundRect(x0 + 1, h - padB - hl, x0 + bw + 1, h - padB, 3, 3, paint(PUR, 0));
                if (ld > 0) c.drawText(String.format(java.util.Locale.US, "%.0f", ld), x0 + bw / 2, h - padB - hl - 5 * u, vp);
                cal.setTimeInMillis((Long) d[i][0]);
                lp.setColor(i == d.length - 1 ? 0xFFFFFFFF : 0xFFCBD5E1);
                c.drawText(DN[cal.get(Calendar.DAY_OF_WEEK) - 1], x0, h - 5 * u, lp);
            }
        }
    }
}
