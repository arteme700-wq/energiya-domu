package ua.sister.energy.notify;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
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
    private View clearAll, clearHint;
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
        clearAll = add(l, Ui.outline(this, "🗑  Очистити все", 0xFF3B0D0D, 0xFFF87171, 0xFFFECACA));
        clearAll.setOnClickListener(v -> confirmClearAll());
        clearHint = Ui.text(this, "Утримуйте подію, щоб видалити тільки її", 18, 0xFF94A3B8, false); l.addView(clearHint);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); l.addView(list);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFF0B1220); sv.addView(l);
        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        ListenService.start(this);
        new Thread(() -> { String r = Updater.check(this, false); android.util.Log.i("Updater", r); }).start();
        handleLink(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleLink(i); }

    /** Посилання energiya://pair?topic=… зі сторінки підключення. Будь-який сайт може відкрити таке посилання, тому завжди питаємо. */
    private void handleLink(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        android.net.Uri u = i.getData();
        i.setData(null);
        String t = "pair".equals(u.getHost()) ? Store.parseTopic(u.toString()) : null;
        if (t == null) { Toast.makeText(this, "Невірне посилання підключення", Toast.LENGTH_LONG).show(); return; }
        if (t.equals(st.topic())) { Toast.makeText(this, "Цей планшет уже підключено", Toast.LENGTH_LONG).show(); return; }
        TextView msg = Ui.text(this, "Отримувати сповіщення з планшета «Енергія дому»?\n\nКод: " + t, 22, 0xFFE2E8F0, false);
        msg.setPadding(48, 32, 48, 0);
        new AlertDialog.Builder(this).setTitle("Підключити планшет").setView(msg)
                .setPositiveButton("Підключити", (d, w) -> pair(t)).setNegativeButton("Скасувати", null).show();
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
        clearAll.setVisibility(a.length() == 0 ? View.GONE : View.VISIBLE);
        clearHint.setVisibility(a.length() == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i); if (m == null) continue;
            String when = new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(m.optLong("t") * 1000));
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(24, 18, 24, 18);
            card.setBackgroundColor(0xFF111A2E);
            card.addView(Ui.text(this, m.optString("title"), 26, 0xFFFFFFFF, true));
            card.addView(Ui.text(this, m.optString("msg"), 22, 0xFFE2E8F0, false));
            card.addView(Ui.text(this, when, 18, 0xFF94A3B8, false));
            final long t = m.optLong("t"); final String title = m.optString("title"), msg = m.optString("msg");
            card.setOnLongClickListener(v -> { askDeleteOne(card, t, title, msg, when); return true; });
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

    // ---- Видалення історії (1.3.1) ----

    /** Діалог без системної рамки: свій темний фон, великі кнопки. Поки відкритий — список не оновлюється. */
    private Dialog sheet(View content, boolean bottom) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(bottom ? Gravity.BOTTOM : Gravity.CENTER);
            w.setDimAmount(0.72f);
        }
        h.removeCallbacks(refresh);
        d.setOnDismissListener(x -> { render(); h.removeCallbacks(refresh); h.postDelayed(refresh, 5000); });
        return d;
    }

    private Button bigButton(LinearLayout parent, String s, int bg, float weight) {
        Button b = Ui.button(this, s, bg);
        LinearLayout.LayoutParams lp = weight > 0 ? new LinearLayout.LayoutParams(0, -2, weight) : new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(weight > 0 ? 8 : 0, 16, weight > 0 ? 8 : 0, 0);
        parent.addView(b, lp);
        return b;
    }

    private void confirmClearAll() {
        int n = st.messages().length();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(44, 40, 44, 40);
        box.setBackground(Ui.panel(0xFF1E293B, 0xFF475569, 40));
        box.addView(Ui.text(this, "Видалити всі події?", 26, 0xFFFFFFFF, true));
        box.addView(Ui.text(this, "З телефона зникнуть усі " + n + " " + events(n) + " зі списку. Повернути їх не можна.\n\nНові сповіщення надходитимуть, як і раніше.", 20, 0xFFE2E8F0, false));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); box.addView(row);
        FrameWrap fw = new FrameWrap(box, 36);
        Dialog d = sheet(fw.view, false);
        bigButton(row, "Скасувати", 0xFF334155, 1).setOnClickListener(v -> d.dismiss());
        bigButton(row, "Видалити все", 0xFFB91C1C, 1).setOnClickListener(v -> {
            st.clearMessages(); d.dismiss();
            Toast.makeText(this, "Історію очищено", Toast.LENGTH_SHORT).show();
        });
        d.show();
    }

    private void askDeleteOne(View card, long t, String title, String msg, String when) {
        card.setBackground(Ui.panel(0xFF1E2A44, 0xFFFBBF24, 8));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(44, 40, 44, 56);
        android.graphics.drawable.GradientDrawable g = Ui.panel(0xFF1E293B, 0xFF475569, 0);
        g.setCornerRadii(new float[]{48, 48, 48, 48, 0, 0, 0, 0}); box.setBackground(g);
        box.addView(Ui.text(this, title, 24, 0xFFFFFFFF, true));
        box.addView(Ui.text(this, when, 18, 0xFF94A3B8, false));
        Dialog d = sheet(box, true);
        if (d.getWindow() != null) d.getWindow().setDimAmount(0.55f);
        bigButton(box, "🗑  Видалити цю подію", 0xFFB91C1C, 0).setOnClickListener(v -> {
            boolean ok = st.removeMessage(t, title, msg); d.dismiss();
            Toast.makeText(this, ok ? "Подію видалено" : "Подію вже видалено", Toast.LENGTH_SHORT).show();
        });
        bigButton(box, "Скасувати", 0xFF334155, 0).setOnClickListener(v -> d.dismiss());
        d.show();
    }

    /** 1 подія, 2–4 події, 5+ подій (українські форми множини). */
    static String events(int n) {
        int a = n % 100, b = n % 10;
        if (a >= 11 && a <= 14) return "подій";
        if (b == 1) return "подію";
        if (b >= 2 && b <= 4) return "події";
        return "подій";
    }

    /** Відступи навколо центрального діалогу, щоб він не прилипав до країв екрана. */
    private final class FrameWrap {
        final View view;
        FrameWrap(View inner, int margin) {
            android.widget.FrameLayout f = new android.widget.FrameLayout(MainActivity.this);
            f.setPadding(margin, margin, margin, margin);
            f.addView(inner, new android.widget.FrameLayout.LayoutParams(-1, -2));
            view = f;
        }
    }
}
