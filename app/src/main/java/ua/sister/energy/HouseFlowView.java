package ua.sister.energy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Власна векторна ілюстрація будинку з анімованими потоками енергії.
 * Малюється в «проєктних» координатах 400×300 і масштабується під клітинку.
 * Анімація ≤30 кадрів/с і лише коли setRunning(true) (Activity у фокусі, екран увімкнено).
 */
final class HouseFlowView extends View {
    private static final float DW = 400, DH = 300;
    double pv1, pv2, batt, load, grid, gen; int soc = -1; boolean gridOn = true, ok;

    private boolean running;
    private long lastFrame, t0 = SystemClock.uptimeMillis();
    private final float[] phase = new float[4];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), lead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] lines = new Path[4];       // 0 PV→інв, 1 мережа→інв, 2 інв→АКБ, 3 інв→дім
    private final PathMeasure[] pm = new PathMeasure[4];
    private final float[] len = new float[4];
    private final float[] pos = new float[2];
    private Shader glow, wallL, wallR, roofG;

    // геометрія
    private static final float[] GABLE = {90, 165, 150, 112, 210, 205};
    private static final float[] WALL_L = {90, 165, 210, 205, 210, 278, 90, 238};
    private static final float[] WALL_R = {210, 205, 330, 160, 330, 233, 210, 278};
    private static final float[] ROOF = {150, 112, 270, 70, 336, 158, 210, 205};
    private static final float[] ROOF_L = {84, 168, 150, 108, 156, 116, 92, 172};

    HouseFlowView(Context c) {
        super(c);
        float[][] pts = {
                {262, 128, 262, 196, 255, 204},                 // з даху вниз до інвертора
                {34, 132, 92, 180, 92, 250, 205, 290, 240, 278, 240, 224}, // стовп → дріт → низом до інвертора
                {262, 226, 262, 248, 286, 240},                 // інвертор → АКБ
                {244, 212, 214, 222, 160, 203},                 // інвертор → освітлене вікно
        };
        for (int i = 0; i < 4; i++) {
            Path p = new Path(); p.moveTo(pts[i][0], pts[i][1]);
            for (int k = 2; k < pts[i].length; k += 2) p.lineTo(pts[i][k], pts[i][k + 1]);
            lines[i] = p; pm[i] = new PathMeasure(p, false); len[i] = pm[i].getLength();
        }
        stroke.setStyle(Paint.Style.STROKE);
        lead.setStyle(Paint.Style.STROKE); lead.setColor(0x6694A3B8); lead.setStrokeWidth(0.8f);
        lead.setPathEffect(new DashPathEffect(new float[]{2, 3}, 0));
        glow = new RadialGradient(150, 205, 70, 0x88FFE9B0, 0x00FFE9B0, Shader.TileMode.CLAMP);
        wallL = new LinearGradient(90, 170, 210, 270, 0xFF3A4150, 0xFF262B36, Shader.TileMode.CLAMP);
        wallR = new LinearGradient(210, 200, 330, 230, 0xFF20252F, 0xFF181C24, Shader.TileMode.CLAMP);
        roofG = new LinearGradient(150, 110, 330, 160, 0xFF2B303B, 0xFF1A1E26, Shader.TileMode.CLAMP);
    }

    void setRunning(boolean r) {
        running = r;
        if (r) { lastFrame = SystemClock.uptimeMillis(); postInvalidateOnAnimation(); }
    }

    private static Path poly(float[] p) {
        Path path = new Path(); path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        path.close(); return path;
    }

    private Path pGable, pWallL, pWallR, pRoof, pRoofL;

    @Override protected void onDraw(Canvas c) {
        if (pGable == null) { pGable = poly(GABLE); pWallL = poly(WALL_L); pWallR = poly(WALL_R); pRoof = poly(ROOF); pRoofL = poly(ROOF_L); }
        float s = Math.min(getWidth() / DW, getHeight() / DH);
        c.save();
        c.translate((getWidth() - DW * s) / 2, (getHeight() - DH * s) / 2);
        c.scale(s, s);
        drawScene(c);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f); lastFrame = now;
        double pv = pv1 + pv2;
        double[] p = {ok ? pv : 0, ok && gridOn ? grid : 0, ok ? -batt : 0, ok ? load : 0};
        int[] col = {Views.YEL, p[1] >= 0 ? Views.BLU : Views.GRN, p[2] >= 0 ? Views.GRN : 0xFFFB923C, Views.PUR};
        for (int i = 0; i < 4; i++) flow(c, i, p[i], col[i], dt);
        drawLabels(c);
        c.restore();
        if (running) postInvalidateDelayed(33); // ≈30 fps
    }

    private void flow(Canvas c, int i, double w, int color, float dt) {
        stroke.setShader(null); stroke.setColor(0x5594A3B8); stroke.setStrokeWidth(1.2f);
        c.drawPath(lines[i], stroke);
        if (Math.abs(w) < 20) return;
        double a = Math.min(1, Math.abs(w) / 4000.0);
        float speed = (float) (18 + 60 * a);       // проєктних px/с
        float gap = (float) (22 - 10 * a);         // щільність крапок
        phase[i] = (phase[i] + speed * dt) % gap;
        dot.setColor(color);
        float r = (float) (1.8 + 1.2 * a);
        for (float d = phase[i]; d < len[i]; d += gap) {
            float at = w > 0 ? d : len[i] - d;
            pm[i].getPosTan(at, pos, null);
            dot.setAlpha(255); c.drawCircle(pos[0], pos[1], r, dot);
            dot.setAlpha(70); c.drawCircle(pos[0], pos[1], r * 2.2f, dot);
        }
    }

    private void drawScene(Canvas c) {
        fill.setShader(null);
        // тінь
        fill.setColor(0x55000000); c.drawOval(60, 250, 360, 305, fill);
        // стіни, дах
        fill.setShader(wallR); c.drawPath(pWallR, fill);
        fill.setShader(wallL); c.drawPath(pWallL, fill);
        fill.setShader(null); fill.setColor(0xFF323845); c.drawPath(pGable, fill);
        fill.setShader(roofG); c.drawPath(pRoof, fill);
        fill.setShader(null); fill.setColor(0xFF14171E); c.drawPath(pRoofL, fill);
        // сонячні панелі 2×4 на даху
        panel(c, 0.18f, 0.14f, 0.62f, 0.86f);
        // вікно (світиться коли є споживання)
        boolean lit = ok && load > 30;
        if (lit) { fill.setShader(glow); c.drawCircle(150, 205, 70, fill); fill.setShader(null); }
        fill.setColor(lit ? 0xFFFFF4D6 : 0xFF3B4252);
        c.drawPath(quad(WALL_L, 0.38f, 0.36f, 0.66f, 0.82f), fill);
        stroke.setColor(0xFF1A1E26); stroke.setStrokeWidth(1.2f);
        c.drawPath(quad(WALL_L, 0.52f, 0.36f, 0.52f, 0.82f, true), stroke);
        // маленьке вікно на правій стіні
        fill.setColor(0xFF2A303C); c.drawPath(quad(WALL_R, 0.62f, 0.15f, 0.82f, 0.45f), fill);
        // інвертор
        fill.setColor(0xFFCBD5E1); c.drawPath(quad(WALL_R, 0.12f, 0.12f, 0.33f, 0.55f), fill);
        fill.setColor(0xFF0F172A); c.drawPath(quad(WALL_R, 0.16f, 0.2f, 0.28f, 0.3f), fill);
        fill.setColor(0xFF34D399); c.drawCircle(250, 222, 1.4f, fill);
        // АКБ
        fill.setColor(0xFF94A3B8); c.drawPath(quad(WALL_R, 0.62f, 0.55f, 0.82f, 0.98f), fill);
        fill.setColor(soc >= 0 ? 0xFF10B981 : 0xFF475569); c.drawPath(quad(WALL_R, 0.66f, 0.62f, 0.7f, 0.9f), fill);
        // стовп і дріт
        stroke.setColor(0xFF64748B); stroke.setStrokeWidth(3); c.drawLine(34, 120, 34, 262, stroke);
        stroke.setStrokeWidth(1.6f); c.drawLine(22, 132, 46, 128, stroke); c.drawLine(24, 142, 44, 139, stroke);
        stroke.setColor(gridOn ? 0xFF475569 : 0xFFF87171);
        stroke.setStrokeWidth(1); c.drawLine(0, 126, 34, 132, stroke);
    }

    /** Чотирикутник усередині грані (u — по горизонталі, v — по вертикалі). */
    private static Path quad(float[] f, float u0, float v0, float u1, float v1) { return quad(f, u0, v0, u1, v1, false); }

    private static Path quad(float[] f, float u0, float v0, float u1, float v1, boolean lineOnly) {
        Path p = new Path();
        float[] a = pt(f, u0, v0), b = pt(f, u1, v0), cc = pt(f, u1, v1), d = pt(f, u0, v1);
        if (lineOnly) { p.moveTo(a[0], a[1]); p.lineTo(d[0], d[1]); return p; }
        p.moveTo(a[0], a[1]); p.lineTo(b[0], b[1]); p.lineTo(cc[0], cc[1]); p.lineTo(d[0], d[1]); p.close();
        return p;
    }

    /** Білінійна точка грані f = [TL, TR, BR, BL]. */
    private static float[] pt(float[] f, float u, float v) {
        float tx = f[0] + (f[2] - f[0]) * u, ty = f[1] + (f[3] - f[1]) * u;
        float bx = f[6] + (f[4] - f[6]) * u, by = f[7] + (f[5] - f[7]) * u;
        return new float[]{tx + (bx - tx) * v, ty + (by - ty) * v};
    }

    private void panel(Canvas c, float u0, float v0, float u1, float v1) {
        fill.setShader(null); stroke.setStrokeWidth(0.7f); stroke.setColor(0xFF64748B);
        int nu = 4, nv = 2;
        for (int i = 0; i < nu; i++) for (int j = 0; j < nv; j++) {
            float a0 = u0 + (u1 - u0) * i / nu, a1 = u0 + (u1 - u0) * (i + 1) / nu;
            float b0 = v0 + (v1 - v0) * j / nv, b1 = v0 + (v1 - v0) * (j + 1) / nv;
            Path q = quad(ROOF, a0 + 0.008f, b0 + 0.02f, a1 - 0.008f, b1 - 0.02f);
            fill.setColor(0xFF0B1E3A); c.drawPath(q, fill);
            c.drawPath(q, stroke);
        }
    }

    private void drawLabels(Canvas c) {
        double pv = pv1 + pv2;
        label(c, 4, 34, 34, 120, ok ? (gridOn ? Main.w(Math.abs(grid)) : "немає світла") : "—",
                "Мережа", !gridOn ? Views.RED : Views.BLU);
        label(c, 200, 34, 262, 100, ok ? Main.w(pv) : "—", "Сонце", Views.YEL);
        label(c, 62, 268, 160, 214, ok ? Main.w(load) : "—", gen > 20 ? "Дім + ГЕН" : "Дім", Views.PUR);
        String bt = ok ? Main.w(Math.abs(batt)) : "—";
        label(c, 268, 268, 296, 252, bt, "АКБ", batt > 20 ? 0xFFFB923C : Views.GRN);
    }

    private void label(Canvas c, float x, float y, float lx, float ly, String val, String name, int color) {
        c.drawLine(x + 2, y + 6, lx, ly, lead);
        txt.setTextSize(30); txt.setFakeBoldText(true); txt.setColor(color);
        c.drawText(val, x, y - 2, txt);
        txt.setTextSize(21); txt.setFakeBoldText(false); txt.setColor(0xFFCBD5E1);
        c.drawText(name, x, y + 20, txt);
    }
}
