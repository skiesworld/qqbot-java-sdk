package io.github.skiesworld.qqbot.error;

/** The Ed25519 signature of an HTTP callback could not be verified. */
public class SignatureException extends QQBotException {

    public SignatureException(String message) {
        super(message);
    }

    public SignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
