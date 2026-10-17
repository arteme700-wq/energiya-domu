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
    private TextView res, stripState;

    private LinearLayout.LayoutParams gap() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 6, 0, 18); return lp; }

    private void stripRefresh() {
        if (stripState == null) return;
        Boolean a = StripOverlay.alert;
        String st = a == null ? "Стан тривоги: ще перевіряю…" : a ? "Зараз: ТРИВОГА" : "Зараз: тривоги немає";
        if (!StripOverlay.canDraw(this)) st += "\nНемає дозволу «Поверх інших додатків» — смуга не показується";
        stripState.setText(st);
    }

    @Override protected void onResume() { super.onResume(); stripRefresh(); StripOverlay.apply(this); }
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
        l.addView(Ui.text(this, "Червона смуга під час тривоги", 26, 0xFFFFFFFF, true));
        l.addView(Ui.text(this, "Тихо, без звуку і сповіщень: під час повітряної тривоги (Броварський район) верх екрана стає червоним.", 21, 0xFFE2E8F0, false));
        stripState = Ui.text(this, "", 22, 0xFFFACC15, true); l.addView(stripState);
        TextView sw = Ui.button(this, "", 0xFF334155); l.addView(sw, gap());
        sw.setText(StripOverlay.enabled(this) ? "Смуга: увімкнена (вимкнути)" : "Смуга: вимкнена (увімкнути)");
        sw.setOnClickListener(v -> { StripOverlay.setEnabled(this, !StripOverlay.enabled(this));
            sw.setText(StripOverlay.enabled(this) ? "Смуга: увімкнена (вимкнути)" : "Смуга: вимкнена (увімкнути)"); stripRefresh(); });
        TextView perm = Ui.button(this, "Дозволити «Поверх інших додатків»", 0xFF059669); l.addView(perm, gap());
        perm.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 23
                && !open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))) appDetails(); });
        l.addView(Ui.text(this, "Якщо перемикач сірий: Налаштування → Додатки → «Енергія — сповіщення» → ⋮ → «Дозволити обмежені налаштування», потім знову ця кнопка.", 20, 0xFFCBD5E1, false));
        TextView t5 = Ui.button(this, "Показати 5 с", 0xFFE11D48); l.addView(t5, gap());
        t5.setOnClickListener(v -> { if (!StripOverlay.canDraw(this)) { stripState.setText("Спершу дайте дозвіл «Поверх інших додатків»"); return; }
            StripOverlay.test5s(this); moveTaskToBack(true); });
        stripRefresh();
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
