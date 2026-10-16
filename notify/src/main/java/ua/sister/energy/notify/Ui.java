package ua.sister.energy.notify;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Великі шрифти для людей зі слабким зором. */
final class Ui {
    private Ui() { }
    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 8, 0, 8);
        return t;
    }
    static Button button(Context c, String s, int bg) {
        Button b = new Button(c); b.setText(s); b.setTextSize(22); b.setAllCaps(false); b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setGravity(Gravity.CENTER); b.setMinHeight(150); b.setPadding(24, 24, 24, 24);
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(28); b.setBackground(g);
        return b;
    }
}
