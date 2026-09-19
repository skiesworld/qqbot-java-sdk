package io.github.skiesworld.qqbot.callback;

import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The callback endpoint over a real socket on a free localhost port: what a route accepts, what it refuses, and how
 * two bots sharing one port stay apart. Nothing here talks to QQ.
 */
class WebhookServerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210";
    private static final String APP_ID = "APP";
    private static final String ROUTE = "/qq/" + APP_ID;
    private static final String TS = "1700000000";

    private final List<QQEvent> seen = new CopyOnWriteArrayList<>();
    private final List<QQEvent> otherSeen = new CopyOnWriteArrayList<>();
    private WebhookServer server;
    private String base;
    private HttpClient http;

    @BeforeEach
    void startEndpoint() throws IOException {
        server = new WebhookServer("127.0.0.1", 0).start();
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopEndpoint() {
        server.close();
    }

    private void mountOneBot() {
        server.mount(ROUTE, new WebhookHandler(SECRET, APP_ID, collecting(seen)));
    }

    private static EventBus collecting(List<QQEvent> sink) {
        EventBus bus = new EventBus();
        bus.onAny(sink::add);
        return bus;
    }

    private static String signed(String secret, String body) {
        return SignatureUtil.signHex(secret, TS + body);
    }

    private HttpResponse<String> send(String path, String body, String timestamp, String signature, String appId)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (timestamp != null) {
            builder.header("X-Signature-Timestamp", timestamp);
        }
        if (signature != null) {
            builder.header("X-Signature-Ed25519", signature);
        }
        if (appId != null) {
            builder.header("X-Bot-Appid", appId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> toMountedBot(String body, String signature) throws Exception {
        mountOneBot();
        return send(ROUTE, body, TS, signature, APP_ID);
    }

    @Test
    void acceptsASignedEventAndAnswersTheAck() throws Exception {
        String body = "{\"op\":0,\"s\":7,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"E1\",\"d\":{\"content\":\"你好\","
                + "\"author\":{\"user_openid\":\"U7\"}}}";
        HttpResponse<String> response = toMountedBot(body, signed(SECRET, body));

        assertEquals(200, response.statusCode());
        assertEquals(WebhookHandler.ACK, response.body());
        assertEquals(1, seen.size());
        assertEquals("C2C_MESSAGE_CREATE", seen.get(0).name());
        assertEquals(7L, seen.get(0).seq());
        assertEquals("E1", seen.get(0).id());
        assertEquals("你好", seen.get(0).rawObject().get("content").getAsString());
    }

    @Test
    void aBadOrMissingSignatureNeverReachesTheBus() throws Exception {
        String body = "{\"op\":0,\"t\":\"READY\",\"d\":{}}";
        mountOneBot();
        assertEquals(401, send(ROUTE, body, TS, "beef", APP_ID).statusCode());
        assertEquals(401, send(ROUTE, body, TS, null, APP_ID).statusCode());
        assertEquals(401, send(ROUTE, body, null, signed(SECRET, body), APP_ID).statusCode());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void anotherAppidsCallbackIsRefusedButAnAbsentHeaderIsNot() throws Exception {
        String body = "{\"op\":0,\"t\":\"READY\",\"d\":{}}";
        mountOneBot();
        assertEquals(401, send(ROUTE, body, TS, signed(SECRET, body), "OTHER").statusCode());
        assertTrue(seen.isEmpty());
        // the appid is an extra check on top of the signature, not a replacement for it: without the header a
        // correctly signed body still passes, which is what a bot whose appid is not configured relies on
        assertEquals(200, send(ROUTE, body, TS, signed(SECRET, body), null).statusCode());
    }

    @Test
    void theAddressChallengeIsAnsweredWithoutDispatching() throws Exception {
        String body = "{\"op\":13,\"d\":{\"plain_token\":\"tok-1\",\"event_ts\":\"1699999000\"}}";
        HttpResponse<String> response = toMountedBot(body, signed(SECRET, body));

        assertEquals(200, response.statusCode());
        Map<String, Object> reply = Json.toMap(response.body());
        assertEquals("tok-1", reply.get("plain_token"));
        assertEquals(SignatureUtil.signHex(SECRET, "1699999000tok-1"), reply.get("signature"));
        assertTrue(seen.isEmpty(), "opcode 13 is an address check, not an event");
    }

    @Test
    void onePortServesTwoBotsAndTheirSecretsDoNotMix() throws Exception {
        server.mount("/qq/A", new WebhookHandler(SECRET, "A", collecting(seen)));
        server.mount("/qq/B", new WebhookHandler(OTHER_SECRET, "B", collecting(otherSeen)));

        String body = "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"E2\",\"d\":{\"content\":\"x\"}}";
        assertEquals(200, send("/qq/A", body, TS, signed(SECRET, body), "A").statusCode());
        assertEquals(200, send("/qq/B", body, TS, signed(OTHER_SECRET, body), "B").statusCode());
        assertEquals(List.of("C2C_MESSAGE_CREATE"), names(seen));
        assertEquals(List.of("C2C_MESSAGE_CREATE"), names(otherSeen));

        assertEquals(401, send("/qq/B", body, TS, signed(SECRET, body), "A").statusCode(),
                "A's signature and appid mean nothing on B's route");
        assertEquals(1, names(seen).size(), "the refused request must not reach either bus");
        assertEquals(1, names(otherSeen).size());
    }

    @Test
    void anUnmountedPathIsFourOhFour() throws Exception {
        String body = "{\"op\":0,\"t\":\"READY\",\"d\":{}}";
        assertEquals(404, send("/qq/nobody", body, TS, signed(SECRET, body), null).statusCode());
        mountOneBot();
        assertEquals(404, send("/qq/nobody", body, TS, signed(SECRET, body), null).statusCode(),
                "mounting one bot does not open another");
        server.unmount(ROUTE);
        assertEquals(List.of(), List.copyOf(server.paths()));
        assertEquals(404, send(ROUTE, body, TS, signed(SECRET, body), APP_ID).statusCode());
    }

    @Test
    void mountingNormalizesPathsAndRefusesDuplicates() {
        server.mount("qq/A", new WebhookHandler(SECRET, collecting(seen)));
        server.mount("/qq/B/", new WebhookHandler(SECRET, collecting(seen)));
        assertEquals(List.of("/qq/A", "/qq/B"), server.paths().stream().sorted().toList());

        assertThrows(IllegalArgumentException.class,
                () -> server.mount("/qq/A", new WebhookHandler(OTHER_SECRET, collecting(seen))),
                "a second bot on the same route would silently replace the first");
        assertThrows(IllegalArgumentException.class, () -> server.mount("  ", new WebhookHandler(SECRET, bus())));
    }

    @Test
    void onlyPostIsAccepted() throws Exception {
        mountOneBot();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + ROUTE)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals(List.of("POST"), response.headers().allValues("Allow"));
    }

    @Test
    void aSignedBodyThatIsNotACallbackObjectIsTheCallersFault() throws Exception {
        mountOneBot();
        for (String body : List.of("this is not json at all", "[]", "\"a string\"")) {
            assertEquals(400, send(ROUTE, body, TS, signed(SECRET, body), APP_ID).statusCode(), body);
        }
        assertTrue(seen.isEmpty());
    }

    @Test
    void closingReleasesThePort() throws IOException {
        mountOneBot();
        int port = server.port();
        assertTrue(port > 0, "port 0 was requested, so the OS picked one");
        server.close();
        assertFalse(server.isRunning());
        try (WebhookServer again = new WebhookServer("127.0.0.1", port).start()) {
            assertEquals(port, again.port());
        }
    }

    private static EventBus bus() {
        return new EventBus();
    }

    private static List<String> names(List<QQEvent> events) {
        return events.stream().map(QQEvent::name).toList();
    }
}
