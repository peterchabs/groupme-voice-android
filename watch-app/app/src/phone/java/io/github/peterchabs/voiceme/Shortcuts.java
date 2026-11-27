package io.github.peterchabs.voiceme;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

/**
 * Offers the "Send a GroupMe" shortcut to Google Assistant (via Google's shortcuts library),
 * so it shows up under Assistant > Shortcuts, where you can give it your own "Hey Google" phrase.
 */
final class Shortcuts {
    private Shortcuts() { }

    static void publish(Context c) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW).setClass(c, MainActivity.class);
            ShortcutInfoCompat talk = new ShortcutInfoCompat.Builder(c, "talk")
                    .setShortLabel(c.getString(R.string.shortcut_talk_short))
                    .setLongLabel(c.getString(R.string.shortcut_talk_long))
                    .setIcon(IconCompat.createWithResource(c, R.mipmap.ic_launcher))
                    .setIntent(open)
                    .addCapabilityBinding("actions.intent.OPEN_APP_FEATURE", "feature",
                            java.util.Arrays.asList(c.getResources().getStringArray(R.array.talk_names)))
                    .build();
            ShortcutManagerCompat.pushDynamicShortcut(c, talk);
        } catch (RuntimeException ignored) {
            // shortcuts are a convenience; never let them stop the app
        }
    }
}
