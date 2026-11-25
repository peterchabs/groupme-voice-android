package io.github.peterchabs.sayso;

import android.content.Context;

import androidx.wear.phone.interactions.notifications.BridgingConfig;
import androidx.wear.phone.interactions.notifications.BridgingManager;

/**
 * Wear OS copies the phone app's notifications to the watch. While the watch app shows its own,
 * those copies are switched off, so each message shows once; with the watch's off, they come back.
 */
final class Bridge {
    private Bridge() { }

    static void update(Context any) {
        Context c = any.getApplicationContext(); // a BroadcastReceiver's own context can't bind to the bridging service
        try {
            boolean watchShowsItsOwn = Store.pushOn(c) && !Store.token(c).isEmpty();
            BridgingManager.fromContext(c).setConfig(
                    new BridgingConfig.Builder(c, !watchShowsItsOwn).build());
        } catch (RuntimeException e) {
            android.util.Log.w("SaySo", "couldn't set notification bridging (" + e + ")");
        }
    }
}
