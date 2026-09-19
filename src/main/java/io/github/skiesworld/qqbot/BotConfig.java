package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.util.Strings;

import java.time.Duration;
import java.util.Objects;

/** Immutable connection settings for one bot account. */
public final class BotConfig {

    /** Unified request address of the QQ open platform. */
    public static final String DEFAULT_API_BASE = "https://api.bot.qq.com";
    public static final String TOKEN_PATH = "/app/getAppAccessToken";

    /** The only ports the platform accepts a callback url on are 80, 443, 8080 and 8443. */
    public static final int DEFAULT_WEBHOOK_PORT = 8080;
    /** A bot's route is this prefix plus its app id, so several bots can share one listening port. */
    public static final String WEBHOOK_PATH_PREFIX = "/qq";

    /**
     * How the bot is reached. Both deliver into the same {@link io.github.skiesworld.qqbot.event.EventBus};
     * only the ingress differs, so listeners, handlers and commands are written once either way.
     */
    public enum Transport {

        /** Outbound gateway websocket: identify, heartbeat, resume after a drop. */
        WEBSOCKET,

        /** The platform POSTs callbacks to a public url and every request is verified against the bot secret. */
        WEBHOOK
    }

    private final String appId;
    private final String clientSecret;
    private final String staticAccessToken;
    private final String apiBase;
    private final String wsUrl;
    private final Transport transport;
    private final String botSecret;
    private final String webhookHost;
    private final int webhookPort;
    private final String webhookPath;
    private final long intents;
    private final int shardId;
    private final int shardCount;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final int maxRetries;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Duration tokenRefreshMargin;
    private final String os;
    private final String browser;
    private final String device;
    private final String userAgent;

    private BotConfig(Builder b) {
        this.appId = Strings.requireNonBlank(b.appId, "appId");
        this.clientSecret = b.clientSecret;
        this.staticAccessToken = b.staticAccessToken;
        this.apiBase = Strings.isBlank(b.apiBase) ? DEFAULT_API_BASE : trimTrailingSlash(b.apiBase);
        this.wsUrl = b.wsUrl;
        this.transport = b.transport == null ? Transport.WEBSOCKET : b.transport;
        this.botSecret = Strings.isBlank(b.botSecret) ? b.clientSecret : b.botSecret;
        this.webhookHost = Strings.isBlank(b.webhookHost) ? "0.0.0.0" : b.webhookHost;
        this.webhookPort = b.webhookPort;
        this.webhookPath = webhookPath(b.webhookPath, b.appId);
        if (transport == Transport.WEBHOOK) {
            if (b.webhookPort < 0 || b.webhookPort > 65_535) {
                throw new IllegalArgumentException("webhookPort " + b.webhookPort + " is not a bindable port");
            }
            if (Strings.isBlank(this.botSecret)) {
                throw new IllegalArgumentException("transport WEBHOOK verifies every callback, so botSecret"
                        + " (or clientSecret) is required");
            }
        } else if (b.webhookPort != DEFAULT_WEBHOOK_PORT) {
            throw new IllegalArgumentException("webhookPort is only meaningful with transport WEBHOOK");
        }
        this.intents = b.intents;
        this.shardId = b.shardId;
        this.shardCount = Math.max(1, b.shardCount);
        if (shardId < 0 || shardId >= shardCount) {
            throw new IllegalArgumentException("shardId " + shardId + " out of range for " + shardCount + " shards");
        }
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.writeTimeout = b.writeTimeout;
        this.maxRetries = b.maxRetries;
        this.retryBaseDelay = b.retryBaseDelay;
        this.retryMaxDelay = b.retryMaxDelay;
        this.tokenRefreshMargin = b.tokenRefreshMargin;
        this.os = b.os;
        this.browser = b.browser;
        this.device = b.device;
        this.userAgent = b.userAgent;
        if (Strings.isBlank(b.staticAccessToken) && Strings.isBlank(b.clientSecret)) {
            throw new IllegalArgumentException("either clientSecret or a static access token is required");
        }
    }

