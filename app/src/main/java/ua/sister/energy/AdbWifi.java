package ua.sister.energy;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Wi‑Fi ADB (adb over TCP). Без root — лише статус + налагодження USB (якщо власник пристрою) + відкрити «Для розробників». */
public final class AdbWifi {
    private static final String TAG = "AdbWifi";
    public static final int PORT = 5555;
    private AdbWifi() { }

    /** Чи слухає хтось TCP-порт (з /proc/net/tcp*, без root; запасний варіант — підключення до 127.0.0.1). */
    public static boolean listening(int port) {
        String hex = String.format("%04X", port);
        for (String f : new String[]{"/proc/net/tcp6", "/proc/net/tcp"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l; r.readLine();
                while ((l = r.readLine()) != null) {
                    String[] p = l.trim().split("\\s+");
                    if (p.length > 3 && p[1].endsWith(":" + hex) && p[3].equals("0A")) return true;
                }
            } catch (Exception ignored) { }
        }
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 400); return true; } catch (Exception e) { return false; }
    }

    static String prop(String k) {
        try { Class<?> c = Class.forName("android.os.SystemProperties"); return (String) c.getMethod("get", String.class).invoke(null, k); }
        catch (Exception e) { return ""; }
    }

    static boolean hasSu() {
        for (String d : new String[]{"/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/magisk/.core/bin/su", "/data/adb/magisk/su"})
            if (new File(d).exists()) return true;
        return false;
    }

    static boolean usbDebug(Context c) {
        try { return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; } catch (Exception e) { return false; }
    }

    public static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            boolean on = listening(PORT);
            o.put("on", on); o.put("port", PORT);
            o.put("ip", LanServer.lanIp());
            o.put("prop_port", prop("service.adb.tcp.port"));
            o.put("persist_port", prop("persist.adb.tcp.port"));
            o.put("adbd", prop("init.svc.adbd"));
            o.put("root", hasSu());
            o.put("usb_debug", c != null && usbDebug(c));
            o.put("owner", c != null && Updater.isOwner(c));
            o.put("android", android.os.Build.VERSION.RELEASE);
            o.put("text", on ? "Wi‑Fi ADB увімкнено, порт " + PORT : "Wi‑Fi ADB вимкнено");
        } catch (Exception ignored) { }
        return o;
    }

    private static int su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            long end = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < end) { try { return p.exitValue(); } catch (IllegalThreadStateException e) { Thread.sleep(200); } }
            p.destroy(); return -2;
        } catch (Exception e) { Log.w(TAG, "su", e); return -1; }
    }

    private static boolean waitState(boolean want, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (listening(PORT) == want) return true; try { Thread.sleep(500); } catch (InterruptedException e) { return false; } }
        return listening(PORT) == want;
    }

    /** Спроба ввімкнути. Повертає JSON {ok, on, steps, text}. */
    public static JSONObject enable(Context c) {
        StringBuilder steps = new StringBuilder(); boolean ok = false; String text;
        if (listening(PORT)) { ok = true; text = "Wi‑Fi ADB уже увімкнено, порт " + PORT + "."; }
        else {
            // 1) налагодження USB має бути ввімкнене — власник пристрою може це зробити сам
            if (c != null && !usbDebug(c) && Updater.isOwner(c)) {
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    dpm.setGlobalSetting(new ComponentName(c, AdminReceiver.class), Settings.Global.ADB_ENABLED, "1");
                    steps.append("Налагодження USB увімкнено (власник пристрою). ");
                } catch (Exception e) { steps.append("Налагодження USB: помилка. "); }
            }
            // 2) root
            if (hasSu()) {
                int r = su("setprop service.adb.tcp.port " + PORT + "; setprop persist.adb.tcp.port " + PORT + "; stop adbd; start adbd");
                steps.append("root: код ").append(r).append(". ");
                ok = waitState(true, 8000);
            } else steps.append("root немає. ");
            if (ok) text = "Готово: Wi‑Fi ADB увімкнено, порт " + PORT + ". Підключення: adb connect " + LanServer.lanIp() + ":" + PORT;
            else {
                openDev(c);
                text = "Без root Android 7 не дозволяє додатку ввімкнути Wi‑Fi ADB. На планшеті відкрито «Для розробників». "
                        + "Потрібно один раз підключити USB-кабель до комп’ютера і виконати: adb tcpip " + PORT
                        + " — після кожного перезавантаження планшета це треба повторити (або root/Magisk).";
            }
        }
        JSONObject o = status(c);
        try { o.put("ok", ok); o.put("steps", steps.toString().trim()); o.put("text", text); } catch (Exception ignored) { }
        return o;
    }

    public static JSONObject disable(Context c) {
        boolean ok = false; String text;
        if (!listening(PORT)) { ok = true; text = "Wi‑Fi ADB уже вимкнено."; }
        else if (hasSu()) {
            su("setprop service.adb.tcp.port -1; setprop persist.adb.tcp.port ''; stop adbd; start adbd");
            ok = waitState(false, 8000);
            text = ok ? "Wi‑Fi ADB вимкнено." : "Не вдалося вимкнути.";
        } else text = "Без root вимкнути з додатка не можна. Вимкнеться саме після перезавантаження планшета, або: adb usb.";
        JSONObject o = status(c);
        try { o.put("ok", ok); o.put("text", text); } catch (Exception ignored) { }
        return o;
    }

    static void openDev(Context c) {
        if (c == null) return;
        try { c.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { try { c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
    }
}
