package io.github.peterchabs.groupmevoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignInTest {
    @Test public void readsTheTokenFromGroupMesPage() {
        // evaluateJavascript hands back the JSON as a quoted, escaped string
        assertEquals("abcDEF1234567890abcDEF1234567890",
                DevSignInActivity.tokenFrom("\"{\\\"t\\\":\\\"abcDEF1234567890abcDEF1234567890\\\",\\\"f\\\":false}\""));
        assertEquals("abcDEF1234567890abcDEF12",
                DevSignInActivity.tokenFrom("{\"t\":\"abcDEF1234567890abcDEF12\",\"f\":false}"));
    }

    @Test public void ignoresAnythingThatIsntAToken() {
        assertNull(DevSignInActivity.tokenFrom(null));
        assertNull(DevSignInActivity.tokenFrom("null"));
        assertNull(DevSignInActivity.tokenFrom("\"{\\\"t\\\":\\\"\\\",\\\"f\\\":true}\""));   // login page
        assertNull(DevSignInActivity.tokenFrom("{\"t\":\"short\",\"f\":false}"));
        assertNull(DevSignInActivity.tokenFrom("{\"t\":\"has space in it 1234567890\",\"f\":false}"));
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
