package io.github.peterchabs.sayso;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

/** The notice Android requires while Say So keeps its live GroupMe connection open (phone: a plain one). */
final class Ongoing {
    private Ongoing() { }

    static Notification running(Context c, String channel, int id, int icon, String text, PendingIntent open, String group) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c))
                .setSmallIcon(icon)
                .setContentText(text)
                .setOngoing(true)
                .setGroup(group)
                .setSortKey("z")
                .setLocalOnly(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED);
        return b.build();
    }
}
