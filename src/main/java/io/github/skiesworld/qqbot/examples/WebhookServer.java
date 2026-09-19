package io.github.skiesworld.qqbot.examples;

import com.sun.net.httpserver.HttpExchange;
import io.github.skiesworld.qqbot.error.SignatureException;
import io.github.skiesworld.qqbot.callback.WebhookHandler;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 回调模式：用 JDK 自带的 HttpServer 接平台推送，验签、地址校验与事件分发都由
 * {@link WebhookHandler} 完成。回调地址只允许 80 / 443 / 8080 / 8443 端口。
 *
 * <pre>
 *   QQ_BOT_SECRET=... ./gradlew runExample -Pexample=WebhookServer
 *   curl -X POST localhost:8080/qq -d '{"op":13,"d":{"plain_token":"x","event_ts":"1"}}'
 * </pre>
 */
public final class WebhookServer {

    private WebhookServer() {
    }

    public static void main(String[] args) throws IOException {
        String botSecret = Env.required("QQ_BOT_SECRET");
        int port = Integer.getInteger("port", 8080);

        EventBus events = new EventBus();
        events.onAny(event -> Env.log("event %s seq=%s id=%s", event.name(), event.seq(), event.id()));
        events.on(EventType.C2C_MESSAGE_CREATE, event ->
                Env.log("c2c from %s: %s", event.targetId(), event.rawObject().get("content").getAsString()));

        WebhookHandler handler = new WebhookHandler(botSecret, System.getenv("QQ_APP_ID"), events);
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/qq", exchange -> respond(exchange, handler));
        server.setExecutor(null);
        server.start();
        Env.log("listening on http://0.0.0.0:%d/qq", port);
    }

    private static void respond(HttpExchange exchange, WebhookHandler handler) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int status;
        String response;
        try {
            response = handler.handle(body,
                    exchange.getRequestHeaders().getFirst("X-Signature-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Signature-Ed25519"),
                    exchange.getRequestHeaders().getFirst("X-Bot-Appid"));
            status = 200;
        } catch (SignatureException e) {
            Env.log("rejected: %s", e.getMessage());
            response = "{\"err_code\":-1,\"message\":\"signature check failed\"}";
            status = 401;
        }
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }
}
