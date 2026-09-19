package io.github.skiesworld.qqbot.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.error.QQBotException;
import io.github.skiesworld.qqbot.error.WsException;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.http.Params;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.util.Strings;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The event gateway: connects, identifies, keeps the session alive and resumes it after a drop.
 *
 * <p>Lifecycle expected from the platform:
 * <pre>
 *   connect -> op10 Hello(heartbeat_interval)
 *            -> op2 Identify | op6 Resume
 *            -> op0 dispatch READY | RESUMED, then business events
 * </pre>
 * A drop replays missed events through RESUME using the last {@code s} and the stored session id;
 * only when the platform rejects the session does the gateway fall back to IDENTIFY.
 */
public final class Gateway implements Closeable {

    public enum State {
        IDLE, CONNECTING, AWAITING_HELLO, AUTHENTICATING, CONNECTED, RECONNECTING, STOPPED
    }

    /** Observability hooks; every method has a no-op default. */
    public interface Listener {
        default void onStateChange(State from, State to) {
        }

        default void onReady(String sessionId, JsonElement user) {
        }

        default void onResumed() {
        }

        default void onHeartbeatAck(long roundTripMillis) {
        }

        default void onError(Throwable error) {
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);
    private static final Endpoint<GatewayInfo> GET_GATEWAY_BOT =
            Endpoint.of(Endpoint.Method.GET, "/gateway/bot", GatewayInfo.class);
    private static final long DEFAULT_HEARTBEAT_MILLIS = 45_000;
    /** Missing ACK for this many heartbeat periods is treated as a dead connection. */
    private static final int HEARTBEAT_ACK_TOLERANCE = 2;

    private final BotConfig config;
    private final HttpTransport http;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final AtomicInteger reconnects = new AtomicInteger();

    private volatile WebSocket socket;
    private volatile State state = State.IDLE;
    private volatile String sessionId;
    private volatile Long seq;
    private volatile long heartbeatInterval = DEFAULT_HEARTBEAT_MILLIS;
    private volatile long lastHeartbeatSentAt;
    private volatile boolean heartbeatAcked = true;
    private volatile String gatewayUrl;
    private ScheduledFuture<?> heartbeatTask;

    public Gateway(BotConfig config, HttpTransport http, EventBus eventBus) {
        this(config, http, eventBus, null);
    }

    public Gateway(BotConfig config, HttpTransport http, EventBus eventBus, ScheduledExecutorService scheduler) {
        this.config = config;
        this.http = http;
        this.eventBus = eventBus;
        this.ownsScheduler = scheduler == null;
        this.scheduler = scheduler != null ? scheduler : Executors.newScheduledThreadPool(2, daemonFactory());
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "qqbot-gateway-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public Gateway addListener(Listener listener) {
        listeners.add(listener);
        return this;
    }

    public State state() {
        return state;
    }

    public String sessionId() {
        return sessionId;
    }

    public Long lastSeq() {
        return seq;
    }

    public int reconnectCount() {
        return reconnects.get();
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** Resolves the gateway url when needed, then connects without blocking. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new QQBotException("gateway already started");
        }
        scheduler.execute(this::open);
    }

