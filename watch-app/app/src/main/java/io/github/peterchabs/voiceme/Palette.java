package io.github.peterchabs.voiceme;

import android.content.Context;
import android.content.res.Configuration;

/**
 * The app's colors, light or dark. Every screen calls {@link #apply} first, then reads these.
 * The setting is Light (the default), Dark, or System (follow the device).
 */
final class Palette {
    private Palette() { }

    static final int BLUE = 0xFF00AFF0;

    static boolean dark;
    static int BG, SURFACE, CARD, INK, SUB, BUBBLE, PLACEHOLDER, AVATAR, BADGE, LIVE;
    /** Message pills and card rows: fills and hairline edges. Yours: a gentle blue gradient. */
    static int BUBBLE_EDGE, ROW, ROW_EDGE;
    static final int BLUE_TOP = 0xFF2CC4F9, BLUE_BOTTOM = 0xFF0397D8;
    static int BTN_TOP, BTN_BOTTOM, BTN_LIP, FIELD_TEXT, FIELD_HINT, FIELD_EDGE, FIELD_FILL_TOP, FIELD_FILL_MID;

    static final String LIGHT = "light", DARK = "dark", SYSTEM = "system";

    static void apply(Context c) {
        String t = Store.theme(c);
        boolean deviceDark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean watch = Stage.isWatchBuild();
        // the phone app only signs watches in and has no theme setting: it follows the phone
        dark = watch ? DARK.equals(t) || (SYSTEM.equals(t) && deviceDark) : deviceDark;
        if (dark) {
            BG = watch ? 0xFF000000 : 0xFF111417;   // true black on a watch: OLED pixels off
            SURFACE = watch ? 0xFF0B0F13 : 0xFF181C21;
            CARD = 0xFF1C2127;
            INK = 0xFFF1F4F7;
            SUB = 0xFFA3AEB9;
            BUBBLE = 0xFF252C34;
            BUBBLE_EDGE = 0xFF343D47;
            ROW = watch ? 0xFF11161B : 0xFF1A1F25;
            ROW_EDGE = 0xFF29313A;
            PLACEHOLDER = 0xFF1A1F25;
            AVATAR = 0xFF2B323A;
            BADGE = 0xFF0F2A38;
            LIVE = 0xFF4CC9F6;
            BTN_TOP = 0xFF26303A;
            BTN_BOTTOM = 0xFF1B232B;
            BTN_LIP = 0xFF0B5A7A;
            FIELD_TEXT = 0xFFF1F4F7;
            FIELD_HINT = 0xFF7D8894;
            FIELD_EDGE = 0xFF3A444F;
            FIELD_FILL_TOP = 0xFF151A1F;
            FIELD_FILL_MID = 0xFF1B2127;
        } else {
            BG = 0xFFFFFFFF;
            SURFACE = 0xFFF4FAFE;
            CARD = 0xFFFFFFFF;
            INK = 0xFF1B1F24;
            SUB = 0xFF46515C;
            BUBBLE = 0xFFEDF1F5;
            BUBBLE_EDGE = 0xFFD8E0E8;
            ROW = 0xFFF7FAFC;
            ROW_EDGE = 0xFFE4EBF1;
            PLACEHOLDER = 0xFFEAF0F5;
            AVATAR = 0xFFDDE6EE;
            BADGE = 0xFFEAF6FD;
            LIVE = 0xFF0A8FC4;
            BTN_TOP = 0xFFFFFFFF;
            BTN_BOTTOM = 0xFFEAF7FD;
            BTN_LIP = 0xFFAEE3F8;
            FIELD_TEXT = 0xFF262626;
            FIELD_HINT = 0xFF9AA7B0;
            FIELD_EDGE = 0xFFC5D3DC;
            FIELD_FILL_TOP = 0xFFE3EBF0;
            FIELD_FILL_MID = 0xFFF7FAFC;
        }
    }

    /** Light, then Dark, then System, then Light again. */
    static String next(String t) {
        return LIGHT.equals(t) ? DARK : DARK.equals(t) ? SYSTEM : LIGHT;
    }

    static String label(String t) {
        return DARK.equals(t) ? "Dark" : SYSTEM.equals(t) ? "System" : "Light";
    }
}
