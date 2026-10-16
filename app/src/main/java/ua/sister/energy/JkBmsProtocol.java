package ua.sister.energy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Протокол JK BMS по BLE (сервіс FFE0, характеристика FFE1).
 * Джерело розмітки: syssi/esphome-jk-bms (components/jk_bms_ble).
 *
 * Команда: AA 55 90 EB | cmd | len | value(4, LE) | 0… | crc(sum 0..18) — 20 байт.
 * Відповідь: 55 AA EB 90 | type | counter | … ; CRC = сума байтів 0..298 (& 0xFF) у байті 299.
 * type 0x01 — налаштування, 0x02 — дані банок, 0x03 — інформація про пристрій.
 *
 * Варіанти кадру даних: JK02_24S (offset 0), JK02_32S (offset 16 / 32), JK04 (float, старі JK-B2A16S v3).
 * Визначаємо автоматично: JK04 — за float напругами; 24S/32S — за тим, де загальна напруга
 * (байт 118 або 150) збігається з сумою напруг банок.
 */
public final class JkBmsProtocol {
    public static final byte CMD_CELL_INFO = (byte) 0x96;
    public static final byte CMD_DEVICE_INFO = (byte) 0x97;

    public static final String PROTO_JK02_24S = "JK02_24S";
    public static final String PROTO_JK02_32S = "JK02_32S";
    public static final String PROTO_JK04 = "JK04";

    public static final int FRAME_SIZE = 300;
    private static final int MAX_BUFFER = 400;

    private JkBmsProtocol() {
    }

    public static byte[] buildCommand(byte cmd, int value, int length) {
        byte[] f = new byte[20];
        f[0] = (byte) 0xAA;
        f[1] = (byte) 0x55;
        f[2] = (byte) 0x90;
        f[3] = (byte) 0xEB;
        f[4] = cmd;
        f[5] = (byte) length;
        f[6] = (byte) value;
        f[7] = (byte) (value >> 8);
        f[8] = (byte) (value >> 16);
        f[9] = (byte) (value >> 24);
        f[19] = crc(f, 19);
        return f;
    }

