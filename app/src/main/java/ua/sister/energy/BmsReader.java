package ua.sister.energy;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * JK BMS по BLE лише на читання. Надсилаються тільки запити-«опитування» 0x97 (інфо) і 0x96 (дані банок)
 * — так працює протокол JK; команд зміни налаштувань у класі немає.
 */
@SuppressLint("MissingPermission")
public final class BmsReader {
    private static final String TAG = "BmsReader";
    private static final UUID SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener { void onCell(JkBmsProtocol.CellInfo ci); void onStatus(boolean connected); }

    private final Context ctx;
    private final String mac;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final JkBmsProtocol.Assembler asm = new JkBmsProtocol.Assembler();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ch;
    private String protocol;
    private int cellCount = -1;
    private long lastFrame;
    private boolean running;

    public BmsReader(Context c, String mac, Listener l) { ctx = c.getApplicationContext(); this.mac = mac; listener = l; }

    public void start() { running = true; connect(); h.postDelayed(watchdog, 30000); }

    public void stop() { running = false; h.removeCallbacksAndMessages(null); close(); }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (System.currentTimeMillis() - lastFrame > 60000) { close(); connect(); }
            h.postDelayed(this, 30000);
        }
    };

    private void close() {
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null; ch = null;
        listener.onStatus(false);
    }

    private void connect() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled() || mac == null || mac.isEmpty()) return;
            BluetoothDevice dev = ad.getRemoteDevice(mac);
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { Log.w(TAG, "connect", e); }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { h.post(() -> g.discoverServices()); }
            else h.post(() -> { close(); if (running) h.postDelayed(BmsReader.this::connect, 10000); });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            BluetoothGattCharacteristic c = s != null ? s.getCharacteristic(CHR) : null;
            if (c == null) { h.post(BmsReader.this::close); return; }
            ch = c;
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            h.postDelayed(() -> poll(JkBmsProtocol.CMD_DEVICE_INFO), 500);
            h.postDelayed(() -> poll(JkBmsProtocol.CMD_CELL_INFO), 1800);
            h.post(() -> listener.onStatus(true));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            h.post(() -> onChunk(v));
        }
    };

    /** Дозволені лише запити читання 0x96/0x97. */
    private void poll(byte cmd) {
        if (cmd != JkBmsProtocol.CMD_CELL_INFO && cmd != JkBmsProtocol.CMD_DEVICE_INFO) return;
        if (gatt == null || ch == null) return;
        ch.setValue(JkBmsProtocol.buildCommand(cmd, 0, 0));
        ch.setWriteType((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gatt.writeCharacteristic(ch);
    }

    private void onChunk(byte[] chunk) {
        byte[] f = asm.feed(chunk);
        if (f == null) return;
        try {
            int t = JkBmsProtocol.frameType(f);
            if (t == 0x01) { cellCount = JkBmsProtocol.parseSettings(f, protocol).cellCount; }
            else if (t == 0x02) {
                protocol = JkBmsProtocol.detectProtocol(f, protocol);
                lastFrame = System.currentTimeMillis();
                listener.onCell(JkBmsProtocol.parseCellInfo(f, protocol, cellCount));
            }
        } catch (Exception e) { Log.w(TAG, "parse", e); }
    }
}
