package io.github.skiesworld.qqbot.auth;

/** A platform access token with its expiry. */
public final class AccessToken {

    private final String token;
    private final long expiresAtMillis;

    public AccessToken(String token, long expiresInSeconds) {
        this.token = token;
        this.expiresAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L;
    }

    public static AccessToken ofDuration(String token, java.time.Duration ttl) {
        return new AccessToken(token, ttl.getSeconds());
    }

    public String token() {
        return token;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean expiringWithin(long millis) {
        return System.currentTimeMillis() + millis >= expiresAtMillis;
    }

    /** Header/identify value form: {@code QQBot <token>}. */
    public String authorization() {
        return "QQBot " + token;
    }

    @Override
    public String toString() {
        return "AccessToken{expiresAt=" + expiresAtMillis + "}";
    }
}
