package ua.sister.energy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@SuppressLint("MissingPermission")
public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private EditText ip, port, serial, slave, rated, cap, upd;
    private CheckBox demo, ntfyOn;
    private EditText socMin;
    private TextView bmsSel, status;
    private String bmsMac, bmsName;
    private final LinkedHashMap<String, String> found = new LinkedHashMap<>();
    private ArrayAdapter<String> adapter;
    private final List<String> macs = new ArrayList<>();
    private BluetoothLeScanner scanner;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        bmsMac = prefs.bmsMac(); bmsName = prefs.bmsName();
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(32, 24, 32, 24);
        title(l, "Налаштування");
        demo = new CheckBox(this); demo.setText("Демо-режим (імітація даних, без обладнання)"); demo.setTextSize(18);
        demo.setChecked(prefs.demo()); l.addView(demo);
        title(l, "Інвертор Deye (Wi-Fi логер Solarman, лише читання)");
        ip = field(l, "IP-адреса логера, напр. 192.168.1.50", prefs.ip(), InputType.TYPE_CLASS_TEXT);
        serial = field(l, "Серійний номер логера (10 цифр, з наліпки)", prefs.serial(), InputType.TYPE_CLASS_NUMBER);
        port = field(l, "Порт (зазвичай 8899)", String.valueOf(prefs.port()), InputType.TYPE_CLASS_NUMBER);
        slave = field(l, "Modbus-адреса інвертора (зазвичай 1)", String.valueOf(prefs.slave()), InputType.TYPE_CLASS_NUMBER);
        rated = field(l, "Номінальна потужність, Вт", String.valueOf((int) prefs.ratedW()), InputType.TYPE_CLASS_NUMBER);
        Button test = new Button(this); test.setText("Перевірити зв'язок з логером"); l.addView(test);
        test.setOnClickListener(v -> testLogger());
        cap = field(l, "Ємність АКБ, А·год (для «Вистачить / До повного»)", String.valueOf((int) prefs.capAh()), InputType.TYPE_CLASS_NUMBER);
        title(l, "JK BMS (Bluetooth, лише читання)");
        bmsSel = new TextView(this); bmsSel.setTextSize(17); l.addView(bmsSel); showBms();
        Button scan = new Button(this); scan.setText("Шукати BMS"); l.addView(scan);
        scan.setOnClickListener(v -> startScan());
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        ListView lv = new ListView(this); lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> { bmsMac = macs.get(pos); bmsName = found.get(bmsMac); showBms(); });
        l.addView(lv, new LinearLayout.LayoutParams(-1, 360));
        title(l, "Сповіщення на телефон (ntfy.sh)");
        final Notifier nf = new Notifier(this);
        ntfyOn = new CheckBox(this); ntfyOn.setText("Надсилати сповіщення"); ntfyOn.setTextSize(18); ntfyOn.setChecked(nf.enabled()); l.addView(ntfyOn);
        socMin = field(l, "Поріг низького заряду АКБ, %", String.valueOf(nf.socThreshold()), InputType.TYPE_CLASS_NUMBER);
        TextView tp = new TextView(this); tp.setTextSize(18); tp.setText("Код для телефона:\n" + nf.topic()); tp.setTextIsSelectable(true); l.addView(tp);
        TextView hint = new TextView(this); hint.setTextSize(15); hint.setText("Відскануйте QR у застосунку «Енергія — сповіщення» на телефоні або введіть код вручну."); l.addView(hint);
        QrView qr = new QrView(this); qr.setText(Notifier.pairUrl(nf.topic()));
        l.addView(qr, new LinearLayout.LayoutParams(420, 420));
        Button tst = new Button(this); tst.setText("Надіслати тестове сповіщення"); l.addView(tst);
        tst.setOnClickListener(v -> { status.setText("Надсилаю…"); new Thread(() -> {
            String r;
            try { Notifier.post(nf.topic(), new org.json.JSONObject().put("title", "🔔 Тест: Енергія дому").put("message", "Сповіщення працюють. Цей планшет надсилатиме події про світло та АКБ.").put("priority", 4)); r = "Тестове сповіщення надіслано"; }
            catch (Exception e) { r = "Не вдалося: " + e.getMessage(); }
            final String rr = r; h.post(() -> status.setText(rr)); }).start(); });
        Button rg = new Button(this); rg.setText("Створити новий код (старі телефони перестануть отримувати)"); l.addView(rg);
        rg.setOnLongClickListener(v -> { nf.regenerate(); recreate(); return true; });
        rg.setOnClickListener(v -> status.setText("Утримуйте кнопку, щоб змінити код"));
        title(l, "Оновлення (версія " + BuildConfig.VERSION_NAME + (Updater.isOwner(this) ? ", тихе встановлення" : "") + ")");
        upd = field(l, "Адреса перевірки (порожньо = GitHub)", prefs.updateUrl().equals(Updater.DEFAULT_URL) ? "" : prefs.updateUrl(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        TextView last = new TextView(this); last.setTextSize(16);
        last.setText("Остання перевірка: " + getSharedPreferences("cfg", MODE_PRIVATE).getString("upd_last", "ще не було"));
        l.addView(last);
        Button chk = new Button(this); chk.setText("Перевірити оновлення зараз"); l.addView(chk);
        chk.setOnClickListener(v -> { save(); status.setText("Перевіряю оновлення…"); new Thread(() -> { String r = Updater.check(this); h.post(() -> status.setText(r)); }).start(); });
        status = new TextView(this); status.setTextSize(16); l.addView(status);
        Button save = new Button(this); save.setText("Зберегти і повернутись"); l.addView(save);
        save.setOnClickListener(v -> { save(); finish(); });
        TextView note = new TextView(this); note.setTextSize(14);
        note.setText("Відкрити налаштування: значок ⚙ або довге натискання на «Енергія».\nДодаток лише читає дані: інвертор (Modbus 0x03) і BMS (запити стану). Жодних команд керування.");
        l.addView(note);
        ScrollView sv = new ScrollView(this); sv.addView(l);
        setContentView(sv);
    }

    private void showBms() { bmsSel.setText("Вибрано: " + (bmsMac.isEmpty() ? "нічого" : bmsName + "  " + bmsMac)); }

    private void title(LinearLayout l, String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(21); t.setPadding(0, 24, 0, 8);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); l.addView(t);
    }

    private EditText field(LinearLayout l, String hint, String val, int type) {
        TextView t = new TextView(this); t.setText(hint); t.setTextSize(15); l.addView(t);
        EditText e = new EditText(this); e.setText(val); e.setInputType(type); e.setTextSize(19); e.setSingleLine(true);
        l.addView(e); return e;
    }

    private int num(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; } }

    private void save() {
        prefs.edit().putString("ip", ip.getText().toString().trim()).putString("serial", serial.getText().toString().trim())
                .putInt("port", num(port, 8899)).putInt("slave", num(slave, 1)).putFloat("rated", num(rated, 8000)).putFloat("cap_ah", num(cap, 420))
                .putString("update_url", upd.getText().toString().trim()).putBoolean("demo", demo.isChecked()).putString("bms_mac", bmsMac).putString("bms_name", bmsName == null ? "" : bmsName)
                .apply();
        new Notifier(this).edit().putBoolean("enabled", ntfyOn.isChecked()).putInt("soc_min", Math.max(5, Math.min(90, num(socMin, 20)))).apply();
    }

    private void testLogger() {
        status.setText("Перевіряю…");
        final String host = ip.getText().toString().trim(); final String ser = serial.getText().toString().trim();
        final int p = num(port, 8899), sl = num(slave, 1);
        new Thread(() -> {
            String msg;
            try {
                Snapshot s = new DeyeReader(new SolarmanV5(host, p, Long.parseLong(ser), sl)).read();
                msg = String.format(Locale.US, "OK: СЕС %.0f Вт, дім %.0f Вт, мережа %.0f Вт, %.1f В, SOC %.0f%%",
                        s.pv(), s.load, s.grid, s.gridV, s.invSoc);
            } catch (Exception e) { msg = "Помилка: " + e.getMessage(); }
            final String m = msg;
            h.post(() -> status.setText(m));
        }).start();
    }

    private void startScan() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { need.add(Manifest.permission.BLUETOOTH_SCAN); need.add(Manifest.permission.BLUETOOTH_CONNECT); }
        else need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        List<String> miss = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) { requestPermissions(miss.toArray(new String[0]), 1); return; }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { status.setText("Bluetooth недоступний"); return; }
        if (!ad.isEnabled()) { status.setText("Увімкніть Bluetooth (а на Android 7 — ще й геолокацію)"); return; }
        scanner = ad.getBluetoothLeScanner();
        found.clear(); macs.clear(); adapter.clear();
        status.setText("Пошук 15 с…");
        scanner.startScan(cb);
        h.postDelayed(() -> { try { scanner.stopScan(cb); } catch (Exception ignored) { } status.setText("Пошук завершено. Торкніться BMS у списку."); }, 15000);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        boolean ok = r.length > 0; for (int x : r) ok &= x == PackageManager.PERMISSION_GRANTED;
        if (ok) startScan(); else status.setText("Без дозволу пошук неможливий");
    }

    private final ScanCallback cb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String mac = r.getDevice().getAddress();
            String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            if (name == null) name = r.getDevice().getName();
            if (name == null || found.containsKey(mac)) return;
            found.put(mac, name); macs.add(mac);
            boolean jk = name.toUpperCase(Locale.US).startsWith("JK");
            adapter.add((jk ? "★ " : "") + name + "   " + mac + "   " + r.getRssi() + " dBm");
        }
    };

    @Override protected void onDestroy() { super.onDestroy(); try { if (scanner != null) scanner.stopScan(cb); } catch (Exception ignored) { } }
}
