package ua.sister.energy;

import java.util.Calendar;

/** Імітація даних для перевірки інтерфейсу без обладнання. */
final class Demo {
    private Demo() { }

    static double sun(double hour) { return Math.max(0, Math.sin((hour - 6.5) / 13.0 * Math.PI)); }

    static double houseKw(double hour) { return 0.42 + 0.35 * Math.exp(-Math.pow(hour - 8, 2) / 3) + 0.9 * Math.exp(-Math.pow(hour - 19, 2) / 4) + 0.08 * Math.sin(hour * 3); }

    static Snapshot snapshot(long now) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        double hr = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0;
        double jit = Math.sin(now / 7000.0);
        Snapshot s = new Snapshot();
        s.ts = now;
        s.pv1 = Math.round(4300 * sun(hr) * (1 + 0.04 * jit));
        s.pv2 = 0; s.gen = 0;
        s.load = Math.round(houseKw(hr) * 1000);
        double surplus = s.pv() - s.load;
        s.batt = Math.round(surplus > 0 ? -Math.min(surplus, 9700) : -surplus * 0.9);
        s.grid = Math.round(s.load - s.pv() - s.batt);
        s.gridV = 229.8 + 1.2 * jit; s.gridHz = 50.0 + 0.02 * jit;
        s.tempHs = 38 + 6 * sun(hr); s.tempDc = 33 + 4 * sun(hr);
        s.state = 2; s.gridRelay = 1;
        double sumPv = 0, sumLd = 0, imp = 0, exp = 0, chg = 0, dis = 0;
        for (double h = 0; h < hr; h += 0.25) {
            double p = 4.3 * sun(h), l = houseKw(h), su = p - l;
            double b = su > 0 ? Math.min(su, 9.7) : -Math.min(-su * 0.9, 9.7);
            double g = l - p + b;
            sumPv += p / 4; sumLd += l / 4;
            if (g > 0) imp += g / 4; else exp -= g / 4;
            if (b > 0) chg += b / 4; else dis -= b / 4;
        }
        s.todayPv = sumPv; s.todayLoad = sumLd; s.todayImp = imp; s.todayExp = exp; s.todayChg = chg; s.todayDis = dis;
        return s;
    }

    static JkBmsProtocol.CellInfo bms(long now, Snapshot s) {
        JkBmsProtocol.CellInfo ci = new JkBmsProtocol.CellInfo();
        ci.cells = new double[16];
        for (int i = 0; i < 16; i++) ci.cells[i] = 3.318 + 0.013 * ((i * 7 % 11) / 10.0);
        double sum = 0; for (double v : ci.cells) sum += v;
        ci.voltage = sum;
        ci.current = -s.batt / sum;
        ci.power = ci.voltage * ci.current;
        ci.capacityAh = 420; ci.soc = 78; ci.remainAh = 327.6;
        ci.temps = new double[]{24.1, 23.6}; ci.tempMos = 27;
        return ci;
    }

    static double[][] hourly(long now) { return hourly(now, 0); }

    static double[][] hourly(long now, int back) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        int H = back > 0 ? 23 : c.get(Calendar.HOUR_OF_DAY);
        double[][] r = new double[3][24];
        for (int i = 0; i < 24; i++) {
            if (i > H) { r[0][i] = r[1][i] = r[2][i] = Double.NaN; continue; }
            double p = 4.3 * sun(i + 0.5), l = houseKw(i + 0.5), su = p - l;
            double b = su > 0 ? Math.min(su, 9.7) : -Math.min(-su * 0.9, 9.7);
            r[0][i] = p; r[1][i] = l; r[2][i] = Math.max(0, l - p + b);
        }
        return r;
    }

    static Object[][] days(long now, int n, Snapshot today) {
        if (n == 7) return week(now, today);
        Object[][] r = new Object[n][3];
        for (int i = 0; i < n; i++) {
            r[i][0] = now - (n - 1 - i) * 86400000L;
            double k = 0.55 + 0.45 * Math.abs(Math.sin(i * 1.7));
            r[i][1] = i < n - 1 ? 22.0 * k : today.todayPv;
            r[i][2] = i < n - 1 ? 11.5 + 2.5 * Math.sin(i * 0.9) : today.todayLoad;
        }
        return r;
    }

    static Object[][] week(long now, Snapshot today) {
        double[] pv = {19.8, 17.6, 6.9, 15.2, 21.0, 18.4}, ld = {12.4, 14.1, 13.2, 11.6, 12.0, 13.5};
        Object[][] r = new Object[7][3];
        for (int i = 0; i < 7; i++) {
            r[i][0] = now - (6 - i) * 86400000L;
            r[i][1] = i < 6 ? pv[i] : today.todayPv;
            r[i][2] = i < 6 ? ld[i] : today.todayLoad;
        }
        return r;
    }
}
