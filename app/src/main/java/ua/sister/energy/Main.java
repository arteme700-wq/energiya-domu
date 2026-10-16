package ua.sister.energy;

import java.util.Locale;

/** Форматування чисел. */
final class Main {
    private Main() { }
    static String n0(double v) {
        if (Double.isNaN(v)) return "—";
        String s = String.format(Locale.US, "%,d", Math.round(v));
        return s.replace(',', ' ').replace('-', '−');
    }
    static String w(double v) { return Double.isNaN(v) ? "—" : n0(v) + " Вт"; }
    static String f(double v, int d) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%." + d + "f", v).replace('-', '−'); }
    static String dur(double hours) {
        if (Double.isNaN(hours) || hours < 0 || hours > 99) return "—";
        int m = (int) Math.round(hours * 60);
        return m / 60 + " г " + m % 60 + " хв";
    }
}
