package io.github.skiesworld.qqbot.error;

/** Credential problems: fetching, refreshing or accepting an {@code access_token}. */
public class AuthException extends QQBotException {

    private final int code;

    public AuthException(String message, int code) {
        super(message + " (code=" + code + ")");
        this.code = code;
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
    }

    public int code() {
        return code;
    }
}
