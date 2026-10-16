package ua.sister.energy.notify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Налаштування і останні повідомлення. */
final class Store {
    static final String SERVER = "https://ntfy.sh";
    private final SharedPreferences p;
    Store(Context c) { p = c.getSharedPreferences("st", Context.MODE_PRIVATE); }

    String topic() { return p.getString("topic", ""); }
    void setTopic(String t) { p.edit().putString("topic", t).remove("last_id").apply(); }
    String lastId() { return p.getString("last_id", ""); }
    void setLastId(String id) { p.edit().putString("last_id", id).apply(); }
    String status() { return p.getString("status", "—"); }
    void setStatus(String s) { p.edit().putString("status", s).apply(); }

    /** Приймає QR-вміст (https://ntfy.sh/тема) або сам код. @return тема або null. */
    static String parseTopic(String s) {
        if (s == null) return null;
        s = s.trim();
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int sl = s.lastIndexOf('/'); if (sl >= 0) s = s.substring(sl + 1);
        return s.matches("[A-Za-z0-9_-]{8,64}") ? s : null;
    }

    synchronized void addMessage(JSONObject m) {
        try {
            JSONArray a = new JSONArray(p.getString("msgs", "[]"));
            JSONArray b = new JSONArray(); b.put(m);
            for (int i = 0; i < a.length() && b.length() < 30; i++) b.put(a.get(i));
            p.edit().putString("msgs", b.toString()).apply();
        } catch (Exception ignored) { }
    }

    JSONArray messages() { try { return new JSONArray(p.getString("msgs", "[]")); } catch (Exception e) { return new JSONArray(); } }
}
