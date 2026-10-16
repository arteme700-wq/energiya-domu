package ua.sister.energy;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private final SharedPreferences p;
    public Prefs(Context c) { p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }
    public String ip() { return p.getString("ip", ""); }
    public int port() { return p.getInt("port", 8899); }
    public String serial() { return p.getString("serial", ""); }
    public int slave() { return p.getInt("slave", 1); }
    public String bmsMac() { return p.getString("bms_mac", ""); }
    public String bmsName() { return p.getString("bms_name", ""); }
    public String updateUrl() { String u = p.getString("update_url", ""); return u.isEmpty() ? Updater.DEFAULT_URL : u; }
    public boolean demo() { return p.getBoolean("demo", true); }
    public double capAh() { return p.getFloat("cap_ah", 420f); }
    public double ratedW() { return p.getFloat("rated", 8000f); }
    public SharedPreferences.Editor edit() { return p.edit(); }
}
