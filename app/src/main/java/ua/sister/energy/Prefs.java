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
    // інформаційна сторінка (типові значення — Княжичі, Броварський р-н, черга 1.2)
    public double lat() { return p.getFloat("lat", 50.47f); }
    public double lon() { return p.getFloat("lon", 30.78f); }
    public String queue() { return p.getString("queue", "1.2"); }
    public int alertRegion() { return p.getInt("alert_region", 79); }
    public int alertFallback() { return p.getInt("alert_fallback", 14); }
    public double pvKwp() { return p.getFloat("pv_kwp", 5f); }
    public int pvTilt() { return p.getInt("pv_tilt", 30); }
    public int pvAzimuth() { return p.getInt("pv_azimuth", 0); }
    public double pvEff() { return p.getFloat("pv_eff", 0.8f); }
    public boolean alertSound() { return p.getBoolean("alert_sound", true); }
    public boolean outageSound() { return p.getBoolean("outage_sound", true); }
    public boolean outageSoundNight() { return p.getBoolean("outage_sound_night", false); }
    /** Нічний режим: "sun" (схід/захід, типово), "fixed" (за розкладом) або "off". */
    public String nightMode() { return p.getString("night_mode", "sun"); }
    public String nightFrom() { return p.getString("night_from", "22:00"); }
    public String nightTo() { return p.getString("night_to", "07:00"); }
    public SharedPreferences.Editor edit() { return p.edit(); }
}