    private static String trimTrailingSlash(String v) {
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    /**
     * Callback paths always start with one slash and never end with one. Left unset a bot is addressed as
     * {@code /qq/{appId}}, which is what lets one endpoint serve many bots.
     */
    private static String webhookPath(String configured, String appId) {
        String path = Strings.isBlank(configured) ? WEBHOOK_PATH_PREFIX + "/" + appId : configured.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.length() == 1 ? path : trimTrailingSlash(path);
    }

    public String appId() {
        return appId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    /** The key callbacks are signed with; the platform calls it the Bot Secret and it is usually the app secret. */
    public String botSecret() {
        return botSecret;
    }

    public Transport transport() {
        return transport;
    }

    public String webhookHost() {
        return webhookHost;
    }

    public int webhookPort() {
        return webhookPort;
    }

    /** This bot's route on a shared webhook endpoint, e.g. {@code /qq/100000000}. */
    public String webhookPath() {
        return webhookPath;
    }

    /** Pre-issued access token; when set the SDK never calls the token endpoint. */
    public String staticAccessToken() {
        return staticAccessToken;
    }

    public String apiBase() {
        return apiBase;
    }

    /** Explicit gateway url. When null the SDK resolves it through {@code GET /gateway/bot}. */
    public String wsUrl() {
        return wsUrl;
    }

    public String tokenUrl() {
        return apiBase + TOKEN_PATH;
    }

    public long intents() {
        return intents;
    }

    public int shardId() {
        return shardId;
    }

    public int shardCount() {
        return shardCount;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public Duration writeTimeout() {
        return writeTimeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration retryBaseDelay() {
        return retryBaseDelay;
    }

    public Duration retryMaxDelay() {
        return retryMaxDelay;
    }

    /** How long before expiry an access token is considered stale. */
    public Duration tokenRefreshMargin() {
        return tokenRefreshMargin;
    }

    public String os() {
        return os;
    }

    public String browser() {
        return browser;
    }

    public String device() {
        return device;
    }

    public String userAgent() {
        return userAgent;
    }

    public static Builder builder(String appId) {
        return new Builder(appId);
    }

    public Builder toBuilder() {
        Builder b = new Builder(appId);
        b.clientSecret = clientSecret;
        b.staticAccessToken = staticAccessToken;
        b.apiBase = apiBase;
        b.wsUrl = wsUrl;
        b.transport = transport;
        b.botSecret = botSecret;
        b.webhookHost = webhookHost;
        b.webhookPort = webhookPort;
        b.webhookPath = webhookPath;
        b.intents = intents;
        b.shardId = shardId;
        b.shardCount = shardCount;
        b.connectTimeout = connectTimeout;
        b.readTimeout = readTimeout;
        b.writeTimeout = writeTimeout;
        b.maxRetries = maxRetries;
        b.retryBaseDelay = retryBaseDelay;
        b.retryMaxDelay = retryMaxDelay;
        b.tokenRefreshMargin = tokenRefreshMargin;
        b.os = os;
        b.browser = browser;
        b.device = device;
        b.userAgent = userAgent;
        return b;
    }

    public static final class Builder {
        private final String appId;
        private String clientSecret;
        private String staticAccessToken;
        private String apiBase;
        private String wsUrl;
        private Transport transport;
        private String botSecret;
        private String webhookHost;
        private int webhookPort = DEFAULT_WEBHOOK_PORT;
        private String webhookPath;
        private long intents;
        private int shardId;
        private int shardCount = 1;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private Duration writeTimeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
        private Duration retryBaseDelay = Duration.ofMillis(500);
        private Duration retryMaxDelay = Duration.ofSeconds(30);
        private Duration tokenRefreshMargin = Duration.ofSeconds(60);
        private String os = System.getProperty("os.name", "unknown");
        private String browser = "qqbot-java-sdk";
        private String device = "qqbot-java-sdk";
        private String userAgent = "qqbot-java-sdk/1.0.0";

        private Builder(String appId) {
            this.appId = appId;
        }

        public Builder clientSecret(String v) {
            this.clientSecret = v;
            return this;
        }

        /** Bring your own token (external cache, proxy service, already refreshed elsewhere). */
        public Builder accessToken(String v) {
            this.staticAccessToken = v;
            return this;
        }

        public Builder apiBase(String v) {
            this.apiBase = v;
            return this;
        }

        public Builder wsUrl(String v) {
            this.wsUrl = v;
            return this;
        }

        /** Which inbound channel this bot uses; {@link Transport#WEBSOCKET} unless you say otherwise. */
        public Builder transport(Transport v) {
            this.transport = Objects.requireNonNull(v, "transport");
            return this;
        }

        /** Key callbacks are signed with. Defaults to {@link #clientSecret(String)}. */
        public Builder botSecret(String v) {
            this.botSecret = v;
            return this;
        }

        /** Interface to bind the built-in callback endpoint to; ignored by {@link #webhook(int, String)}. */
        public Builder webhookHost(String v) {
            this.webhookHost = v;
            return this;
        }

        /**
         * Switch to {@link Transport#WEBHOOK} and where to listen <em>when this bot has its own endpoint</em>;
         * several bots normally share one, see {@link io.github.skiesworld.qqbot.callback.WebhookServer#mount}.
         * Port 0 asks the OS for a free one, which {@code bot.webhookServer().port()} then reports; the platform
         * only accepts 80, 443, 8080 and 8443 on the public url. A null path keeps the {@code /qq/{appId}} route.
         */
        public Builder webhook(int port, String path) {
            this.transport = Transport.WEBHOOK;
            this.webhookPort = port;
            this.webhookPath = path;
            return this;
        }

        public Builder intents(long v) {
            this.intents = v;
            return this;
        }

        public Builder intents(io.github.skiesworld.qqbot.websocket.Intent... v) {
            long bits = 0;
            for (io.github.skiesworld.qqbot.websocket.Intent i : v) {
                bits |= i.bit();
            }
            this.intents = bits;
            return this;
        }

        public Builder shard(int id, int count) {
            this.shardId = id;
            this.shardCount = count;
            return this;
        }

        public Builder connectTimeout(Duration v) {
            this.connectTimeout = Objects.requireNonNull(v);
            return this;
        }

        public Builder readTimeout(Duration v) {
            this.readTimeout = Objects.requireNonNull(v);
            return this;
        }

        public Builder writeTimeout(Duration v) {
            this.writeTimeout = Objects.requireNonNull(v);
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = Math.max(0, v);
            return this;
        }

        public Builder retryBaseDelay(Duration v) {
            this.retryBaseDelay = Objects.requireNonNull(v);
            return this;
        }

        public Builder retryMaxDelay(Duration v) {
            this.retryMaxDelay = Objects.requireNonNull(v);
            return this;
        }

        public Builder tokenRefreshMargin(Duration v) {
            this.tokenRefreshMargin = Objects.requireNonNull(v);
            return this;
        }

        public Builder identity(String os, String browser, String device) {
            this.os = os;
            this.browser = browser;
            this.device = device;
            return this;
        }

        public Builder userAgent(String v) {
            this.userAgent = v;
            return this;
        }

        public BotConfig build() {
            return new BotConfig(this);
        }
    }
}
