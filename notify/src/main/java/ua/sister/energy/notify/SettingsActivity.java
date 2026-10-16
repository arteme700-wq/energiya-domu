package ua.sister.energy.notify;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Підказки для Xiaomi / HyperOS: без цього система «вбиває» фонові застосунки. */
public final class SettingsActivity extends Activity {
    private TextView res;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40, 40, 40, 40);
        l.setBackgroundColor(0xFF0B1220);
        l.addView(Ui.text(this, "Щоб сповіщення приходили завжди", 30, 0xFFFFFFFF, true));
        l.addView(Ui.text(this, "На телефонах Xiaomi / Poco (HyperOS) зробіть усі 4 кроки:", 22, 0xFFCBD5E1, false));
        step(l, "1. Автозапуск — увімкнути", "Безпека → Дозволи → Автозапуск → «Енергія — сповіщення».", () -> {
            if (!open(new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))) appDetails();
        });
        step(l, "2. Батарея — «Без обмежень»", "Налаштування застосунку → Економія заряду → Без обмежень.", () -> {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())
                    && open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))) return;
            if (!open(new Intent().setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", getPackageName()).putExtra("package_label", "Енергія"))) appDetails();
        });
        step(l, "3. Сповіщення — дозволити", "Увімкніть «Події: світло, АКБ, інвертор», а також «Спливаючі» та «На заблокованому екрані».", () -> {
            if (Build.VERSION.SDK_INT >= 26) open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            else appDetails();
        });
        step(l, "4. Закріпити в недавніх", "Відкрийте список недавніх застосунків, потягніть картку вниз — з'явиться замочок.", null);
        l.addView(Ui.text(this, "Оновлення (версія " + BuildConfig.VERSION_NAME + ")", 26, 0xFFFFFFFF, true));
        TextView u = Ui.button(this, "Перевірити оновлення", 0xFF334155);
        l.addView(u);
        res = Ui.text(this, Updater.last(this), 22, 0xFFE2E8F0, false); l.addView(res);
        u.setOnClickListener(v -> { res.setText("Перевіряю…"); new Thread(() -> { Updater.check(this, true); h.post(() -> res.setText(Updater.last(this))); }).start(); });
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFF0B1220); sv.addView(l);
        setContentView(sv);
    }

    private void step(LinearLayout l, String title, String text, Runnable action) {
        l.addView(Ui.text(this, title, 25, 0xFFFACC15, true));
        l.addView(Ui.text(this, text, 21, 0xFFE2E8F0, false));
        if (action != null) {
            TextView b = Ui.button(this, "Відкрити", 0xFF059669);
            b.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 6, 0, 22);
            l.addView(b, lp);
        }
    }

    private boolean open(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private void appDetails() { open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
}
