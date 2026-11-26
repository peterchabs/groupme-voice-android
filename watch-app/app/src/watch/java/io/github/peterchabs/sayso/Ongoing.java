package io.github.peterchabs.sayso;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;

import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;

/**
 * The notice Android requires while Say So keeps its live GroupMe connection open. On a watch it's
 * also an Ongoing Activity, as Wear OS asks of anything that keeps running: a small Say So icon on
 * the watch face and an entry in Recents, either of which opens Say So in one tap.
 */
final class Ongoing {
    private Ongoing() { }

    static Notification running(Context c, String channel, int id, int icon, String text, PendingIntent open, String group) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(icon)
                .setContentText(text)
                .setOngoing(true)
                .setGroup(group)
                .setSortKey("z")
                .setLocalOnly(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open);
        new OngoingActivity.Builder(c, id, b)
                .setStaticIcon(icon)
                .setTouchIntent(open)
                .setStatus(new Status.Builder().addTemplate(text).build())
                .build()
                .apply(c);
        return b.build();
    }
}
