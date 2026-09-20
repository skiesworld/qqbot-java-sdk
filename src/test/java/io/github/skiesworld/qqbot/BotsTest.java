package io.github.skiesworld.qqbot;

import io.github.skiesworld.qqbot.callback.WebhookServer;
import io.github.skiesworld.qqbot.websocket.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Running several bots in one process: lookup by app id, one callback socket for all of them, and what closing
 * the registry takes with it.
 */
class BotsTest {

    private static final String SECRET = "abcdefghijklmnopabcdefghijklmnop";

    private static QQBotClient websocketBot(String appId) {
        return QQBotClient.create(BotConfig.builder(appId).clientSecret(SECRET)
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .build());
    }

    private static QQBotClient callbackBot(String appId, int port, String path) {
        return QQBotClient.create(BotConfig.builder(appId).clientSecret(SECRET)
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .webhook(port, path)
                .build());
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
    void broadcastReachesEveryBotAndOneFailureDoesNotStopTheRest() {
        List<String> touched = new java.util.ArrayList<>();
        QQBotClient broken = websocketBot("BAD");
        try (Bots bots = new Bots().register(websocketBot("OK1"), broken, websocketBot("OK2"))) {
            bots.broadcast(bot -> {
                if (bot == broken) {
                    throw new IllegalStateException("bad credentials");
                }
                touched.add(bot.config().appId());
            });
        }
        assertEquals(List.of("OK1", "OK2"), touched);
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
