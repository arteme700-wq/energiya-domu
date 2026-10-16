package ua.sister.energy;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Локальна історія: хвилинні зразки, денні підсумки, відключення мережі. */
public final class HistoryDb extends SQLiteOpenHelper {
    public HistoryDb(Context c) { super(c, "history.sqlite", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples(ts INTEGER PRIMARY KEY, pv REAL, load REAL, grid REAL, batt REAL, soc REAL)");
        db.execSQL("CREATE TABLE daily(day TEXT PRIMARY KEY, pv REAL, load REAL, imp REAL, exp REAL, chg REAL, dis REAL)");
        db.execSQL("CREATE TABLE outages(start INTEGER PRIMARY KEY, end INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    public static String day(long ts) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts)); }

    public void addSample(Snapshot s, double soc) {
        ContentValues v = new ContentValues();
        v.put("ts", s.ts / 1000); v.put("pv", s.pv()); v.put("load", s.load); v.put("grid", s.grid);
        v.put("batt", s.batt); v.put("soc", soc);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        // денні лічильники інвертора лише зростають протягом доби: беремо MAX
        String d = day(s.ts);
        db.execSQL("INSERT OR IGNORE INTO daily(day,pv,load,imp,exp,chg,dis) VALUES(?,0,0,0,0,0,0)", new Object[]{d});
        db.execSQL("UPDATE daily SET pv=MAX(pv,?),load=MAX(load,?),imp=MAX(imp,?),exp=MAX(exp,?),chg=MAX(chg,?),dis=MAX(dis,?) WHERE day=?",
                new Object[]{nz(s.todayPv), nz(s.todayLoad), nz(s.todayImp), nz(s.todayExp), nz(s.todayChg), nz(s.todayDis), d});
        db.delete("samples", "ts<?", new String[]{String.valueOf(s.ts / 1000 - 40L * 86400)});
    }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    /** [3][24]: середні кВт по годинах сьогодні (pv, load, grid-імпорт); NaN = немає даних. */
    public double[][] hourly(long now) { return hourly(now, 0); }

    /** Те саме для дня зі зсувом (0 = сьогодні, 1 = вчора). */
    public double[][] hourly(long now, int back) {
        double[][] r = new double[3][24];
        for (double[] a : r) java.util.Arrays.fill(a, Double.NaN);
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, -back);
        long t0 = c.getTimeInMillis() / 1000;
        try (Cursor q = getReadableDatabase().rawQuery(
                "SELECT (ts-?)/3600 h, AVG(pv), AVG(load), AVG(MAX(grid,0)) FROM samples WHERE ts>=? AND ts<? GROUP BY h",
                new String[]{String.valueOf(t0), String.valueOf(t0), String.valueOf(t0 + 86400)})) {
            while (q.moveToNext()) {
                int h = q.getInt(0);
                if (h < 0 || h > 23) continue;
                for (int k = 0; k < 3; k++) r[k][h] = q.getDouble(k + 1) / 1000.0;
            }
        }
        return r;
    }

    /** Останні 7 днів: {day, pv, load}. */
    public Object[][] week(long now) { return days(now, 7); }

    /** Останні n днів: {day, pv, load}. */
    public Object[][] days(long now, int n) {
        Object[][] r = new Object[n][3];
        for (int i = 0; i < n; i++) {
            long t = now - (n - 1 - i) * 86400000L;
            String d = day(t);
            r[i][0] = t; r[i][1] = 0.0; r[i][2] = 0.0;
            try (Cursor q = getReadableDatabase().rawQuery("SELECT pv,load FROM daily WHERE day=?", new String[]{d})) {
                if (q.moveToNext()) { r[i][1] = q.getDouble(0); r[i][2] = q.getDouble(1); }
            }
        }
        return r;
    }

    public void gridChanged(boolean on, long ts) {
        SQLiteDatabase db = getWritableDatabase();
        if (!on) db.execSQL("INSERT OR IGNORE INTO outages(start,end) VALUES(?,NULL)", new Object[]{ts / 1000});
        else db.execSQL("UPDATE outages SET end=? WHERE end IS NULL", new Object[]{ts / 1000});
    }

    /** {start, end(0=триває)} останнього відключення або null. */
    public long[] lastOutage() {
        try (Cursor q = getReadableDatabase().rawQuery("SELECT start, IFNULL(end,0) FROM outages ORDER BY start DESC LIMIT 1", null)) {
            if (q.moveToNext()) return new long[]{q.getLong(0) * 1000, q.getLong(1) * 1000};
        }
        return null;
    }

    /** {pv, load, imp, exp, chg, dis} за день або null. */
    public double[] dayRow(String d) {
        try (Cursor q = getReadableDatabase().rawQuery("SELECT pv,load,imp,exp,chg,dis FROM daily WHERE day=?", new String[]{d})) {
            if (q.moveToNext()) { double[] r = new double[6]; for (int i = 0; i < 6; i++) r[i] = q.getDouble(i); return r; }
        }
        return null;
    }

    /** Відключення з моменту since: список {start, end(0=триває)}, новіші першими. */
    public java.util.List<long[]> outages(long since) {
        java.util.List<long[]> r = new java.util.ArrayList<>();
        try (Cursor q = getReadableDatabase().rawQuery("SELECT start, IFNULL(end,0) FROM outages WHERE start>=? OR end IS NULL ORDER BY start DESC LIMIT 20",
                new String[]{String.valueOf(since / 1000)})) {
            while (q.moveToNext()) r.add(new long[]{q.getLong(0) * 1000, q.getLong(1) * 1000});
        }
        return r;
    }
}
