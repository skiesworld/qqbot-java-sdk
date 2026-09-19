package io.github.skiesworld.qqbot.callback;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.error.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One listening socket, one route per bot.
 *
 * <p>A callback url belongs to a single bot, so N bots do not need N ports: mount each bot's
 * {@link WebhookHandler} at its own path — {@code /qq/{appId}} by default — and the only things that differ per
 * request are which secret verifies it and which bus the event lands on. Verification therefore cannot leak
 * across bots: a payload signed for bot A posted at bot B's route is a 401, and {@code X-Bot-Appid} is checked
 * against the bot that owns the route.
 *
 * <pre>{@code
 * try (WebhookServer endpoint = new WebhookServer("0.0.0.0", 8080).start()) {
 *     endpoint.mount(botA);                            // /qq/<appIdA>
 *     endpoint.mount(botB);                            // /qq/<appIdB>
 *     endpoint.mount("/legacy/qq", anotherVerifier);   // anything that is a WebhookHandler
 * }
 * }</pre>
 *
 * <p>This class is only an HTTP resource: read the body, hand it to the route's handler, turn the outcome into a
 * status. Nothing unverified reaches a bus — a bad signature is a 401, a body that is not a callback object a
 * 400, an unknown path a 404, anything but POST a 405. It speaks plain HTTP; put a reverse proxy in front when the
 * platform needs 443 or 8443, or mount {@link WebhookHandler#handle} in your own web framework instead.
 *
 * <p>Requests are served on a few daemon threads and {@link WebhookHandler#handle} dispatches synchronously, so a
 * bot whose listeners are slow wants a bus built on an {@link java.util.concurrent.Executor} — the platform
 * expects a prompt acknowledgement.
 */
public final class WebhookServer implements AutoCloseable {

    /** Callback bodies are small; this only bounds what a request can make us buffer. */
    public static final int MAX_BODY_BYTES = 1 << 20;

    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);
    private static final int DEFAULT_THREADS = 4;

    private final String host;
    private final int requestedPort;
    private final Executor externalExecutor;

    private final Map<String, WebhookHandler> routes = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService threads;
    private int boundPort = -1;

    /** @param port 0 to let the OS pick one, which {@link #port()} then reports */
    public WebhookServer(String host, int port) {
        this(host, port, null);
    }

    /** @param executor null uses a small daemon pool; pass one to share your application's threads */
    public WebhookServer(String host, int port, Executor executor) {
        this.host = host == null || host.isBlank() ? "0.0.0.0" : host;
        this.requestedPort = port;
        this.externalExecutor = executor;
    }

    /** Bind and start listening. Routes may be added and removed afterwards. */
    public synchronized WebhookServer start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("already listening on " + host + ":" + boundPort);
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
        routes.keySet().forEach(path -> created.createContext(path, this::exchange));
        created.createContext("/", this::unknown);
        Executor given = externalExecutor;
        if (given == null) {
            threads = Executors.newFixedThreadPool(DEFAULT_THREADS, runnable -> {
                Thread t = new Thread(runnable, "qqbot-webhook");
                t.setDaemon(true);
                return t;
            });
            given = threads;
        }
        created.setExecutor(given);
        created.start();
        server = created;
        boundPort = created.getAddress().getPort();
        log.info("webhook endpoint listening on http://{}:{}, routes {}", host, boundPort, paths());
        return this;
    }

    /** The port actually bound, which differs from the requested one only when 0 was asked for. */
    public int port() {
        return boundPort;
    }

    public boolean isRunning() {
        return server != null;
    }

    /** The paths currently served. */
    public Set<String> paths() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(routes.keySet()));
    }

    /**
     * Mount a bot at the path its config carries. The bot keeps no reference to this server, so closing the bot
     * does not pull the socket out from under the other routes — close this server to stop serving.
     */
    public WebhookServer mount(QQBotClient bot) {
        return mount(bot.config().webhookPath(), bot.webhook());
    }

    /** Mount a verifier at {@code path}. */
    public synchronized WebhookServer mount(String path, WebhookHandler handler) {
        String route = normalize(path);
        if (handler == null) {
            throw new IllegalArgumentException("handler is required for " + route);
        }
        if (routes.putIfAbsent(route, handler) != null) {
            throw new IllegalArgumentException(route + " is already mounted");
        }
        if (server != null) {
            server.createContext(route, this::exchange);
        }
        return this;
    }

    /** Stop serving {@code path}; requests to it go back to 404. */
    public synchronized WebhookServer unmount(String path) {
        String route = normalize(path);
        routes.remove(route);
        if (server != null) {
            server.removeContext(route);
        }
        return this;
    }

    @Override
    public synchronized void close() {
        if (server == null) {
            return;
        }
        server.stop(0);
        server = null;
        boundPort = -1;
        routes.clear();
        if (threads != null) {
            threads.shutdownNow();
            try {
                if (!threads.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("webhook threads did not stop within 2s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            threads = null;
        }
    }

    private void unknown(HttpExchange exchange) throws IOException {
        try {
            log.debug("no route for {} {}", exchange.getRequestMethod(), exchange.getRequestURI());
            respond(exchange, 404, "{\"err_code\":-1,\"message\":\"no bot mounted here\"}");
        } finally {
            exchange.close();
        }
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            WebhookHandler handler = routes.get(path);
            if (handler == null) {
                // reachable when the route was removed after the server picked this context
                respond(exchange, 404, "{\"err_code\":-1,\"message\":\"no bot mounted here\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "POST");
                respond(exchange, 405, "{\"err_code\":-1,\"message\":\"POST only\"}");
                return;
            }
            String body = read(exchange.getRequestBody());
            int status;
            String response;
            try {
                response = handler.handle(body,
                        exchange.getRequestHeaders().getFirst("X-Signature-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Signature-Ed25519"),
                        exchange.getRequestHeaders().getFirst("X-Bot-Appid"));
                status = 200;
            } catch (SignatureException e) {
                log.warn("rejected callback on {}: {}", path, e.getMessage());
                response = "{\"err_code\":-1,\"message\":\"signature check failed\"}";
                status = 401;
            } catch (JsonParseException | IllegalStateException e) {
                // the bytes verified but were not a callback object: whoever posted them is at fault
                log.warn("unusable callback body on {}: {}", path, e.getMessage());
                response = "{\"err_code\":-1,\"message\":\"body is not a callback object\"}";
                status = 400;
            } catch (RuntimeException e) {
                log.error("callback handling failed on {}", path, e);
                response = "{\"err_code\":-1,\"message\":\"internal error\"}";
                status = 500;
            }
            respond(exchange, status, response);
        } finally {
            exchange.close();
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("a mount path is required");
        }
        String route = path.trim();
        if (!route.startsWith("/")) {
            route = "/" + route;
        }
        return route.length() == 1 || !route.endsWith("/") ? route : route.substring(0, route.length() - 1);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("callback body over " + MAX_BODY_BYTES + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }
}
