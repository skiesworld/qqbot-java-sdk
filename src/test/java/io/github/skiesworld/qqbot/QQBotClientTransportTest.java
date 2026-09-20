package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.callback.SignatureUtil;
import io.github.skiesworld.qqbot.callback.WebhookHandler;
import io.github.skiesworld.qqbot.callback.WebhookServer;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.handler.On;
import io.github.skiesworld.qqbot.websocket.Intent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the transport switch actually does: the same client, the same bus and the same handler class serve callback
 * traffic instead of a gateway — one bot on its own port, or several bots on one endpoint routed by app id.
 */
class QQBotClientTransportTest {

    private static final String SECRET = "abcdefghijklmnopabcdefghijklmnop";
    private static final String SECOND_SECRET = "zyxwvutsrqponmlkjihgfedcba012345";
    private static final String TS = "1700000000";

    private final List<String> hits = new CopyOnWriteArrayList<>();

    /** A webhook bot whose route comes from its app id, which is what lets several share one endpoint. */
    private static QQBotClient webhookBot(String appId, String secret) {
        return QQBotClient.create(BotConfig.builder(appId).clientSecret(secret)
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .webhook(0, null)
                .build());
    }

    private static int post(int port, String path, String body, String secret, String appId) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("X-Signature-Timestamp", TS)
                        .header("X-Signature-Ed25519", SignatureUtil.signHex(secret, TS + body))
                        .header("X-Bot-Appid", appId)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    @Test
    void startBindsTheCallbackEndpointAndCloseReleasesIt() throws Exception {
        QQBotClient bot = webhookBot("APP", SECRET);
        bot.start();
        int port = bot.webhookServer().port();
        assertTrue(port > 0, "the OS picked one");
        assertEquals(port, bot.webhookServer().port(), "the endpoint is created once");

        assertEquals(200, post(port, "/qq/APP", "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{}}", SECRET, "APP"));
        bot.close();
        assertFalse(bot.webhookServer().isRunning());
        assertThrows(Exception.class,
                () -> post(port, "/qq/APP", "{\"op\":0,\"t\":\"READY\",\"d\":{}}", SECRET, "APP"),
                "nothing is listening any more");
    }

    @Test
    void theSameHandlerClassServesTheCallbackTransport() throws Exception {
        try (QQBotClient bot = webhookBot("APP", SECRET)) {
            bot.handlers().register(new Handlers());
            bot.start();
            assertEquals(200, post(bot.webhookServer().port(), bot.config().webhookPath(),
                    "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"E5\",\"d\":{\"content\":\"总线不变\"}}",
                    SECRET, "APP"));
            assertEquals(List.of("总线不变/E5"), hits);
        }
    }

    @Test
    void severalBotsShareOneEndpointWithoutSharingASecret() throws Exception {
        try (QQBotClient first = webhookBot("APP-A", SECRET);
                QQBotClient second = webhookBot("APP-B", SECOND_SECRET);
                WebhookServer endpoint = new WebhookServer("127.0.0.1", 0).start()) {

            first.handlers().register(new Handlers());
            second.handlers().register(new Handlers());
            endpoint.mount(first).mount(second);
            assertEquals(List.of("/qq/APP-A", "/qq/APP-B"), endpoint.paths().stream().sorted().toList());

            String body = "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"E7\",\"d\":{\"content\":\"各走各的\"}}";
            assertEquals(200, post(endpoint.port(), "/qq/APP-A", body, SECRET, "APP-A"));
            assertEquals(List.of("各走各的/E7"), hits);

            assertEquals(200, post(endpoint.port(), "/qq/APP-B", body, SECOND_SECRET, "APP-B"));
            assertEquals(List.of("各走各的/E7", "各走各的/E7"), hits, "the second bot got its own copy");

            assertEquals(401, post(endpoint.port(), "/qq/APP-B", body, SECRET, "APP-A"),
                    "A's signature and app id mean nothing on B's route");
            assertEquals(2, hits.size(), "the refused request reached neither bus again");
        }
    }

    @Test
    void awaitingGatewayReadyOnACallbackBotSaysWhatToDoInstead() {
        try (QQBotClient bot = webhookBot("APP", SECRET)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> bot.connectAndAwaitReady(100));
            assertTrue(error.getMessage().contains("start()"), error.getMessage());
        }
    }

    @Test
    void theHandlerIsPublicSoAnExistingWebFrameworkCanMountIt() {
        try (QQBotClient bot = webhookBot("APP", SECRET)) {
            bot.handlers().register(new Handlers());
            assertNotNull(bot.webhook());
            assertSame(bot.webhook(), bot.webhook(), "one verifier per client");
            String body = "{\"op\":0,\"t\":\"FRIEND_ADD\",\"d\":{\"openid\":\"U\"}}";
            assertEquals(WebhookHandler.ACK, bot.webhook().handle(body, TS,
                    SignatureUtil.signHex(SECRET, TS + body), "APP"));
            assertEquals(List.of("FRIEND_ADD"), hits);
        }
    }

    @SuppressWarnings("unused")
    class Handlers {

        @On(EventType.C2C_MESSAGE_CREATE)
        public void onMessage(io.github.skiesworld.qqbot.event.model.C2CMessageCreate msg, QQEvent raw) {
            hits.add(msg.content + "/" + raw.id());
        }

        @On(EventType.FRIEND_ADD)
        public void onFriend(QQEvent raw) {
            hits.add(raw.name());
        }
    }
}
