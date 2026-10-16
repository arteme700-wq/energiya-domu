package ua.sister.energy;

/** Поточні показники інвертора (NaN = немає даних). */
public final class Snapshot {
    public long ts;
    public double pv1 = Double.NaN, pv2 = Double.NaN, load = Double.NaN, grid = Double.NaN, batt = Double.NaN;
    public double gridV = Double.NaN, gridHz = Double.NaN, tempHs = Double.NaN, tempDc = Double.NaN;
    public double invSoc = Double.NaN, invBattV = Double.NaN;
    public double todayPv = Double.NaN, todayLoad = Double.NaN, todayImp = Double.NaN, todayExp = Double.NaN,
            todayChg = Double.NaN, todayDis = Double.NaN;
    public double gen = Double.NaN;
    public int state = -1;
    public int gridRelay = -1;

    public double pv() { return (Double.isNaN(pv1) ? 0 : pv1) + (Double.isNaN(pv2) ? 0 : pv2); }

    /** Чи є напруга в мережі. */
    public boolean gridOn() { return !Double.isNaN(gridV) && gridV > 170; }
}
