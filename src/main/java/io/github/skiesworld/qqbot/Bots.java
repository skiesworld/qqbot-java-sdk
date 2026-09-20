package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.callback.WebhookServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bots one process runs, looked up by app id — which is what a plugin, a command handler or a web route
 * needs, since none of them is written against one particular bot.
 *
 * <pre>{@code
 * Bots bots = new Bots().webhookEndpoint("0.0.0.0", 8080);   // one socket for all of them
 * bots.register(QQBotClient.create(configA)).register(QQBotClient.create(configB));
 * bots.startAll();
 *
 * bots.get(appId)              -&gt; Optional&lt;QQBotClient&gt;      // by app id
 * bots.getBot()                -&gt; the only bot, or an error   // single-bot process shorthand
 * bots.getBots()               -&gt; every bot, in registration order
 * }</pre>
 *
 * <p>Registration is by app id and an id is accepted once, because two bots on one id would answer the same
 * events twice. A bot whose {@link BotConfig#transport()} is {@link BotConfig.Transport#WEBHOOK} is mounted on
 * this registry's shared endpoint at {@code /qq/{appId}} instead of binding a port of its own, and closing the
 * registry closes the endpoint; closing one bot leaves the shared socket up for the others.
 *
 * <p>Order is registration order: {@link #startAll()} comes up in it and {@link #close()} goes down in reverse,
 * so a bot that others were asked to talk to is the last one to go.
 */
public final class Bots implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Bots.class);

    private final Map<String, QQBotClient> byAppId = new LinkedHashMap<>();
    private volatile WebhookServer endpoint;
    private volatile String endpointHost;
    private volatile int endpointPort = -1;

    /** Add one or several bots; see {@link #register(QQBotClient)}. */
    public Bots register(QQBotClient... bots) {
        for (QQBotClient bot : bots) {
            register(bot);
        }
        return this;
    }

    /**
     * Take a bot, keyed by its app id, mounting it on the shared callback endpoint when it wants one.
     *
     * @throws IllegalArgumentException when that id is already taken — a bot registered twice would answer
     *                                  every dispatch twice
     */
    public synchronized Bots register(QQBotClient bot) {
        Objects.requireNonNull(bot, "bot");
        String appId = bot.config().appId();
        if (byAppId.containsKey(appId)) {
            throw new IllegalArgumentException("a bot is already registered for app id " + appId
                    + "; close it first, since two bots on one id answer every event twice");
        }
        byAppId.put(appId, bot);
        if (bot.config().transport() == BotConfig.Transport.WEBHOOK && endpointPort >= 0) {
            mount(bot);
        }
        return this;
    }

    /**
     * Bind one socket for the callback bots hereafter, which is normally set before registering anything.
     *
     * @param port 0 to let the operating system pick, after which {@link #endpoint()} reports which port that was
     */
    public Bots webhookEndpoint(String host, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("webhook port must be 0-65535, got " + port);
        }
        this.endpointHost = host;
        this.endpointPort = port;
        return this;
    }

    /** The shared callback endpoint, bound on first use. */
    public synchronized WebhookServer endpoint() throws IOException {
        if (endpointPort < 0) {
            throw new IllegalStateException("no callback endpoint was configured; call webhookEndpoint(host, port)"
                    + " before starting a bot whose transport is WEBHOOK");
        }
        if (endpoint == null) {
            endpoint = new WebhookServer(endpointHost, endpointPort).start();
        }
        return endpoint;
    }

    /** The bot for {@code appId}, if one is registered. */
    public Optional<QQBotClient> get(String appId) {
        return Optional.ofNullable(byAppId.get(appId));
    }

    /** The only bot this process runs. */
    public QQBotClient getBot() {
        Collection<QQBotClient> all = byAppId.values();
        if (all.size() != 1) {
            throw new IllegalStateException("there " + (all.size() < 2 ? "is no bot" : "are " + all.size()
                    + " bots") + " registered; getBot() is the shorthand for a single-bot process, use"
                    + " get(appId) otherwise");
        }
        return all.iterator().next();
    }

    /** Every bot, in registration order. */
    public List<QQBotClient> getBots() {
        return List.copyOf(byAppId.values());
    }

    public int size() {
        return byAppId.size();
    }

    /** Whether the bot for {@code appId} is up — which is not the same question as whether it is registered. */
    public boolean isOnline(String appId) {
        QQBotClient bot = byAppId.get(appId);
        return bot != null && bot.isOnline();
    }

    /** Bring every bot up the way its own {@link BotConfig#transport()} says. */
    public Bots startAll() throws IOException {
        for (QQBotClient bot : new ArrayList<>(byAppId.values())) {
            if (bot.config().transport() == BotConfig.Transport.WEBHOOK && endpointPort >= 0) {
                mount(bot);
            }
            bot.start();
        }
        return this;
    }

    private void mount(QQBotClient bot) {
        try {
            WebhookServer server = endpoint();
            if (!server.paths().contains(bot.config().webhookPath())) {
                server.mount(bot);
            }
            bot.useSharedEndpoint(server);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind the shared callback endpoint", e);
        }
    }

    /** Stop every bot, most recently registered first, and release the shared endpoint. */
    @Override
    public synchronized void close() {
        List<QQBotClient> ordered = new ArrayList<>(byAppId.values());
        Collections.reverse(ordered);
        for (QQBotClient bot : ordered) {
            try {
                bot.close();
            } catch (RuntimeException e) {
                log.error("closing bot {} threw", bot.config().appId(), e);
            }
        }
        byAppId.clear();
        WebhookServer server = endpoint;
        if (server != null) {
            server.close();
            endpoint = null;
        }
    }
}
