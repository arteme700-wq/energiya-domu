package ua.sister.energy;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Самооновлення з GitHub Releases. Реліз містить version.json {"versionCode","versionName","sha256"} і APK.
 * Встановлюється лише APK з тим самим сертифікатом підпису і правильним SHA-256.
 * Тихо — якщо додаток є власником пристрою; інакше Android покаже звичайний запит.
 */
public final class Updater {
    public static final String DEFAULT_URL = "https://api.github.com/repos/arteme700-wq/energiya-domu/releases/latest";
    private static final String TAG = "Updater";
    static final String ACTION = "ua.sister.energy.INSTALL_STATUS";

    private Updater() { }

    /** @return текст результату (для налаштувань / журналу). Виконувати не в UI-потоці. */
    public static synchronized String check(Context ctx) {
        try {
            String url = new Prefs(ctx).updateUrl();
            JSONObject rel = new JSONObject(new String(get(url, 1 << 20), "UTF-8"));
            JSONArray assets = rel.getJSONArray("assets");
            String apkUrl = null, verUrl = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String n = a.getString("name");
                if (n.endsWith(".apk")) apkUrl = a.getString("browser_download_url");
                if (n.equals("version.json")) verUrl = a.getString("browser_download_url");
            }
            if (apkUrl == null || verUrl == null) return "У релізі немає APK або version.json";
            JSONObject ver = new JSONObject(new String(get(verUrl, 64 << 10), "UTF-8"));
            int code = ver.getInt("versionCode");
            String name = ver.optString("versionName", String.valueOf(code));
            if (code <= BuildConfig.VERSION_CODE) return "Остання версія вже встановлена (" + BuildConfig.VERSION_NAME + ")";
            File apk = new File(ctx.getCacheDir(), "update.apk");
            byte[] data = get(apkUrl, 50 << 20);
            String sha = ver.optString("sha256", "");
            if (!sha.isEmpty() && !sha.equalsIgnoreCase(hex(MessageDigest.getInstance("SHA-256").digest(data))))
                return "SHA-256 не збігається — оновлення відхилено";
            try (OutputStream os = new FileOutputStream(apk)) { os.write(data); }
            String bad = verify(ctx, apk, code);
            if (bad != null) { apk.delete(); return bad; }
            install(ctx, apk, name);
            return "Встановлюю " + name + (isOwner(ctx) ? " (тихо)" : " (потрібне підтвердження)");
        } catch (Exception e) {
            Log.w(TAG, "check", e);
            return "Помилка перевірки: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static boolean isOwner(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
    }

    @SuppressWarnings("deprecation")
    private static String verify(Context ctx, File apk, int code) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo n = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (n == null) return "Файл не є коректним APK";
        if (!ctx.getPackageName().equals(n.packageName)) return "Чужий пакет у релізі";
        if (n.versionCode != code) return "versionCode у APK не збігається з version.json";
        PackageInfo me = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
        if (n.signatures == null || !sameSigs(me.signatures, n.signatures)) return "Інший підпис — оновлення відхилено";
        return null;
    }

    private static boolean sameSigs(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!Arrays.equals(a[i].toByteArray(), b[i].toByteArray())) return false;
        return true;
    }

    private static void install(Context ctx, File apk, String name) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[65536]; int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                s.fsync(out);
            }
            new Prefs(ctx).edit().putString("pending_update", name).commit();
            Intent i = new Intent(ctx, InstallReceiver.class).setAction(ACTION);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? 0x02000000 /*MUTABLE*/ : 0));
            s.commit(pend.getIntentSender());
        }
    }

    private static byte[] get(String url, int max) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "energiya-domu/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");
            int code = c.getResponseCode();
            if (code / 100 == 3) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[65536]; int r;
                while ((r = in.read(b)) > 0) { bo.write(b, 0, r); if (bo.size() > max) throw new IOException("too big"); }
                return bo.toByteArray();
            }
        }
        throw new IOException("too many redirects");
    }

    private static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x)); return sb.toString(); }

    /** Результат сесії встановлення. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -99);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(confirm); }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "install status " + st + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
