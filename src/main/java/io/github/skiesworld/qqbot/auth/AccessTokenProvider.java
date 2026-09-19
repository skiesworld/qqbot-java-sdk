package io.github.skiesworld.qqbot.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.error.AuthException;
import io.github.skiesworld.qqbot.util.Json;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Caches the bot {@code access_token} and refreshes it shortly before it expires.
 *
 * <p>The platform keeps a token valid for ~7200s and returns the same value while it is fresh, so
 * fetching eagerly is harmless; inside the last minute of validity a new token is issued instead.
 */
public final class AccessTokenProvider {

    /** Seam for tests and for embedding an externally managed token source. */
    public interface Fetcher {
        AccessToken fetch() throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(AccessTokenProvider.class);

    private final BotConfig config;
    private final Fetcher fetcher;
    private final Object lock = new Object();
    private volatile AccessToken current;

    public AccessTokenProvider(BotConfig config, OkHttpClient client) {
        this(config, new HttpFetcher(config, client));
    }

    public AccessTokenProvider(BotConfig config, Fetcher fetcher) {
        this.config = config;
        this.fetcher = fetcher;
    }

    /** Force a token even without a client secret, e.g. when the caller supplies {@code accessToken()}. */
    public static AccessTokenProvider ofStaticToken(BotConfig config) {
        String token = config.staticAccessToken();
        return new AccessTokenProvider(config, () -> new AccessToken(token, TimeUnit.DAYS.toSeconds(3650)));
    }

    /** Value of the {@code Authorization} header, {@code QQBot <access_token>}. */
    public String authorization() throws IOException {
        return get().authorization();
    }

    public AccessToken get() throws IOException {
        AccessToken cached = current;
        if (cached != null && !isStale(cached)) {
            return cached;
        }
        synchronized (lock) {
            if (current != null && !isStale(current)) {
                return current;
            }
            IOException last = null;
            for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
                try {
                    AccessToken fresh = fetcher.fetch();
                    current = fresh;
                    log.debug("refreshed access token, valid until {}", fresh.expiresAtMillis());
                    return fresh;
                } catch (IOException e) {
                    last = e;
                    if (attempt < config.maxRetries()) {
                        sleepQuietly(config.retryBaseDelay().toMillis() << attempt);
                    }
                }
            }
            throw last == null ? new IOException("access token fetch failed") : last;
        }
    }

    /** Drop the cache so the next call re-fetches; used after an authentication rejection. */
    public void invalidate() {
        synchronized (lock) {
            current = null;
        }
    }

    private boolean isStale(AccessToken token) {
        return token.expiringWithin(config.tokenRefreshMargin().toMillis());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Plain {@code POST {appId, clientSecret}} against the token endpoint. */
    public static final class HttpFetcher implements Fetcher {

        private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

        private final BotConfig config;
        private final OkHttpClient client;

        public HttpFetcher(BotConfig config, OkHttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public AccessToken fetch() throws IOException {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("appId", config.appId());
            payload.put("clientSecret", config.clientSecret());
            Request request = new Request.Builder()
                    .url(config.tokenUrl())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .post(RequestBody.create(Json.toJson(payload), JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                ResponseBody body = response.body();
                String text = body == null ? "" : body.string();
                if (!response.isSuccessful() && text.isEmpty()) {
                    throw new IOException("token request failed with http " + response.code());
                }
                JsonElement parsed = Json.parseLenient(text.isEmpty() ? "{}" : text);
                JsonObject obj = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
                if (obj.has("code") && obj.get("code").getAsLong() != 0) {
                    throw new AuthException("getAppAccessToken rejected: " + safeText(obj, "message"),
                            obj.get("code").getAsInt());
                }
                if (!obj.has("access_token")) {
                    throw new AuthException("getAppAccessToken returned no access_token: " + text, 0);
                }
                return new AccessToken(obj.get("access_token").getAsString(), readSeconds(obj, "expires_in"));
            }
        }

        private static long readSeconds(JsonObject obj, String field) {
            if (!obj.has(field) || obj.get(field).isJsonNull()) {
                return 7200;
            }
            JsonElement v = obj.get(field);
            try {
                return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                        ? Long.parseLong(v.getAsString().trim()) : v.getAsLong();
            } catch (NumberFormatException e) {
                return 7200;
            }
        }

        private static String safeText(JsonObject obj, String field) {
            return obj.has(field) ? obj.get(field).getAsString() : "(no message)";
        }
    }
}
