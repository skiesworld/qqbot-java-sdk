package io.github.skiesworld.qqbot.callback;

import io.github.skiesworld.qqbot.error.SignatureException;
import io.github.skiesworld.qqbot.util.Strings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Ed25519 signing and verification for HTTP callback mode.
 *
 * <p>The platform derives the key pair from the Bot Secret by repeating it until it fills the 32-byte
 * seed and truncating. JDK 17 can build a private key straight from that seed, and because Ed25519 is
 * deterministic, verifying an incoming signature is done by re-signing {@code timestamp + body} and
 * comparing in constant time — no public key has to be reconstructed.
 */
public final class SignatureUtil {

    /** ed25519.SeedSize. */
    static final int SEED_SIZE = 32;
    /** ed25519.SignatureSize. */
    static final int SIGNATURE_SIZE = 64;

    private SignatureUtil() {
    }

    public static byte[] seedFromSecret(String botSecret) {
        byte[] secret = botSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length == 0) {
            throw new IllegalArgumentException("bot secret must not be empty");
        }
        byte[] seed = new byte[SEED_SIZE];
        for (int i = 0; i < SEED_SIZE; i++) {
            seed[i] = secret[i % secret.length];
        }
        return seed;
    }

    public static PrivateKey privateKey(String botSecret) {
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return factory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seedFromSecret(botSecret)));
        } catch (GeneralSecurityException e) {
            throw new SignatureException("Ed25519 is unavailable on this JVM (needs JDK 15+)", e);
        }
    }

    /** Hex signature over an arbitrary message, as the platform computes it. */
    public static String signHex(String botSecret, String message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey(botSecret));
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            byte[] raw = signer.sign();
            if (raw.length != SIGNATURE_SIZE) {
                throw new SignatureException("unexpected signature length " + raw.length);
            }
            return Strings.hex(raw);
        } catch (GeneralSecurityException e) {
            throw new SignatureException("cannot sign callback payload", e);
        }
    }

    public static boolean verify(String botSecret, String timestamp, byte[] body, String signatureHex) {
        if (Strings.isBlank(timestamp) || body == null || Strings.isBlank(signatureHex)) {
            return false;
        }
        byte[] expected;
        try {
            expected = Strings.unhex(signatureHex.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (expected.length != SIGNATURE_SIZE) {
            return false;
        }
        String message = timestamp + new String(body, StandardCharsets.UTF_8);
        byte[] mine;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey(botSecret));
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            mine = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new SignatureException("cannot verify callback payload", e);
        }
        return java.security.MessageDigest.isEqual(mine, expected);
    }

    /**
     * Reply body for the address validation challenge (opcode 13): the signature over
     * {@code eventTs + plainToken}.
     */
    public static String validationResponse(String botSecret, String eventTs, String plainToken) {
        return "{\"plain_token\":" + quote(plainToken) + ",\"signature\":"
                + quote(signHex(botSecret, eventTs + plainToken)) + "}";
    }

    private static String quote(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }
}
