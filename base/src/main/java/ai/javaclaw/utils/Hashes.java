package ai.javaclaw.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of text, as bytes or lowercase hex. */
public final class Hashes {

    private Hashes() {
    }

    /** The SHA-256 digest of {@code value}'s UTF-8 bytes. */
    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** {@link #sha256} as 64 lowercase hex characters. */
    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }
}
