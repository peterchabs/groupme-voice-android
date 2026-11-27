package io.github.peterchabs.voiceme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignInTest {
    @Test public void takesGroupMeWebsSignInFromItsCookies() {
        assertEquals("abcDEF1234567890abcDEF1234567890",
                GroupMeSignInActivity.tokenFrom("token=abcDEF1234567890abcDEF1234567890"));
        assertEquals("abcDEF1234567890abcDEF12",
                GroupMeSignInActivity.tokenFrom("_ga=GA1.2.3; token=abcDEF1234567890abcDEF12; theme=dark"));
    }

    @Test public void takesGroupMeWebsSignInFromItsStorage() {
        assertEquals("abcDEF1234567890abcDEF1234567890", GroupMeSignInActivity.fromStorage("\"abcDEF1234567890abcDEF1234567890\""));
        assertNull(GroupMeSignInActivity.fromStorage("\"\""));
        assertNull(GroupMeSignInActivity.fromStorage("null"));
        assertNull(GroupMeSignInActivity.fromStorage(null));
    }

    @Test public void notSignedInYetMeansNoToken() {
        assertNull(GroupMeSignInActivity.tokenFrom(null));
        assertNull(GroupMeSignInActivity.tokenFrom(""));
        assertNull(GroupMeSignInActivity.tokenFrom("_ga=GA1.2.3; theme=dark"));         // the login page
        assertNull(GroupMeSignInActivity.tokenFrom("token="));                           // cleared
        assertNull(GroupMeSignInActivity.tokenFrom("token=short"));
        assertNull(GroupMeSignInActivity.tokenFrom("csrftoken=abcDEF1234567890abcDEF1234567890")); // a different cookie
    }

    @Test public void unsentMessageOnlyComesBackForTheSamePerson() {
        MainActivity.Unsent u = new MainActivity.Unsent("To Family", "“hi”", "Sending", "Sent", "111", t -> { });
        assertTrue(u.freshFor("111"));
        assertFalse(u.freshFor("222"));   // someone else signed in
        assertFalse(u.freshFor(""));
        MainActivity.Unsent nobody = new MainActivity.Unsent("To Family", "x", "Sending", "Sent", "", t -> { });
        assertFalse(nobody.freshFor(""));
    }
}
