package ua.sister.energy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Intent a = new Intent(c, MainActivity.class);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(a);
    }
}
