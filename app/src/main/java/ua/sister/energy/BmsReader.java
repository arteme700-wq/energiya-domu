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

    private static final long CONNECT_TIMEOUT_MS = 25_000L;
    private static final long DISCOVER_DELAY_MS = 600L;
    private static final long CCCD_FALLBACK_MS = 1_500L;
    private static final long WATCHDOG_MS = 5_000L;
    private static final long NO_DATA_REQUEST_MS = 12_000L;
    private static final long NO_DATA_RECONNECT_MS = 50_000L;
    private static final long RECONNECT_DELAY_MS = 10_000L;
    private static final long CLOSE_TIMEOUT_MS = 600L;

    public interface Listener {
        void onCell(JkBmsProtocol.CellInfo ci);
        void onStatus(boolean connected);
        default void onDevice(JkBmsProtocol.DeviceInfo di) {}
    }

    private final Context ctx;
    private final String mac;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final JkBmsProtocol.Assembler asm = new JkBmsProtocol.Assembler();
    private BluetoothGatt gatt;
    private BluetoothGatt closingGatt;
    private BluetoothGattCharacteristic ch;
    private String protocol;
    private int cellCount = -1;
    private long lastFrame;
    private long lastRequest;
    private boolean running;
    private boolean connected;
    private boolean connecting;
    private boolean requestsStarted;
    private boolean reconnectScheduled;
    private boolean connectAfterClose;

    public BmsReader(Context c, String mac, Listener l) {
        ctx = c.getApplicationContext();
        this.mac = mac;
        listener = l;
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrame = System.currentTimeMillis();
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, WATCHDOG_MS);
        connect();
    }

    public void stop() {
        running = false;
        connectAfterClose = false;
        reconnectScheduled = false;
        h.removeCallbacksAndMessages(null);
        BluetoothGatt old = gatt;
        gatt = null;
        ch = null;
        connected = false;
        connecting = false;
        requestsStarted = false;
        listener.onStatus(false);
        if (closingGatt != null && closingGatt != old) {
            try { closingGatt.close(); } catch (Exception ignored) { }
            closingGatt = null;
        }
        if (old != null) {
            closingGatt = old;
            try { old.disconnect(); } catch (Exception ignored) { }
            h.postDelayed(forceCloseTask, CLOSE_TIMEOUT_MS);
        }
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override public void run() {
            if (!running || !connecting || gatt == null) return;
            Log.w(TAG, "connect timeout");
            closeAsync();
            scheduleReconnect(RECONNECT_DELAY_MS);
        }
    };

    private final Runnable discoverTask = new Runnable() {
        @Override public void run() {
            if (!running || gatt == null || !connecting) return;
            if (!gatt.discoverServices()) {
                closeAsync();
                scheduleReconnect(RECONNECT_DELAY_MS);
            }
        }
    };

    private final Runnable startReqFallback = new Runnable() {
        @Override public void run() {
            if (gatt != null && running && !requestsStarted) startRequests(gatt);
        }
    };

    private final Runnable reqDeviceTask = new Runnable() {
        @Override public void run() {
            if (running && connected && gatt != null) poll(JkBmsProtocol.CMD_DEVICE_INFO);
        }
    };

    private final Runnable reqCellTask = new Runnable() {
        @Override public void run() {
            if (running && connected && gatt != null) poll(JkBmsProtocol.CMD_CELL_INFO);
        }
    };

    private final Runnable reconnectTask = new Runnable() {
        @Override public void run() {
            reconnectScheduled = false;
            if (running) connect();
        }
    };

    private final Runnable forceCloseTask = new Runnable() {
        @Override public void run() {
            if (closingGatt != null) {
                try { closingGatt.close(); } catch (Exception ignored) { }
                closingGatt = null;
            }
            if (connectAfterClose && running && gatt == null && !connecting && !reconnectScheduled) {
                connectAfterClose = false;
                h.postDelayed(reconnectTask, 300L);
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            if (connected && gatt != null) {
                long silent = now - lastFrame;
                if (silent > NO_DATA_RECONNECT_MS) {
                    Log.w(TAG, "watchdog: no data " + (silent / 1000) + "s, reconnecting");
                    closeAsync();
                    scheduleReconnect(3_000L);
                } else if (silent > NO_DATA_REQUEST_MS && now - lastRequest > NO_DATA_REQUEST_MS) {
                    poll(JkBmsProtocol.CMD_CELL_INFO);
                }
            } else if (gatt == null && !connecting && closingGatt == null && !reconnectScheduled) {
                scheduleReconnect(5_000L);
            }
            h.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void scheduleReconnect(long delayMs) {
        if (!running || reconnectScheduled) return;
        reconnectScheduled = true;
        h.postDelayed(reconnectTask, delayMs);
    }

    private void closeAsync() {
        h.removeCallbacks(connectTimeout);
        h.removeCallbacks(discoverTask);
        h.removeCallbacks(startReqFallback);
        h.removeCallbacks(reqDeviceTask);
        h.removeCallbacks(reqCellTask);
        BluetoothGatt old = gatt;
        gatt = null;
        ch = null;
        connected = false;
        connecting = false;
        requestsStarted = false;
        listener.onStatus(false);
        if (old != null) {
            if (closingGatt != null && closingGatt != old) {
                h.removeCallbacks(forceCloseTask);
                try { closingGatt.close(); } catch (Exception ignored) { }
                closingGatt = null;
            }
            closingGatt = old;
            try { old.disconnect(); } catch (Exception ignored) { }
            h.removeCallbacks(forceCloseTask);
            h.postDelayed(forceCloseTask, CLOSE_TIMEOUT_MS);
        }
    }

    private void connect() {
        if (!running || gatt != null || connecting) return;
        if (closingGatt != null) {
            connectAfterClose = true;
            return;
        }
        reconnectScheduled = false;
        connectAfterClose = false;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled() || mac == null || mac.isEmpty()) return;
            BluetoothDevice dev = ad.getRemoteDevice(mac);
            connecting = true;
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                connecting = false;
                scheduleReconnect(RECONNECT_DELAY_MS);
                return;
            }
            h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "connect", e);
            connecting = false;
            gatt = null;
            scheduleReconnect(RECONNECT_DELAY_MS);
        }
    }

    private void startRequests(BluetoothGatt g) {
        if (g != gatt || !running || requestsStarted || ch == null) return;
        requestsStarted = true;
        h.removeCallbacks(connectTimeout);
        h.removeCallbacks(startReqFallback);
        connected = true;
        connecting = false;
        lastFrame = System.currentTimeMillis();
        lastRequest = 0;
        listener.onStatus(true);
        h.removeCallbacks(reqDeviceTask);
        h.removeCallbacks(reqCellTask);
        h.postDelayed(reqDeviceTask, 300L);
        h.postDelayed(reqCellTask, 1500L);
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            h.post(() -> {
                if (g == closingGatt) {
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        h.removeCallbacks(forceCloseTask);
                        try { g.close(); } catch (Exception ignored) { }
                        closingGatt = null;
                        if (connectAfterClose && running && gatt == null && !connecting && !reconnectScheduled) {
                            connectAfterClose = false;
                            h.postDelayed(reconnectTask, 300L);
                        }
                    }
                    return;
                }
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    h.removeCallbacks(discoverTask);
                    h.postDelayed(discoverTask, DISCOVER_DELAY_MS);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        h.removeCallbacks(connectTimeout);
                        h.removeCallbacks(discoverTask);
                        h.removeCallbacks(startReqFallback);
                        h.removeCallbacks(reqDeviceTask);
                        h.removeCallbacks(reqCellTask);
                        gatt = null;
                        ch = null;
                        connected = false;
                        connecting = false;
                        requestsStarted = false;
                        try { g.close(); } catch (Exception ignored) { }
                        listener.onStatus(false);
                    } else {
                        closeAsync();
                    }
                    if (running) scheduleReconnect(RECONNECT_DELAY_MS);
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> {
                if (g != gatt || !running) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeAsync();
                    scheduleReconnect(RECONNECT_DELAY_MS);
                    return;
                }
                BluetoothGattService s = g.getService(SVC);
                BluetoothGattCharacteristic c = s != null ? s.getCharacteristic(CHR) : null;
                if (c == null) {
                    for (BluetoothGattService svc : g.getServices()) {
                        BluetoothGattCharacteristic cc = svc.getCharacteristic(CHR);
                        if (cc != null) { c = cc; break; }
                    }
                }
                if (c == null) {
                    closeAsync();
                    scheduleReconnect(30_000L);
                    return;
                }
                ch = c;
                requestsStarted = false;
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                boolean writing = false;
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    writing = g.writeDescriptor(d);
                }
                if (!writing) {
                    startRequests(g);
                } else {
                    h.removeCallbacks(startReqFallback);
                    h.postDelayed(startReqFallback, CCCD_FALLBACK_MS);
                }
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(() -> {
                if (g == gatt && running) startRequests(g);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue() == null ? null : c.getValue().clone();
            h.post(() -> {
                if (g == gatt && running && v != null) onChunk(v);
            });
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
        lastRequest = System.currentTimeMillis();
    }

    private void onChunk(byte[] chunk) {
        byte[] f = asm.feed(chunk);
        if (f == null) return;
        try {
            int t = JkBmsProtocol.frameType(f);
            if (t == 0x01) {
                cellCount = JkBmsProtocol.parseSettings(f, protocol).cellCount;
            } else if (t == 0x02) {
                protocol = JkBmsProtocol.detectProtocol(f, protocol);
                lastFrame = System.currentTimeMillis();
                listener.onCell(JkBmsProtocol.parseCellInfo(f, protocol, cellCount));
            } else if (t == 0x03) {
                listener.onDevice(JkBmsProtocol.parseDeviceInfo(f));
            }
        } catch (Exception e) { Log.w(TAG, "parse", e); }
    }
}
