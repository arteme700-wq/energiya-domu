package ua.sister.energy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Клієнт Solarman V5 (TCP 8899) лише для ЧИТАННЯ: Modbus функція 0x03.
 * Протокол: jmccrohan/pysolarmanv5. Жодних функцій запису (0x06/0x10) у класі немає.
 */
public final class SolarmanV5 {
    private static final int FUNC_READ_HOLDING = 0x03; // єдина дозволена функція
    private final String host;
    private final int port;
    private final long serial;
    private final int slave;
    private int seq = 1;

    public SolarmanV5(String host, int port, long serial, int slave) {
        this.host = host; this.port = port; this.serial = serial; this.slave = slave;
    }

    /** Читає count регістрів з адреси start (count ≤ 100). */
    public synchronized int[] readHolding(int start, int count) throws IOException {
        if (count < 1 || count > 100) throw new IllegalArgumentException("count");
        byte[] rtu = new byte[8];
        rtu[0] = (byte) slave; rtu[1] = (byte) FUNC_READ_HOLDING;
        rtu[2] = (byte) (start >> 8); rtu[3] = (byte) start;
        rtu[4] = (byte) (count >> 8); rtu[5] = (byte) count;
        int crc = crc16(rtu, 6);
        rtu[6] = (byte) crc; rtu[7] = (byte) (crc >> 8);
        byte[] req = frame(rtu);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(6000);
            OutputStream os = s.getOutputStream();
            os.write(req); os.flush();
            InputStream is = s.getInputStream();
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                byte[] resp = readFrame(is);
                // відповідь має код 0x1510; інші кадри (heartbeat тощо) пропускаємо
                if ((resp[3] & 0xFF) != 0x10 || (resp[4] & 0xFF) != 0x15) continue;
                return parse(resp, count);
            }
            throw new IOException("timeout");
        }
    }

    private byte[] frame(byte[] rtu) {
        int payloadLen = 15 + rtu.length;
        byte[] f = new byte[11 + payloadLen + 2];
        f[0] = (byte) 0xA5;
        f[1] = (byte) payloadLen; f[2] = (byte) (payloadLen >> 8);
        f[3] = 0x10; f[4] = 0x45; // control 0x4510 (request)
        seq = (seq + 1) & 0xFF;
        f[5] = (byte) seq; f[6] = 0;
        for (int i = 0; i < 4; i++) f[7 + i] = (byte) (serial >> (8 * i));
        f[11] = 0x02; // frame type: solar inverter
        // 2 байти sensor type + 3×4 байти часу = нулі
        System.arraycopy(rtu, 0, f, 26, rtu.length);
        int sum = 0;
        for (int i = 1; i < f.length - 2; i++) sum += f[i] & 0xFF;
        f[f.length - 2] = (byte) sum;
        f[f.length - 1] = 0x15;
        return f;
    }

    private static byte[] readFrame(InputStream is) throws IOException {
        int b;
        do { b = is.read(); if (b < 0) throw new IOException("closed"); } while (b != 0xA5);
        byte[] hdr = new byte[11];
        hdr[0] = (byte) 0xA5;
        readFully(is, hdr, 1, 10);
        int len = (hdr[1] & 0xFF) | ((hdr[2] & 0xFF) << 8);
        if (len > 1024) throw new IOException("bad len");
        byte[] f = new byte[11 + len + 2];
        System.arraycopy(hdr, 0, f, 0, 11);
        readFully(is, f, 11, len + 2);
        if ((f[f.length - 1] & 0xFF) != 0x15) throw new IOException("bad end");
        int sum = 0;
        for (int i = 1; i < f.length - 2; i++) sum += f[i] & 0xFF;
        if ((byte) sum != f[f.length - 2]) throw new IOException("bad checksum");
        return f;
    }

    private static void readFully(InputStream is, byte[] b, int off, int n) throws IOException {
        while (n > 0) { int r = is.read(b, off, n); if (r < 0) throw new IOException("closed"); off += r; n -= r; }
    }

    static int[] parse(byte[] f, int count) throws IOException {
        int m = 25; // 11 заголовок + 14 байт службової частини відповіді
        if (f.length < m + 5) throw new IOException("short");
        int func = f[m + 1] & 0xFF;
        if (func != FUNC_READ_HOLDING) throw new IOException("modbus error 0x" + Integer.toHexString(func));
        int bc = f[m + 2] & 0xFF;
        if (bc != count * 2 || f.length < m + 3 + bc + 2 + 2) throw new IOException("byte count " + bc);
        int crc = crc16(f, m, 3 + bc);
        int got = (f[m + 3 + bc] & 0xFF) | ((f[m + 4 + bc] & 0xFF) << 8);
        if (crc != got) throw new IOException("modbus crc");
        int[] r = new int[count];
        for (int i = 0; i < count; i++) r[i] = ((f[m + 3 + 2 * i] & 0xFF) << 8) | (f[m + 4 + 2 * i] & 0xFF);
        return r;
    }

    static int crc16(byte[] d, int len) { return crc16(d, 0, len); }

    static int crc16(byte[] d, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
        }
        return crc;
    }
}
