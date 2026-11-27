package io.github.peterchabs.voiceme;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart (or an app update), notifications come back on if you had them on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        PushService.sync(c);
    }
}
