package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.callback.SignatureUtil;
import io.github.skiesworld.qqbot.callback.WebhookHandler;
import io.github.skiesworld.qqbot.callback.WebhookServer;
import io.github.skiesworld.qqbot.error.ApiException;
import io.github.skiesworld.qqbot.error.QQBotException;
import io.github.skiesworld.qqbot.event.EventEnvelopes;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.handler.On;
import io.github.skiesworld.qqbot.handler.Permissions;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.websocket.Intent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the transport switch actually does: the same client, the same bus and the same handler class serve callback
 * traffic instead of a gateway — one bot on its own port, or several bots on one endpoint routed by app id. A
 * callback bot also answers "who are you" before it binds anything, so a wrong key fails at boot instead of on the
 * first reply a user waited for.
 */
class QQBotClientTransportTest {

    private static final String SECRET = "abcdefghijklmnopabcdefghijklmnop";
    private static final String SECOND_SECRET = "zyxwvutsrqponmlkjihgfedcba012345";
    private static final String TS = "1700000000";

    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final MockWebServer api = new MockWebServer();

    @BeforeEach
    void startApi() throws Exception {
        api.start();
    }

    @AfterEach
    void stopApi() throws Exception {
        api.close();
    }

    /** A webhook bot whose route comes from its app id, with {@code profile} as the answer to its identity call. */
    private QQBotClient webhookBot(String appId, String secret, String profile) {
        api.enqueue(new MockResponse().setBody(profile));
        return QQBotClient.create(BotConfig.builder(appId).clientSecret(secret)
                .accessToken("TOKEN")
                .apiBase(api.url("/").toString())
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .webhook(0, null)
                .build());
    }

    private QQBotClient webhookBot(String appId, String secret) {
        return webhookBot(appId, secret, "{\"id\":\"BOT1\",\"username\":\"回调bot\"}");
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

    @Test
    void startAsksWhoThisBotIsAndKeepsTheAnswer() throws Exception {
        try (QQBotClient bot = webhookBot("APP", SECRET)) {
            assertNull(bot.selfId(), "nothing is known before it starts");

            bot.start();

            assertEquals("BOT1", bot.selfId());
            assertEquals("BOT1", bot.self().id);
            RecordedRequest identity = api.takeRequest();
            assertTrue(identity.getPath().endsWith("/users/@me"), identity.getPath());
            assertEquals("QQBot TOKEN", identity.getHeader("Authorization"));

            bot.start();
            assertEquals(1, api.getRequestCount(), "the identity is asked once and then remembered");
        }
    }

    @Test
    void aBotThatCannotIdentifyItselfNeverListensAndCanTryAgain() throws Exception {
        QQBotClient broken = webhookBot("APP", SECRET,
                "{\"err_code\":40001,\"message\":\"appid or secret wrong\"}");

        ApiException failure = assertThrows(ApiException.class, broken::start);
        assertEquals(40001, failure.errCode());
        assertFalse(broken.isOnline(), "a bot that could not say who it is never got to listen");
        assertNull(broken.selfId());

        api.enqueue(new MockResponse().setBody("{\"id\":\"BOT1\",\"username\":\"回调bot\"}"));
        broken.start();
        assertTrue(broken.isOnline(), "the same client starts once the platform answers");
        assertEquals("BOT1", broken.selfId());
        broken.close();
    }

    @Test
    void aProfileWithoutAnIdIsNotAnIdentity() {
        QQBotClient nameless = webhookBot("APP", SECRET, "{\"username\":\"没有 id\"}");

        QQBotException failure = assertThrows(QQBotException.class, nameless::start);
        assertTrue(failure.getMessage().contains("without a bot id"), failure.getMessage());
        nameless.close();
    }

    @Test
    void aGroupWideMessageIsForThisBotWhenItNamesThisBotsId() throws Exception {
        try (QQBotClient bot = webhookBot("APP", SECRET)) {
            bot.start();

            QQMessageEvent mine = (QQMessageEvent) EventEnvelopes.of("E1", 0, 1L, "GROUP_MESSAGE_CREATE",
                    Json.parseLenient("{\"group_openid\":\"G1\",\"content\":\" 帮助 \","
                            + "\"author\":{\"member_openid\":\"M1\"},\"mentions\":[{\"member_openid\":\"BOT1\"}]}"),
                    bot.events().outbound());
            QQMessageEvent forAnotherBot = (QQMessageEvent) EventEnvelopes.of("E2", 0, 1L, "GROUP_MESSAGE_CREATE",
                    Json.parseLenient("{\"group_openid\":\"G1\",\"content\":\" 帮助 \","
                            + "\"author\":{\"member_openid\":\"M1\"},\"mentions\":[{\"member_openid\":\"OTHER\"}]}"),
                    bot.events().outbound());

            assertTrue(new Permissions.ToMe().allows(mine, bot), "the id READY or /users/@me reported is in there");
            assertFalse(new Permissions.ToMe().allows(forAnotherBot, bot),
                    "a mention of someone else is not an mention of me, and no bot flag makes me guess");
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
