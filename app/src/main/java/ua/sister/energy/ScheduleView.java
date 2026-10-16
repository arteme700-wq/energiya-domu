package ua.sister.energy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Смуга доби: 48 півгодин (є світло / немає / можливо / невідомо) і позначка «зараз». */
final class ScheduleView extends View {
    Info.Day day; long now; final float u;
    ScheduleView(Context c, float u) { super(c); this.u = u; }
    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), lab = 24 * u, bh = h - lab - 4 * u, gap = 2 * u, cw = (w + gap) / 24f;
        Paint on = Views.paint(0xFF10B981, 0), off = Views.paint(0xFF475569, 0), may = Views.paint(0xFFB45309, 0), unk = Views.paint(0xFF1E293B, 0);
        for (int hr = 0; hr < 24; hr++) {
            float x0 = hr * cw, x1 = x0 + cw - gap, xm = (x0 + x1) / 2;
            for (int k = 0; k < 2; k++) {
                int i = hr * 2 + k;
                Paint p = day == null ? unk : day.off[i] ? off : day.maybe[i] ? may : on;
                c.drawRect(k == 0 ? x0 : xm, 0, k == 0 ? xm : x1, bh, p);
            }
        }
        Paint t = Views.paint(Views.GRY, 19 * u); t.setTextAlign(Paint.Align.CENTER);
        for (int hr = 0; hr <= 24; hr += 3) {
            t.setTextAlign(hr == 0 ? Paint.Align.LEFT : hr == 24 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            c.drawText(String.format(java.util.Locale.US, "%02d", hr), Math.min(w, hr * cw), h - 2 * u, t);
        }
        if (day != null && now >= day.start && now < day.start + 86400000L) {
            float x = (float) ((now - day.start) / 86400000.0 * w);
            Paint m = Views.paint(0xFFFFFFFF, 0); c.drawRect(x - 1.5f * u, 0, x + 1.5f * u, bh, m);
        }
    }
}
