package io.github.skiesworld.qqbot.util;

import java.nio.charset.StandardCharsets;

public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    public static String requireNonBlank(String s, String what) {
        if (isBlank(s)) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return s;
    }

    /**
     * {@code group_openid} -> {@code groupOpenid}; {@code C2C_MESSAGE_CREATE} -> {@code C2CMessageCreate}.
     *
     * <p>Segments are cased by their own style: an all-caps segment keeps the capitals that follow a
     * digit so acronyms such as {@code C2C} survive, while a lower-case segment is simply capitalised,
     * which keeps {@code md5_10m} reading as {@code md510m} rather than {@code md510M}.
     */
    public static String snakeToUpperCamel(String snake) {
        StringBuilder out = new StringBuilder(snake.length());
        for (String segment : snake.split("[_\\- ]")) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append(casedSegment(segment));
        }
        return out.toString();
    }

    private static String casedSegment(String segment) {
        String stripped = segment.replace("$", "");
        long letters = stripped.chars().filter(Character::isLetter).count();
        boolean shouty = letters > 1
                && stripped.chars().filter(Character::isLetter).allMatch(Character::isUpperCase);
        StringBuilder sb = new StringBuilder(stripped.length());
        char previous = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!Character.isLetter(c)) {
                sb.append(c);
                previous = c;
                continue;
            }
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (shouty && Character.isDigit(previous)) {
                sb.append(c);
            } else {
                sb.append(Character.toLowerCase(c));
            }
            previous = c;
        }
        return sb.toString();
    }

    public static String snakeToLowerCamel(String snake) {
        String s = snakeToUpperCamel(snake);
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Last path segment as a lower camel name: {@code /v2/groups/{x}/messages} -> {@code messages}. */
    public static String lastSegment(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String hex) {
        String h = hex.trim();
        if ((h.length() & 1) != 0) {
            throw new IllegalArgumentException("hex string has odd length: " + h.length());
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("not a hex string: " + h);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Length-independent comparison, used for signature checks. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    public static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Shorten a payload for logging. */
    public static String trimTail(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }

    public static String trimTail(String s) {
        return trimTail(s, 200);
    }

    public static String maskMiddle(String s) {
        if (s == null || s.length() < 12) {
            return "***";
        }
        return s.substring(0, 4) + "***" + s.substring(s.length() - 4);
    }
}
