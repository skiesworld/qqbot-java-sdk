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
                }
                g = gateway;
            }
        }
        return g;
    }

    /**
     * Bring the inbound side up the way {@link BotConfig#transport()} says: open the gateway for
     * {@link BotConfig.Transport#WEBSOCKET}, bind the callback endpoint for {@link BotConfig.Transport#WEBHOOK}.
     * Whatever this starts is stopped by {@link #close()}.
     *
     * <p>Calling {@link #connect()} or {@link #webhookServer()} directly instead is how you opt into one of them
     * regardless of the configured transport — both at once works too, since they share this client's event bus.
     */
    public QQBotClient start() throws IOException {
        if (config.transport() == BotConfig.Transport.WEBHOOK) {
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
                    webhookServer = new WebhookServer(config.webhookHost(), config.webhookPort())
                            .start().mount(this);
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

    /** Whether the inbound side is up now: the gateway socket connected, or a callback endpoint bound. */
    public boolean isOnline() {
        Gateway g = gateway;
        if (g != null && g.isConnected()) {
            return true;
        }
        WebhookServer s = webhookServer;
        return s != null && s.isRunning();
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
