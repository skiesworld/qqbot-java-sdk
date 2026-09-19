package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.api.Api;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.http.Params;
import io.github.skiesworld.qqbot.media.MediaUploader;
import io.github.skiesworld.qqbot.websocket.Gateway;
import io.github.skiesworld.qqbot.websocket.Intent;

import java.io.Closeable;

/**
 * Entry point tying the pieces together: typed OpenAPI calls, the event gateway, rich-media uploads
 * and the listener registry.
 *
 * <pre>{@code
 * try (QQBotClient bot = QQBotClient.create("APPID", "SECRET")) {
 *     bot.events().on(io.github.skiesworld.qqbot.event.EventType.C2C_MESSAGE_CREATE, event ->
 *             bot.api().c2c().sendC2CMessage(event.targetId(),
 *                     io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest.text("pong")));
 *     bot.connect();
 * }
 * }</pre>
 */
public final class QQBotClient implements Closeable {

    private final BotConfig config;
    private final HttpTransport transport;
    private final EventBus events;
    private final Api api;
    private final MediaUploader media;
    private volatile Gateway gateway;
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
        transport.close();
    }
}
