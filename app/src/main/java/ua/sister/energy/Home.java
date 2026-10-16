package ua.sister.energy;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

/** Домашній екран (лаунчер): закріплення через власника пристрою і запасні виходи. */
final class Home {
    private static final String TAG = "SisterEnergy";
    private Home() { }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    static boolean wanted(Context c) { return sp(c).getBoolean("home_lock", true); }

    private static DevicePolicyManager dpm(Context c) { return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE); }

    private static ComponentName admin(Context c) { return new ComponentName(c, AdminReceiver.class); }

    /** Під час запуску: якщо ми власник пристрою і закріплення не вимкнене — робимо себе домашнім екраном без запитів. */
    static void apply(Context c) {
        if (!wanted(c) || !Updater.isOwner(c)) return;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            dpm(c).addPersistentPreferredActivity(admin(c), f, new ComponentName(c, MainActivity.class));
        } catch (Exception e) { Log.w(TAG, "home", e); }
    }

    /** Прибрати закріплення (система знову спитає, який домашній екран використовувати). */
    static void clear(Context c) {
        sp(c).edit().putBoolean("home_lock", false).apply();
        if (!Updater.isOwner(c)) return;
        try { dpm(c).clearPackagePersistentPreferredActivities(admin(c), c.getPackageName()); } catch (Exception e) { Log.w(TAG, "home clear", e); }
    }

    static void enable(Context c) { sp(c).edit().putBoolean("home_lock", true).apply(); apply(c); }

    /** Захист від блокування: якщо застосунок падає 3 рази за 2 хвилини — знімаємо закріплення домашнього екрана. */
    static void installCrashGuard(Context ctx) {
        final Context c = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Guard) return;
        Thread.setDefaultUncaughtExceptionHandler(new Guard(c, prev));
    }

    private static final class Guard implements Thread.UncaughtExceptionHandler {
        final Context c; final Thread.UncaughtExceptionHandler prev;
        Guard(Context c, Thread.UncaughtExceptionHandler prev) { this.c = c; this.prev = prev; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                SharedPreferences p = sp(c);
                long now = System.currentTimeMillis();
                long first = p.getLong("crash_first", 0); int n = p.getInt("crash_n", 0);
                if (now - first > 120000) { first = now; n = 0; }
                n++;
                p.edit().putLong("crash_first", first).putInt("crash_n", n).commit();
                if (n >= 3) clear(c);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }
}
