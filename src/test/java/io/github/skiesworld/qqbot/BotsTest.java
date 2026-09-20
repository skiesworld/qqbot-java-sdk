package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.callback.WebhookServer;
import io.github.skiesworld.qqbot.error.QQBotException;
import io.github.skiesworld.qqbot.websocket.Intent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Running several bots in one process: lookup by app id, one callback socket for all of them, what closing the
 * registry takes with it, and what one unusable account does to the rest.
 */
class BotsTest {

    private static final String SECRET = "abcdefghijklmnopabcdefghijklmnop";

    private final MockWebServer api = new MockWebServer();

    @BeforeEach
    void startApi() throws Exception {
        api.start();
    }

    @AfterEach
    void stopApi() throws Exception {
        api.close();
    }

    private static QQBotClient websocketBot(String appId) {
        return QQBotClient.create(BotConfig.builder(appId).clientSecret(SECRET)
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .build());
    }

    /** A callback bot whose {@code /users/@me} answers {@code profile}; starting it verifies its credentials. */
    private QQBotClient callbackBot(String appId, int port, String path, String profile) {
        api.enqueue(new MockResponse().setBody(profile));
        return QQBotClient.create(BotConfig.builder(appId).clientSecret(SECRET)
                .accessToken("TOKEN")
                .apiBase(api.url("/").toString())
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .webhook(port, path)
                .build());
    }

    private QQBotClient callbackBot(String appId, int port, String path) {
        return callbackBot(appId, port, path, "{\"id\":\"" + appId + "\",\"username\":\"bot\"}");
    }

    @Test
    void botsAreKeptInRegistrationOrderAndFoundById() {
        QQBotClient a = websocketBot("A");
        QQBotClient b = websocketBot("B");
        try (Bots bots = new Bots().register(a, b)) {
            assertEquals(List.of(a, b), bots.getBots());
            assertSame(a, bots.get("A").orElseThrow());
            assertSame(b, bots.get("B").orElseThrow());
            assertEquals(Optional.empty(), bots.get("NOPE"));
            assertEquals(2, bots.size());
        }
    }

    @Test
    void oneBotIsAddressableWithoutNamingIt() {
        QQBotClient only = websocketBot("A");
        try (Bots bots = new Bots().register(only)) {
            assertSame(only, bots.getBot());
        }
    }

    @Test
    void theShorthandRefusesToGuessBetweenNoneAndSeveral() {
        assertThrows(IllegalStateException.class, () -> new Bots().getBot());

        Bots bots = new Bots().register(websocketBot("A"), websocketBot("B"));
        IllegalStateException error = assertThrows(IllegalStateException.class, bots::getBot);
        assertTrue(error.getMessage().contains("get(appId)"), error.getMessage());
        bots.close();
    }

    @Test
    void anAppIdIsTakenOnceBecauseTwoBotsOnItWouldAnswerTwice() {
        try (Bots bots = new Bots().register(websocketBot("A"))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> bots.register(websocketBot("A")));
            assertTrue(error.getMessage().contains("A"), error.getMessage());
        }
    }

    @Test
    void aBotThatIsRegisteredButNotStartedIsNotOnline() {
        try (Bots bots = new Bots().register(websocketBot("A"))) {
            assertFalse(bots.isOnline("A"));
            assertFalse(bots.isOnline("NOPE"));
        }
    }

    @Test
    void callbackBotsShareOneSocketOnTheirOwnRoutes() throws Exception {
        Bots bots = new Bots().webhookEndpoint("127.0.0.1", 0);
        QQBotClient a = callbackBot("A", 0, "/qq/A");
        QQBotClient b = callbackBot("B", 0, "/qq/B");
        bots.register(a, b).startAll();

        WebhookServer shared = bots.endpoint();
        assertTrue(shared.port() > 0, "the OS bound a port");
        assertTrue(shared.paths().containsAll(List.of("/qq/A", "/qq/B")), shared.paths().toString());
        assertSame(shared, a.webhookServer(), "the bot answers on the shared socket, not one of its own");
        assertSame(shared, b.webhookServer());
        assertTrue(bots.isOnline("A"));

        a.close();
        assertTrue(shared.isRunning(), "closing one bot leaves the socket up for the others");
        assertEquals(java.util.Set.of("/qq/B"), shared.paths());

        bots.close();
        assertFalse(shared.isRunning());
    }

    @Test
    void oneUnusableAccountLeavesTheOthersRunningAndSaysWhichItWas() throws Exception {
        Bots bots = new Bots().webhookEndpoint("127.0.0.1", 0);
        bots.register(callbackBot("GOOD", 0, "/qq/GOOD"),
                callbackBot("BAD", 0, "/qq/BAD", "{\"err_code\":40001,\"message\":\"wrong secret\"}"));

        QQBotException failure = assertThrows(QQBotException.class, bots::startAll);
        assertTrue(failure.getMessage().contains("BAD"), failure.getMessage());
        assertTrue(bots.isOnline("GOOD"), "one bad key does not keep a healthy account offline");
        assertFalse(bots.isOnline("BAD"));
        assertFalse(bots.endpoint().paths().contains("/qq/BAD"),
                "a bot that could not identify itself must not be reachable on the shared socket");
        bots.close();
    }

    @Test
    void aCallbackBotWithoutASharedSocketBindsItsOwn() throws Exception {
        QQBotClient solo = callbackBot("A", 0, "/qq/A");
        try (Bots bots = new Bots().register(solo)) {
            WebhookServer own = solo.webhookServer();
            assertTrue(own.isRunning(), "no shared socket was configured, so this bot binds its own");
            assertEquals(own, solo.webhookServer(), "one endpoint per bot, created once");
        }
        assertFalse(solo.webhookServer().isRunning());
    }
}