    /** Blocking connect used by tests and simple embedders: returns once READY was observed. */
    public void startAndAwaitReady(long timeoutMillis) throws InterruptedException {
        start();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (sessionId != null) {
                return;
            }
            Thread.sleep(20);
        }
        throw new QQBotException("gateway did not become ready within " + timeoutMillis + "ms");
    }

    private void open() {
        if (shuttingDown.get()) {
            return;
        }
        setState(State.CONNECTING);
        String url;
        try {
            url = resolveUrl();
        } catch (RuntimeException e) {
            setState(State.IDLE);
            fireError(e);
            scheduleReconnect();
            return;
        }
        setState(State.AWAITING_HELLO);
        Request request = new Request.Builder().url(url).header("User-Agent", config.userAgent()).build();
        try {
            this.socket = http.client().newWebSocket(request, new SocketListener());
        } catch (RuntimeException e) {
            setState(State.IDLE);
            fireError(e);
            scheduleReconnect();
        }
    }

    private String resolveUrl() {
        if (Strings.isNotBlank(gatewayUrl)) {
            return gatewayUrl;
        }
        if (Strings.isNotBlank(config.wsUrl())) {
            gatewayUrl = config.wsUrl();
            return gatewayUrl;
        }
        GatewayInfo info = http.execute(GET_GATEWAY_BOT, Params.of(), null);
        if (info == null || Strings.isBlank(info.url)) {
            throw new QQBotException("GET /gateway/bot returned no url");
        }
        GatewayInfo.SessionStartLimit limit = info.sessionStartLimit;
        if (limit != null && limit.remaining != null && limit.remaining <= 0) {
            long wait = limit.resetAfter == null ? 60_000 : Math.min(limit.resetAfter, 60_000);
            log.warn("session start quota exhausted, retrying in {}ms", wait);
            scheduleReconnect(wait);
            throw new QQBotException("session start quota exhausted, retry in " + wait + "ms");
        }
        if (config.shardCount() == 1 && info.shards != null && info.shards > 1) {
            log.info("platform recommends {} shards, current config uses 1", info.shards);
        }
        gatewayUrl = info.url;
        return gatewayUrl;
    }

    /** Force the next connection to a specific url; useful for a sandbox or a proxy. */
    public void useUrl(String url) {
        this.gatewayUrl = url;
    }

    private void authenticate() {
        setState(State.AUTHENTICATING);
        if (sessionId != null && seq != null) {
            resume();
        } else {
            identify();
        }
    }

    private void identify() {
        JsonObject d = new JsonObject();
        try {
            d.addProperty("token", http.tokens().authorization());
        } catch (Exception e) {
            fireError(new WsException(4002, "cannot obtain access token for identify"));
            return;
        }
        d.addProperty("intents", config.intents());
        d.add("shard", Json.GSON.toJsonTree(new int[]{config.shardId(), config.shardCount()}));
        JsonObject props = new JsonObject();
        props.addProperty("$os", config.os());
        props.addProperty("$browser", config.browser());
        props.addProperty("$device", config.device());
        d.add("properties", props);
        send(GatewayPayload.upstream(OpCode.IDENTIFY.code(), d));
    }

    private void resume() {
        JsonObject d = new JsonObject();
        try {
            d.addProperty("token", http.tokens().authorization());
        } catch (Exception e) {
            fireError(new WsException(4002, "cannot obtain access token for resume"));
            return;
        }
        d.addProperty("session_id", sessionId);
        d.addProperty("seq", seq);
        send(GatewayPayload.upstream(OpCode.RESUME.code(), d));
    }

    private void onHello(GatewayPayload payload) {
        JsonObject d = payload.dataObject();
        if (d.has("heartbeat_interval")) {
            heartbeatInterval = Math.max(1000, d.get("heartbeat_interval").getAsLong());
        }
        log.debug("hello, heartbeat every {}ms shard=[{},{}]", heartbeatInterval,
                config.shardId(), config.shardCount());
        startHeartbeat();
        authenticate();
    }

    private void startHeartbeat() {
        synchronized (this) {
            // a fresh socket must not inherit the previous connection's outstanding ack
            heartbeatAcked = true;
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
            }
            long firstDelay = Math.max(200, (long) (heartbeatInterval * Math.random() * 0.5));
            heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, firstDelay, heartbeatInterval,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void heartbeat() {
        if (state != State.CONNECTED && state != State.AUTHENTICATING && state != State.AWAITING_HELLO) {
            return;
        }
        if (!heartbeatAcked) {
            log.warn("no heartbeat ack for {}ms, reconnecting", heartbeatInterval * HEARTBEAT_ACK_TOLERANCE);
            dropForReconnect(new WsException(4009, "heartbeat ack timeout"));
            return;
        }
        heartbeatAcked = false;
        lastHeartbeatSentAt = System.currentTimeMillis();
        JsonObject d = new JsonObject();
        if (seq == null) {
            send(GatewayPayload.upstream(OpCode.HEARTBEAT.code(), null));
        } else {
            send(GatewayPayload.upstream(OpCode.HEARTBEAT.code(),
                    Json.GSON.toJsonTree(seq)));
        }
    }

    private void onDispatch(GatewayPayload payload) {
        if (payload.seq() != null) {
            seq = payload.seq();
        }
        String name = payload.type();
        if (EventType.READY.name().equals(name)) {
            JsonObject d = payload.dataObject();
            if (d.has("session_id")) {
                sessionId = d.get("session_id").getAsString();
            }
            reconnects.set(0);
            setState(State.CONNECTED);
            for (Listener l : listeners) {
                l.onReady(sessionId, d.has("user") ? d.get("user") : null);
            }
            log.info("gateway ready session={} shard=[{},{}]", sessionId, config.shardId(), config.shardCount());
            return;
        }
        if (EventType.RESUMED.name().equals(name)) {
            reconnects.set(0);
            setState(State.CONNECTED);
            for (Listener l : listeners) {
                l.onResumed();
            }
            log.info("gateway session resumed from seq={}", seq);
            return;
        }
        if (eventBus == null) {
            return;
        }
        eventBus.dispatch(new QQEvent(payload.id(), payload.op(), payload.seq(), name,
                EventType.from(name), payload.data()));
    }

    private void send(String text) {
        WebSocket ws = socket;
        if (ws == null) {
            log.debug("dropping upstream frame, socket not ready: {}", text);
            return;
        }
        if (!ws.send(text)) {
            log.warn("gateway refused the frame queue, reconnecting");
            dropForReconnect(new WsException(4008, "send queue full"));
        }
    }

    /** Ask the gateway to reconnect now, keeping the session for RESUME when it is still valid. */
    public void reconnect() {
        dropForReconnect(new WsException(4009, "reconnect requested"));
    }

    /** Drop the stored session, forcing a fresh IDENTIFY on the next connect. */
    public void forgetSession() {
        sessionId = null;
        seq = null;
    }

    private void dropForReconnect(Throwable cause) {
        WebSocket ws = socket;
        if (ws != null) {
            socket = null;
            try {
                ws.cancel();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (cause instanceof WsException wse && wse.fatal()) {
            shutdown(wse);
            return;
        }
        fireError(cause);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        scheduleReconnect(backoffMillis());
    }

    private long backoffMillis() {
        int attempt = reconnects.incrementAndGet();
        long base = config.retryBaseDelay().toMillis();
        long capped = Math.min(config.retryMaxDelay().toMillis(), base << Math.min(10, attempt - 1));
        return capped / 2 + (long) (Math.random() * (capped / 2 + 1));
    }

    private void scheduleReconnect(long delayMillis) {
        if (shuttingDown.get()) {
            return;
        }
        setState(State.RECONNECTING);
        cancelHeartbeat();
        log.info("reconnecting gateway in {}ms (attempt {})", delayMillis, reconnects.get());
        scheduler.schedule(this::open, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        synchronized (this) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
        }
    }

    private void setState(State next) {
        State prev = state;
        if (prev == next) {
            return;
        }
        state = next;
        for (Listener l : listeners) {
            try {
                l.onStateChange(prev, next);
            } catch (RuntimeException e) {
                log.warn("gateway listener threw", e);
            }
        }
    }

    private void fireError(Throwable t) {
        for (Listener l : listeners) {
            try {
                l.onError(t);
            } catch (RuntimeException e) {
                log.warn("gateway listener threw", e);
            }
        }
    }

    private void shutdown(WsException fatal) {
        shuttingDown.set(true);
        cancelHeartbeat();
        setState(State.STOPPED);
        fireError(fatal);
        log.error("gateway stopped: {}", fatal.getMessage());
    }

    private final class SocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, okhttp3.Response response) {
            log.debug("gateway socket open, waiting for hello");
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            GatewayPayload payload;
            try {
                payload = GatewayPayload.parse(text);
            } catch (RuntimeException e) {
                log.warn("unparseable gateway frame: {}", Strings.trimTail(text));
                return;
            }
            handle(ws, payload);
        }

        private void handle(WebSocket ws, GatewayPayload payload) {
            OpCode op = OpCode.from(payload.op());
            if (op == null) {
                log.debug("unknown opcode {} ignored", payload.op());
                return;
            }
            switch (op) {
                case HELLO -> onHello(payload);
                case DISPATCH -> onDispatch(payload);
                case HEARTBEAT -> {
                    // the server may request a beat itself
                    heartbeat();
                }
                case HEARTBEAT_ACK -> {
                    heartbeatAcked = true;
                    long rtt = System.currentTimeMillis() - lastHeartbeatSentAt;
                    for (Listener l : listeners) {
                        l.onHeartbeatAck(rtt);
                    }
                }
                case RECONNECT -> {
                    log.info("gateway asked to reconnect");
                    dropForReconnect(new WsException(4009, "server requested reconnect"));
                }
                case INVALID_SESSION -> {
                    boolean resumable = payload.data() != null && payload.data().isJsonPrimitive()
                            && payload.data().getAsBoolean();
                    if (!resumable) {
                        forgetSession();
                    }
                    log.warn("invalid session (resumable={}), re-authenticating", resumable);
                    dropForReconnect(new WsException(4006, "invalid session"));
                }
                default -> log.debug("opcode {} not handled upstream", op);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            log.debug("gateway closing code={} reason={}", code, reason);
            try {
                ws.close(1000, null);
            } catch (RuntimeException ignored) {
                // peer already gone
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            if (socket != ws) {
                // a callback from a socket we already abandoned; the live one owns reconnection
                return;
            }
            socket = null;
            if (shuttingDown.get()) {
                setState(State.STOPPED);
                return;
            }
            WsException error = new WsException(code, reason);
            if (error.fatal()) {
                shutdown(error);
                return;
            }
            if (code != 1000 && !error.resumable()) {
                // a non-graceful close still allows RESUME while the session id is alive
                log.debug("close code {} will be retried with resume if a session exists", code);
            }
            fireError(error);
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, okhttp3.Response response) {
            if (socket != ws) {
                return;
            }
            socket = null;
            log.debug("gateway socket failure", t);
            if (shuttingDown.get()) {
                setState(State.STOPPED);
                return;
            }
            fireError(t);
            scheduleReconnect();
        }
    }

    /** Close the gateway and stop reconnecting. */
    @Override
    public void close() {
        closeQuietly();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    public void closeQuietly() {
        shuttingDown.set(true);
        cancelHeartbeat();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            // the gateway defines no close handshake, so abort the socket instead of waiting for a peer echo
            try {
                ws.cancel();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        setState(State.STOPPED);
    }

    /** Wait until the gateway reaches {@link State#CONNECTED}, for smoke tests and CLI bots. */
    public boolean awaitConnected(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (state == State.CONNECTED) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return state == State.CONNECTED;
    }
}
