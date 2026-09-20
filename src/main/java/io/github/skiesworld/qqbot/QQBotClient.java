package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.api.Api;
import io.github.skiesworld.qqbot.audit.Audits;
import io.github.skiesworld.qqbot.callback.WebhookHandler;
import io.github.skiesworld.qqbot.callback.WebhookServer;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.Outbound;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.handler.HandlerRegistry;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.http.Params;
import io.github.skiesworld.qqbot.media.MediaUploader;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.ReplySequence;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.websocket.Gateway;
import io.github.skiesworld.qqbot.websocket.Intent;
import io.github.skiesworld.qqbot.model.BotProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * Entry point tying the pieces together: typed OpenAPI calls, the event bus, whichever inbound transport
 * {@link BotConfig#transport()} selects, rich-media uploads and the handler registry.
 *
 * <p>The client attaches itself to its bus, which is what lets an envelope answer the dispatch it came from —
 * {@code msg.reply("收到")} on a {@link io.github.skiesworld.qqbot.event.QQMessageEvent} — without a handler
 * holding on to a client variable.
 *
 * <pre>{@code
 * try (QQBotClient bot = QQBotClient.create("APPID", "SECRET")) {
 *     bot.events().on(io.github.skiesworld.qqbot.event.EventType.C2C_MESSAGE_CREATE, event ->
 *             bot.api().c2c().sendC2CMessage(event.conversationId(),
 *                     io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest.text("pong")));
 *     bot.connect();      // or bot.start(), which follows the configured transport
 * }
 * }</pre>
 */
public final class QQBotClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(QQBotClient.class);

    private final BotConfig config;
    private final HttpTransport transport;
    private final EventBus events;
    private final Api api;
    private final MediaUploader media;
    private final ReplySequence replies = new ReplySequence();
    private volatile Gateway gateway;
    private volatile WebhookHandler webhook;
    private volatile WebhookServer webhookServer;
    private volatile boolean ownsWebhookServer;
    private volatile HandlerRegistry handlers;
    private volatile io.github.skiesworld.qqbot.audit.Audits audits;
    private volatile BotProfile selfProfile;
    private volatile String selfId;
    private final java.util.concurrent.atomic.AtomicBoolean gatewayStarted =
            new java.util.concurrent.atomic.AtomicBoolean();

    public QQBotClient(BotConfig config) {
        this(config, new HttpTransport(config), new EventBus());
    }

    public QQBotClient(BotConfig config, HttpTransport transport, EventBus events) {
        this.config = config;
        this.transport = transport;
        this.events = events;
        this.api = new Api(transport);
        this.media = new MediaUploader(transport);
        this.events.outbound(new Outbound() {
            @Override
            public void reply(QQEvent event, MessageBuilder body) {
                ReplyTarget.of(event).send(QQBotClient.this, event, body, replies);
            }

            @Override
            public Api api() {
                return api;
            }
        });
    }

    public static QQBotClient create(String appId, String clientSecret) {
        return new QQBotClient(BotConfig.builder(appId).clientSecret(clientSecret)
                .intents(Intent.GROUP_AND_C2C_EVENT, Intent.GUILDS, Intent.PUBLIC_GUILD_MESSAGES)
                .build());
    }

    public static QQBotClient create(BotConfig config) {
        return new QQBotClient(config);
    }

    public BotConfig config() {
        return config;
    }

    public HttpTransport transport() {
        return transport;
    }

    public Api api() {
        return api;
    }

    public EventBus events() {
        return events;
    }

    public MediaUploader media() {
        return media;
    }

    /** The counter behind {@link io.github.skiesworld.qqbot.event.QQMessageEvent#reply(String)}: one
     * {@code msg_seq} per original message. */
    public ReplySequence replies() {
        return replies;
    }

    /**
     * Annotated-handler registry bound to this client, so handler methods can ask for {@link Api}, this client or
     * a matched command as a parameter; {@link HandlerRegistry#usePrefixes} and {@link HandlerRegistry#describe()}
     * are the command-side knobs.
     */
    public HandlerRegistry handlers() {
        HandlerRegistry h = handlers;
        if (h == null) {
            synchronized (this) {
                if (handlers == null) {
                    handlers = new HandlerRegistry(events, this);
                }
                h = handlers;
            }
        }
        return h;
    }

    /** Lazily created gateway bound to this client's event bus. */
    public Gateway gateway() {
        Gateway g = gateway;
        if (g == null) {
            synchronized (this) {
                if (gateway == null) {
                    gateway = new Gateway(config, transport, events);
                    gateway.addListener(new Gateway.Listener() {
                        @Override
                        public void onReady(String sessionId, com.google.gson.JsonElement user) {
                            cacheSelfId(selfIdOf(user));
                        }
                    });
                }
                g = gateway;
            }
        }
        return g;
    }

    /** The id READY's {@code d.user} reports, under whichever of its two names the platform filled. */
    private static String selfIdOf(com.google.gson.JsonElement user) {
        if (user == null || !user.isJsonObject()) {
            return null;
        }
        com.google.gson.JsonObject object = user.getAsJsonObject();
        for (String key : new String[]{"id", "user_openid"}) {
            if (object.get(key) != null && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        }
        return null;
    }

    /**
     * Bring the inbound side up the way {@link BotConfig#transport()} says: open the gateway for
     * {@link BotConfig.Transport#WEBSOCKET}, bind the callback endpoint for {@link BotConfig.Transport#WEBHOOK}.
     * Whatever this starts is stopped by {@link #close()}.
     *
     * <p>Callback bots call {@link #self()} first, so a wrong app id, a wrong secret or a bot the platform has
     * not enabled fails here, before anything is listening. A start that fails this way leaves no half-bound
     * endpoint behind, and can simply be tried again.
     *
     * <p>Calling {@link #connect()} or {@link #webhookServer()} directly instead is how you opt into one of them
     * regardless of the configured transport — both at once works too, since they share this client's event bus.
     */
    public QQBotClient start() throws IOException {
        if (config.transport() == BotConfig.Transport.WEBHOOK) {
            self();
            webhookServer();
        } else {
            connect();
        }
        return this;
    }

    /** The callback verifier and dispatcher; mount {@link WebhookHandler#handle} in your own HTTP server. */
    public WebhookHandler webhook() {
        WebhookHandler h = webhook;
        if (h == null) {
            synchronized (this) {
                if (webhook == null) {
                    webhook = new WebhookHandler(config.botSecret(), config.appId(), events);
                }
                h = webhook;
            }
        }
        return h;
    }

    /**
     * This bot's own callback endpoint, bound on first call. Several bots normally share one
     * {@link WebhookServer} instead of one port each: {@code endpoint.mount(bot)} routes it by app id and keeps
     * each bot's verification and bus separate, which is what {@link Bots} does for you.
     */
    public WebhookServer webhookServer() throws IOException {
        WebhookServer s = webhookServer;
        if (s == null) {
            synchronized (this) {
                if (webhookServer == null) {
                    // only remembered once it actually bound, so a failed start leaves nothing to retry around
                    WebhookServer bound = new WebhookServer(config.webhookHost(), config.webhookPort()).start();
                    bound.mount(this);
                    webhookServer = bound;
                    ownsWebhookServer = true;
                }
                s = webhookServer;
            }
        }
        return s;
    }

    /**
     * Answer this bot's callbacks through an endpoint someone else owns, which is what {@link Bots} does: the
     * route, the verification and the bus stay this bot's, and {@link #close()} leaves the socket up for the
     * others.
     */
    void useSharedEndpoint(WebhookServer shared) {
        synchronized (this) {
            webhookServer = Objects.requireNonNull(shared, "shared");
            ownsWebhookServer = false;
        }
    }

    /**
     * Whether the inbound side is up for this bot. On a shared callback endpoint this is whether this bot's own
     * route is still mounted there — the socket being open says nothing about this bot being usable.
     */
    public boolean isOnline() {
        Gateway g = gateway;
        if (g != null && g.isConnected()) {
            return true;
        }
        WebhookServer s = webhookServer;
        if (s == null || !s.isRunning()) {
            return false;
        }
        return ownsWebhookServer || s.paths().contains(config.webhookPath());
    }

    /**
     * Waiting for review verdicts on messages this client sent, e.g.
     * {@code bot.audits().resultOf(auditId, Duration.ofMinutes(5))} after an
     * {@link io.github.skiesworld.qqbot.error.AuditPendingException}.
     */
    public Audits audits() {
        Audits a = audits;
        if (a == null) {
            synchronized (this) {
                if (audits == null) {
                    audits = new Audits(events);
                }
                a = audits;
            }
        }
        return a;
    }

    /** Open the event gateway without blocking; returns once the socket has been requested. */
    public Gateway connect() {
        Gateway g = gateway();
        if (gatewayStarted.compareAndSet(false, true)) {
            g.start();
        }
        return g;
    }

    /** Connect and wait for the READY dispatch, useful for command line bots and smoke tests. */
    public Gateway connectAndAwaitReady(long timeoutMillis) throws InterruptedException {
        if (config.transport() == BotConfig.Transport.WEBHOOK) {
            throw new IllegalStateException("this bot is configured for " + BotConfig.Transport.WEBHOOK
                    + " callbacks, which have no gateway READY to wait for; call start() instead");
        }
        Gateway g = connect();
        if (!g.awaitConnected(timeoutMillis)) {
            throw new io.github.skiesworld.qqbot.error.QQBotException("gateway did not connect within "
                    + timeoutMillis + "ms");
        }
        return g;
    }

    /**
     * This bot's own profile: {@code GET /users/@me} once, then cached, because a bot's identity does not change
     * under it. A failure is not cached, so a network problem at boot is not permanent — call it again, or
     * {@link #start()} again.
     *
     * <p>{@link #start()} calls this before it binds anything when the transport is
     * {@link BotConfig.Transport#WEBHOOK}, which is also the cheapest credential check there is: a wrong
     * app id/secret pair fails here instead of on the first reply a user waited for.
     */
    public BotProfile self() {
        BotProfile cached = selfProfile;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (selfProfile == null) {
                BotProfile profile = api.me().getBotProfile();
                if (profile == null || profile.id == null || profile.id.isBlank()) {
                    throw new io.github.skiesworld.qqbot.error.QQBotException(
                            "GET /users/@me answered without a bot id for app " + config.appId()
                                    + "; the credentials do not identify a bot");
                }
                selfProfile = profile;
                selfId = profile.id;
                log.info("bot {} is {} (id={})", config.appId(), profile.username, profile.id);
            }
            return selfProfile;
        }
    }

    /**
     * This bot's own id as far as it already knows: the {@link #self()} cache, or the {@code user} the gateway's
     * READY carried. Never makes a call and never blocks, so event-path code (gates, handlers) can compare
     * against it; null until one of the two has answered.
     */
    public String selfId() {
        return selfId;
    }

    /** Remember the identity READY carried, so a websocket bot needs no profile call to know itself. */
    void cacheSelfId(String id) {
        if (id != null && !id.isBlank()) {
            selfId = id;
        }
    }

    /** Escape hatch for any documented or newly added operation without waiting for an SDK release. */
    public <T> T execute(Endpoint<T> endpoint, Params params, Object body) {
        return transport.execute(endpoint, params, body);
    }

    @Override
    public void close() {
        Gateway g = gateway;
        if (g != null) {
            g.close();
        }
        WebhookServer s = webhookServer;
        if (s != null) {
            if (ownsWebhookServer) {
                s.close();
            } else {
                s.unmount(config.webhookPath());
            }
        }
        transport.close();
    }
}
