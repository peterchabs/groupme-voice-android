package io.github.peterchabs.sayso;

import android.app.Activity;
import android.content.Intent;

import java.io.IOException;

/**
 * Everyone signs in with GroupMe's own sign-in (the phone app). Sign-ins from GroupMe's developer
 * site ("gm-dev") can't send direct messages anymore, so one of those is signed out, once, and the
 * sign-in screen explains why. Each sign-in is checked once; a good one is never asked about again.
 */
final class SignInCheck {
    private SignInCheck() { }

    static final String OLD_KIND = "gm-dev";

    /** A short fingerprint of the sign-in, so the token itself is never stored twice. */
    static String key(String token) {
        return Integer.toHexString(token.hashCode()) + ":" + token.length();
    }

    static void run(Activity a) {
        final String token = Store.token(a);
        if (token.isEmpty() || key(token).equals(Store.checkedSignIn(a))) return;
        new Thread(() -> {
            String app;
            try {
                app = Api.tokenApp(token);
            } catch (IOException e) {
                return; // offline or GroupMe busy: check again next time
            }
            if (app == null) return;
            if (!OLD_KIND.equals(app)) {
                Store.setCheckedSignIn(a, key(token));
                return;
            }
            a.runOnUiThread(() -> {
                if (!token.equals(Store.token(a))) return; // signed in again meanwhile
                Store.forgetSignIn(a);
                Store.setSignInUpgrade(a, true);
                PushService.sync(a);
                a.startActivity(new Intent(a, SetupActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                a.finish();
            });
        }, "sayso-signin-check").start();
    }
}
