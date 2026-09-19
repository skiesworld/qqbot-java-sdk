package io.github.skiesworld.qqbot.callback;

import io.github.skiesworld.qqbot.util.Strings;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.NamedParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The official "安全和授权" page publishes, for secret {@code naOC0ocQE3shWLAfffVLB1rhYPG7}, the seed
 * {@code naOC0ocQE3shWLAfffVLB1rhYPG7naOC}, its 32-byte public key, and a signature. The seed and the
 * public key are reproduced here exactly; the published signature does not verify under that key for
 * any spelling of the documented timestamp/body pair, so signature assertions use a golden value
 * cross-checked against an independent Ed25519 implementation.
 */
class SignatureUtilTest {

    private static final String SECRET = "naOC0ocQE3shWLAfffVLB1rhYPG7";
    private static final String EXPECTED_SEED = "naOC0ocQE3shWLAfffVLB1rhYPG7naOC";
    /** Little-endian compressed point from the official demo, decimal {@code [215 195 98 254 ...]}. */
    private static final String OFFICIAL_PUBLIC_KEY =
            "d7c362fe78aef81ff23287b493628b5db02a3c4fe30b215e4d19609b5d76673a";
    private static final String TIMESTAMP = "1725442341";
    private static final String BODY = "{ \"op\": 0,\"d\": {}, \"t\": \"GATEWAY_EVENT_NAME\"}";
    /** sign(secret, timestamp + body) as an independent Ed25519 implementation produces it. */
    private static final String GOLDEN_SIGNATURE =
            "2eb9983ebb8bb209e78fd095942f58e442656656e7975d01e64f9023a84b7c96"
                    + "4290fdd40e5500c33867ccfe9563b7e0b6bac0e1d42c13e787b304fd51f71102";

    @Test
    void seedIsSecretRepeatedTo32Bytes() {
        byte[] seed = SignatureUtil.seedFromSecret(SECRET);
        assertEquals(32, seed.length);
        assertEquals(EXPECTED_SEED, new String(seed, StandardCharsets.UTF_8));
    }

    @Test
    void secretLongerThanTheSeedIsTruncated() {
        String longSecret = "0123456789012345678901234567890123456789";
        assertEquals("01234567890123456789012345678901",
                new String(SignatureUtil.seedFromSecret(longSecret), StandardCharsets.UTF_8));
    }

    @Test
    void signatureMatchesIndependentImplementation() {
        assertEquals(GOLDEN_SIGNATURE, SignatureUtil.signHex(SECRET, TIMESTAMP + BODY));
    }

    /**
     * Verifying with the platform's own published public key proves the key pair we derive from the
     * secret is the one the platform derives, which is what makes re-signing a sound check.
     */
    @Test
    void signatureVerifiesUnderTheOfficialPublicKey() throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(officialPublicKey());
        verifier.update((TIMESTAMP + BODY).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Strings.unhex(GOLDEN_SIGNATURE)),
                "our derived key pair must match the official public key");
    }

    private static PublicKey officialPublicKey() throws Exception {
        byte[] point = Strings.unhex(OFFICIAL_PUBLIC_KEY);
        byte[] y = new byte[point.length];
        for (int i = 0; i < point.length; i++) {
            y[i] = point[point.length - 1 - i];
        }
        boolean rhSign = (point[point.length - 1] & 0x80) != 0;
        y[0] &= 0x7F;
        return KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(
                NamedParameterSpec.ED25519, new EdECPoint(rhSign, new BigInteger(1, y))));
    }

    @Test
    void verificationAcceptsOnlyTheExactBodyAndTimestamp() {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        assertTrue(SignatureUtil.verify(SECRET, TIMESTAMP, body, GOLDEN_SIGNATURE));
        assertFalse(SignatureUtil.verify(SECRET, "1725442342", body, GOLDEN_SIGNATURE));
        assertFalse(SignatureUtil.verify(SECRET, TIMESTAMP, (BODY + " ").getBytes(StandardCharsets.UTF_8),
                GOLDEN_SIGNATURE));
        assertFalse(SignatureUtil.verify("another-secret-0123456789abcdef", TIMESTAMP, body, GOLDEN_SIGNATURE));
    }

    @Test
    void rejectsMalformedOrMissingSignatures() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertFalse(SignatureUtil.verify(SECRET, TIMESTAMP, body, "nothex"));
        assertFalse(SignatureUtil.verify(SECRET, TIMESTAMP, body, "deadbeef"));
        assertFalse(SignatureUtil.verify(SECRET, null, body, GOLDEN_SIGNATURE));
        assertFalse(SignatureUtil.verify(SECRET, TIMESTAMP, body, null));
    }

    /** The opcode 13 challenge is answered with a signature over event_ts + plain_token. */
    @Test
    void validationChallengeAnswersWithPlainTokenAndSignature() {
        String plainToken = "Arq0D5A61EgUu4OxUvOp";
        String eventTs = "1725442341";
        String secret = "DG5g3B4j9X2KOErG";
        String json = SignatureUtil.validationResponse(secret, eventTs, plainToken);
        assertTrue(json.contains("\"plain_token\":\"Arq0D5A61EgUu4OxUvOp\""), json);
        String signature = json.substring(json.indexOf("\"signature\":\"") + 13, json.length() - 2);
        assertEquals(128, signature.length(), json);
        assertEquals(SignatureUtil.signHex(secret, eventTs + plainToken), signature);
    }
}
