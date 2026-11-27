package io.github.peterchabs.voiceme;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Phone-to-watch sign-in. The watch shows a short code. The web page (docs/index.html)
 * encrypts the token with a key made from that code and drops it on a relay topic that is
 * also made from the code. Only someone holding the code can find the topic or read it.
 * The code itself never leaves the phone or the watch. Pure Java (unit tested on the JVM).
 */
public final class Link {
    public static final String PAGE = "https://peterchabs.github.io/groupme-voice-android/";
    public static final String RELAY = "https://ntfy.sh/";
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // no 0/O, 1/I/L
    static final int LENGTH = 10;                                    // about 49 bits
    static final long MAX_AGE_MS = 15 * 60 * 1000L;

    public final String code;

    public Link(String code) { this.code = normalize(code); }

    public static Link create() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return new Link(sb.toString());
    }

    static String normalize(String s) { return s.toUpperCase().replaceAll("[^0-9A-Z]", ""); }

    /** "K7Q2M-9XAPZ", easier to read and type. */
    public String pretty() { return code.substring(0, 5) + "-" + code.substring(5); }

    public String pageUrl() { return PAGE + "#" + code; }

    public String topic() { return "gmv-" + hex(sha256("gmv-topic:" + code)).substring(0, 32); }

    /** Returns the token, or null if this payload isn't a valid, recent one for this code. */
    public String open(String payload, long nowMs) {
        String[] t = openSignIn(payload, nowMs);
        return t == null ? null : t[0];
    }

    /**
     * {token, browser} from a payload, or null. "v2" also carries the User-Agent of the browser that
     * signed in to GroupMe on the phone ("" from a "v1" payload, which has only the token).
     */
    public String[] openSignIn(String payload, long nowMs) {
        try {
            byte[] all = Base64.getDecoder().decode(payload.trim());
            if (all.length < 12 + 16 + 1) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha256("gmv-key:" + code), "AES"),
                    new GCMParameterSpec(128, all, 0, 12));
            String plain = new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
            String[] p = plain.split("\n", 4);           // version \n timestamp-ms \n token [\n browser]
            boolean v2 = "v2".equals(p[0]);
            if (v2 ? p.length != 4 : (p.length != 3 || !"v1".equals(p[0]))) return null;
            long ts = Long.parseLong(p[1].trim());
            if (Math.abs(nowMs - ts) > MAX_AGE_MS) return null;
            String token = p[2].replaceAll("\\s+", "");
            if (token.isEmpty()) return null;
            return new String[]{token, v2 ? p[3].trim() : ""};
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null; // wrong code, tampered, or not ours: ignore
        }
    }

    /**
     * Locks a token for the watch showing this code (the phone app's side of the hand-off).
     * Same format as the sign-in page: base64(iv + AES-GCM("v1\n" + ms + "\n" + token)).
     */
    public String seal(String token, long nowMs) {
        return sealText("v1\n" + nowMs + "\n" + token);
    }

    /**
     * The token with the User-Agent of the browser that signed in to GroupMe ("v2"). Older watches
     * ignore this one, so the phone sends a plain {@link #seal} as well.
     */
    public String sealWithBrowser(String token, String browser, long nowMs) {
        return sealText("v2\n" + nowMs + "\n" + token + "\n" + browser.replace('\n', ' '));
    }

    private String sealText(String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sha256("gmv-key:" + code), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True for a well-formed watch code (10 characters from the alphabet). */
    public static boolean valid(String s) {
        String n = s == null ? "" : normalize(s);
        if (n.length() != LENGTH) return false;
        for (int i = 0; i < n.length(); i++) if (ALPHABET.indexOf(n.charAt(i)) < 0) return false;
        return true;
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
