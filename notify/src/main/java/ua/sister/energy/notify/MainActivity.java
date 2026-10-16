package ua.sister.energy.notify;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    static final String ACTION_NEW = "ua.sister.energy.notify.NEW";
    private Store st;
    private TextView status, topic;
    private LinearLayout list;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new Store(this);
        ListenService.channels(this);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40, 40, 40, 40);
        l.setBackgroundColor(0xFF0B1220);
        l.addView(Ui.text(this, "Енергія дому", 34, 0xFFFFFFFF, true));
        status = Ui.text(this, "", 24, 0xFF34D399, true); l.addView(status);
        topic = Ui.text(this, "", 18, 0xFFCBD5E1, false); l.addView(topic);
        add(l, Ui.button(this, "📷  Сканувати QR з планшета", 0xFF059669)).setOnClickListener(v -> scan());
        add(l, Ui.button(this, "⌨  Ввести код вручну", 0xFF334155)).setOnClickListener(v -> manual());
        add(l, Ui.button(this, "⚙  Налаштування телефона", 0xFF334155)).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        l.addView(Ui.text(this, "Останні події", 28, 0xFFFFFFFF, true));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); l.addView(list);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFF0B1220); sv.addView(l);
        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        ListenService.start(this);
        new Thread(() -> { String r = Updater.check(this, false); android.util.Log.i("Updater", r); }).start();
    }

    private TextView add(LinearLayout l, TextView v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 18, 0, 0); l.addView(v, lp); return v;
    }

    private final Runnable refresh = new Runnable() { @Override public void run() { render(); h.postDelayed(this, 5000); } };
    private final BroadcastReceiver onNew = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { render(); } };

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onNew, new IntentFilter(ACTION_NEW), 4 /*RECEIVER_NOT_EXPORTED*/);
        else registerReceiver(onNew, new IntentFilter(ACTION_NEW));
        h.post(refresh);
    }

    @Override protected void onPause() { super.onPause(); unregisterReceiver(onNew); h.removeCallbacks(refresh); }

    private void render() {
        boolean paired = !st.topic().isEmpty();
        status.setText(paired ? st.status() : "Не прив'язано до планшета");
        status.setTextColor(!paired ? 0xFFFBBF24 : st.status().startsWith("Підключено") ? 0xFF34D399 : 0xFFF87171);
        topic.setText(paired ? "Код: " + st.topic() : "Відскануйте QR у налаштуваннях планшета (⚙ → Сповіщення).");
        list.removeAllViews();
        JSONArray a = st.messages();
        if (a.length() == 0) list.addView(Ui.text(this, "Подій поки немає", 22, 0xFF94A3B8, false));
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i); if (m == null) continue;
            String when = new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(m.optLong("t") * 1000));
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(24, 18, 24, 18);
            card.setBackgroundColor(0xFF111A2E);
            card.addView(Ui.text(this, m.optString("title"), 26, 0xFFFFFFFF, true));
            card.addView(Ui.text(this, m.optString("msg"), 22, 0xFFE2E8F0, false));
            card.addView(Ui.text(this, when, 18, 0xFF94A3B8, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 14, 0, 0);
            list.addView(card, lp);
        }
    }

    private void scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != 0) { requestPermissions(new String[]{Manifest.permission.CAMERA}, 1); return; }
        startActivityForResult(new Intent(this, ScanActivity.class), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == 1 && r.length > 0 && r[0] == 0) scan();
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        if (rc == 1 && res == RESULT_OK && d != null) pair(d.getStringExtra("text"));
    }

    private void manual() {
        EditText e = new EditText(this); e.setTextSize(22); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setHint("energiya-…"); e.setText(st.topic());
        new AlertDialog.Builder(this).setTitle("Код з планшета").setView(e)
                .setPositiveButton("Зберегти", (d, w) -> pair(e.getText().toString())).setNegativeButton("Скасувати", null).show();
    }

    private void pair(String s) {
        String t = Store.parseTopic(s);
        if (t == null) { Toast.makeText(this, "Невірний код", Toast.LENGTH_LONG).show(); return; }
        st.setTopic(t); st.setStatus("Підключаюсь…");
        ListenService.restart(this);
        Toast.makeText(this, "Прив'язано", Toast.LENGTH_LONG).show();
        render();
    }
}