    public static byte crc(byte[] data, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xFF;
        }
        return (byte) sum;
    }

    /** Збирає кадри з BLE-нотифікацій (по 20…244 байт). */
    public static final class Assembler {
        private final byte[] buf = new byte[MAX_BUFFER];
        private int len;
        public int crcErrors;

        /** @return повний кадр (300 байт) з правильним CRC або null. */
        public byte[] feed(byte[] chunk) {
            if (chunk == null || chunk.length == 0) {
                return null;
            }
            if (chunk.length >= 4 && (chunk[0] & 0xFF) == 0x55 && (chunk[1] & 0xFF) == 0xAA
                    && (chunk[2] & 0xFF) == 0xEB && (chunk[3] & 0xFF) == 0x90) {
                len = 0;
            }
            if (len + chunk.length > MAX_BUFFER) {
                len = 0;
                return null;
            }
            System.arraycopy(chunk, 0, buf, len, chunk.length);
            len += chunk.length;
            if (len < FRAME_SIZE) {
                return null;
            }
            if ((buf[0] & 0xFF) != 0x55 || (buf[1] & 0xFF) != 0xAA) {
                len = 0;
                return null;
            }
            byte[] frame = new byte[FRAME_SIZE];
            System.arraycopy(buf, 0, frame, 0, FRAME_SIZE);
            len = 0;
            if (crc(frame, FRAME_SIZE - 1) != frame[FRAME_SIZE - 1]) {
                crcErrors++;
                return null;
            }
            return frame;
        }
    }

    // ---------- розбір ----------

    public static final class DeviceInfo {
        public String vendor = "";
        public String hardware = "";
        public String software = "";
        public String name = "";
        public long uptimeSec;
        public long powerOnCount;
    }

    public static final class Settings {
        public int cellCount = -1;
        public double capacityAh = Double.NaN;
    }

    public static final class CellInfo {
        public String protocol;
        public double[] cells = new double[0];
        public double[] resistances = new double[0];
        public double voltage = Double.NaN;
        public double current = Double.NaN;
        public double power = Double.NaN;
        public int soc = -1;
        public int soh = -1;
        public double remainAh = Double.NaN;
        public double capacityAh = Double.NaN;
        public long cycles = -1;
        public double cycleAh = Double.NaN;
        public double tempMos = Double.NaN;
        public double[] temps = new double[0];
        public double balanceCurrent = Double.NaN;
        public boolean balancing;
        public boolean charging;
        public boolean discharging;
        public boolean heating;
        public long errorsMask;
        public long runtimeSec = -1;
        public int frameCounter;
    }

    private static int u8(byte[] d, int i) {
        return d[i] & 0xFF;
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static int i16(byte[] d, int i) {
        return (short) u16(d, i);
    }

    private static long u32(byte[] d, int i) {
        return ((long) u16(d, i)) | (((long) u16(d, i + 2)) << 16);
    }

    private static int i32(byte[] d, int i) {
        return (int) u32(d, i);
    }

    private static float f32(byte[] d, int i) {
        return Float.intBitsToFloat((int) u32(d, i));
    }

    private static String str(byte[] d, int off, int maxLen) {
        int end = off;
        while (end < off + maxLen && end < d.length && d[end] != 0) {
            end++;
        }
        return new String(d, off, end - off, StandardCharsets.US_ASCII).trim();
    }

    public static int frameType(byte[] frame) {
        return frame[4] & 0xFF;
    }

    public static DeviceInfo parseDeviceInfo(byte[] d) {
        DeviceInfo di = new DeviceInfo();
        di.vendor = str(d, 6, 16);
        di.hardware = str(d, 22, 8);
        di.software = str(d, 30, 8);
        di.uptimeSec = u32(d, 38);
        di.powerOnCount = u32(d, 42);
        di.name = str(d, 46, 16);
        return di;
    }

    /** Налаштування JK02 (для JK04 кількість банок у байті 34). */
    public static Settings parseSettings(byte[] d, String protocol) {
        Settings s = new Settings();
        if (PROTO_JK04.equals(protocol)) {
            s.cellCount = u8(d, 34);
            return s;
        }
        long cc = u32(d, 114);
        if (cc > 0 && cc <= 32) {
            s.cellCount = (int) cc;
        }
        long cap = u32(d, 130);
        if (cap > 0 && cap < 10_000_000L) {
            s.capacityAh = cap * 0.001;
        }
        return s;
    }

    /** Визначає варіант кадру даних банок. hint — попередньо визначений протокол або null. */
    public static String detectProtocol(byte[] d, String hint) {
        int c1 = u16(d, 6);
        float f1 = f32(d, 6);
        boolean jk02Like = c1 >= 1000 && c1 <= 5000;
        boolean jk04Like = f1 > 1.0f && f1 < 5.0f;
        if (!jk02Like && jk04Like) {
            return PROTO_JK04;
        }
        double err24 = totalError(d, 0);
        double err32 = totalError(d, 16);
        if (hint != null && !PROTO_JK04.equals(hint)) {
            double errHint = PROTO_JK02_32S.equals(hint) ? err32 : err24;
            if (errHint < 0.05) {
                return hint;
            }
        }
        return err32 < err24 ? PROTO_JK02_32S : PROTO_JK02_24S;
    }

    /** Відносна різниця між полем «напруга батареї» і сумою банок для даного offset. */
    private static double totalError(byte[] d, int offset) {
        int cells = 24 + offset / 2;
        double sum = 0;
        for (int i = 0; i < cells; i++) {
            sum += u16(d, 6 + i * 2) * 0.001;
        }
        double total = u32(d, 118 + offset * 2) * 0.001;
        if (total <= 0 || sum <= 0) {
            return 1e9;
        }
        return Math.abs(total - sum) / total;
    }

    public static CellInfo parseCellInfo(byte[] d, String protocol, int cellCountHint) {
        if (PROTO_JK04.equals(protocol)) {
            return parseJk04(d, cellCountHint);
        }
        CellInfo ci = new CellInfo();
        ci.protocol = protocol;
        ci.frameCounter = u8(d, 5);
        int off = PROTO_JK02_32S.equals(protocol) ? 16 : 0;
        int maxCells = 24 + off / 2;

        long mask = u32(d, 54 + off);
        int count = Long.bitCount(mask);
        if (cellCountHint > 0 && cellCountHint <= maxCells) {
            count = cellCountHint;
        }
        if (count <= 0 || count > maxCells) {
            count = 0;
            while (count < maxCells && u16(d, 6 + count * 2) > 0) {
                count++;
            }
        }
        ci.cells = new double[count];
        ci.resistances = new double[count];
        for (int i = 0; i < count; i++) {
            ci.cells[i] = u16(d, 6 + i * 2) * 0.001;
            ci.resistances[i] = u16(d, 64 + off + i * 2) * 0.001;
        }
        int o = off * 2;
        if (PROTO_JK02_32S.equals(protocol)) {
            ci.tempMos = i16(d, 112 + o) * 0.1;
        } else {
            ci.tempMos = i16(d, 134 + o) * 0.1;
        }
        ci.voltage = u32(d, 118 + o) * 0.001;
        ci.current = i32(d, 126 + o) * 0.001;
        ci.power = ci.voltage * ci.current;
        List<Double> temps = new ArrayList<>();
        temps.add(i16(d, 130 + o) * 0.1);
        temps.add(i16(d, 132 + o) * 0.1);
        if (PROTO_JK02_32S.equals(protocol)) {
            temps.add(i16(d, 226 + o) * 0.1);
            temps.add(i16(d, 224 + o) * 0.1);
            temps.add(i16(d, 222 + o) * 0.1);
            ci.errorsMask = u32(d, 134 + o);
        } else {
            ci.errorsMask = u16(d, 136 + o);
        }
        ci.temps = new double[temps.size()];
        for (int i = 0; i < temps.size(); i++) {
            ci.temps[i] = temps.get(i);
        }
        ci.balanceCurrent = i16(d, 138 + o) * 0.001;
        ci.balancing = u8(d, 140 + o) != 0;
        ci.soc = u8(d, 141 + o);
        ci.remainAh = u32(d, 142 + o) * 0.001;
        ci.capacityAh = u32(d, 146 + o) * 0.001;
        ci.cycles = u32(d, 150 + o);
        ci.cycleAh = u32(d, 154 + o) * 0.001;
        ci.soh = u8(d, 158 + o);
        ci.runtimeSec = u32(d, 162 + o);
        ci.charging = u8(d, 166 + o) != 0;
        ci.discharging = u8(d, 167 + o) != 0;
        ci.heating = u8(d, 183 + o) != 0;
        return ci;
    }

    private static CellInfo parseJk04(byte[] d, int cellCountHint) {
        CellInfo ci = new CellInfo();
        ci.protocol = PROTO_JK04;
        ci.frameCounter = u8(d, 5);
        int count = 0;
        while (count < 24 && f32(d, 6 + count * 4) > 0.5f) {
            count++;
        }
        if (cellCountHint > 0 && cellCountHint <= 24) {
            count = Math.min(count, cellCountHint);
        }
        ci.cells = new double[count];
        ci.resistances = new double[count];
        double total = 0;
        for (int i = 0; i < count; i++) {
            ci.cells[i] = Math.round(f32(d, 6 + i * 4) * 1000.0) / 1000.0;
            ci.resistances[i] = f32(d, 102 + i * 4);
            total += ci.cells[i];
        }
        ci.voltage = total;
        ci.balancing = u8(d, 220) != 0;
        return ci;
    }

    public static List<Integer> errorBits(long mask) {
        List<Integer> bits = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            if (((mask >> i) & 1L) != 0 && i != 3) {
                bits.add(i);
            }
        }
        return bits;
    }
}
