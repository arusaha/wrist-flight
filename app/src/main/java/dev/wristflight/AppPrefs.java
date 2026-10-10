package dev.wristflight;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

public final class AppPrefs {
    private final SharedPreferences prefs;
    public AppPrefs(Context context) { prefs = context.getSharedPreferences("wrist_flight", Context.MODE_PRIVATE); }
    public boolean enabled() { return prefs.getBoolean("enabled", false); }
    public boolean restorePending() { return prefs.getBoolean("restore_pending", false); }
    public boolean setEnabled(boolean value) { return prefs.edit().putBoolean("enabled", value).commit(); }
    public boolean setRestorePending(boolean value) { return prefs.edit().putBoolean("restore_pending", value).commit(); }
    public void status(String value) {
        if (value.equals(status())) return;
        String entry = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + "  " + value;
        String[] old = history().split("\n");
        StringBuilder log = new StringBuilder(entry);
        for (int i = 0; i < Math.min(old.length, 11); i++) {
            if (!old[i].isEmpty()) log.append('\n').append(old[i]);
        }
        prefs.edit().putString("status", value).putString("history", log.toString()).apply();
    }
    public String status() { return prefs.getString("status", "Complete setup to begin"); }
    public String history() { return prefs.getString("history", ""); }
}
