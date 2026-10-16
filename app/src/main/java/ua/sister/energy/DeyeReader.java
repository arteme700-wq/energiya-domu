package ua.sister.energy;

import java.io.IOException;

/**
 * Карта регістрів Deye SUN-*-SG0xLP1 (однофазний гібрид, низьковольтна АКБ).
 * Джерело: davidrapan/ha-solarman, inverter_definitions/deye_hybrid.yaml (Deye Modbus protocol V118).
 */
public final class DeyeReader {
    private final SolarmanV5 client;

    public DeyeReader(SolarmanV5 c) { client = c; }

    private static int s16(int v) { return (short) v; }

    private static double temp(int v) { return (v - 1000) * 0.1; }

    public Snapshot read() throws IOException {
        Snapshot s = new Snapshot();
        int[] a = client.readHolding(59, 50);   // 59..108
        int[] b = client.readHolding(150, 45);  // 150..194
        s.state = a[0];                         // 59  стан пристрою
        s.todayChg = a[70 - 59] * 0.1;          // 70  заряд АКБ за добу
        s.todayDis = a[71 - 59] * 0.1;          // 71  розряд АКБ за добу
        s.todayImp = a[76 - 59] * 0.1;          // 76  куплено з мережі
        s.todayExp = a[77 - 59] * 0.1;          // 77  продано в мережу
        s.gridHz = a[79 - 59] * 0.01;           // 79  частота мережі
        s.todayLoad = a[84 - 59] * 0.1;         // 84  споживання за добу
        s.tempDc = temp(a[90 - 59]);            // 90  температура DC
        s.tempHs = temp(a[91 - 59]);            // 91  температура радіатора
        s.todayPv = a[108 - 59] * 0.1;          // 108 вироблено СЕС за добу
        s.gridV = b[0] * 0.1;                   // 150 напруга мережі L1
        s.grid = s16(b[169 - 150]);             // 169 потужність мережі (+ імпорт)
        s.gen = b[166 - 150];                   // 166 потужність генератора (вхід GEN)
        s.load = s16(b[178 - 150]);             // 178 потужність навантаження
        s.invBattV = b[183 - 150] * 0.01;       // 183 напруга АКБ
        s.invSoc = b[184 - 150];                // 184 SOC з інвертора
        s.pv1 = b[186 - 150];                   // 186 PV1
        s.pv2 = b[187 - 150];                   // 187 PV2
        s.batt = s16(b[190 - 150]);             // 190 потужність АКБ (+ розряд)
        s.gridRelay = b[194 - 150];             // 194 стан мережевого реле
        s.ts = System.currentTimeMillis();
        return s;
    }
}
