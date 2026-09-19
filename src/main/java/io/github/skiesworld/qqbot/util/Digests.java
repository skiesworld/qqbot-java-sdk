package io.github.skiesworld.qqbot.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Digest helpers; the upload protocol asks for whole-file MD5, SHA1 and the MD5 of the first chunk. */
public final class Digests {

    private Digests() {
    }

    public static String md5Hex(byte[] data) {
        return hex("MD5", data);
    }

    public static String sha1Hex(byte[] data) {
        return hex("SHA-1", data);
    }

    public static String md5Hex(byte[] data, int offset, int length) {
        return hex("MD5", java.util.Arrays.copyOfRange(data, offset, offset + length));
    }

    public static String sha256Hex(String text) {
        return hex("SHA-256", text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(String algorithm, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return Strings.hex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " digest unavailable", e);
        }
    }
}
