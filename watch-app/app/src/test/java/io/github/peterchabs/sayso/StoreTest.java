package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StoreTest {
    @Test public void aNewInstallIsLight() {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        assertEquals("light", Store.theme(c));
    }

    @Test public void signingOutKeepsTheLookButForgetsTheAccount() {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Store.setToken(c, "abc");
        Store.setTheme(c, "dark");
        Store.clear(c);
        assertEquals("", Store.token(c));
        assertEquals("dark", Store.theme(c)); // the sign-in screen stays dark
    }
}
