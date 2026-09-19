package io.github.skiesworld.qqbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transport switch: what each setting means, and what is refused before a client is ever built. */
class BotConfigTest {

    @Test
    void websocketIsTheDefaultAndNeedsNoWebhookSettings() {
        BotConfig config = BotConfig.builder("APP").clientSecret("s").build();
        assertSame(BotConfig.Transport.WEBSOCKET, config.transport());
        assertEquals("s", config.botSecret(), "the app secret doubles as the bot secret unless told otherwise");
        assertEquals("0.0.0.0", config.webhookHost());
        assertEquals(8080, config.webhookPort());
        assertEquals("/qq/APP", config.webhookPath(), "the route names the bot, so bots can share a port");
    }

    @Test
    void choosingWebhookIsOneCall() {
        BotConfig config = BotConfig.builder("APP").clientSecret("s").webhook(8443, "/callbacks/qq").build();
        assertSame(BotConfig.Transport.WEBHOOK, config.transport());
        assertEquals(8443, config.webhookPort());
        assertEquals("/callbacks/qq", config.webhookPath());
    }

    @Test
    void aCallbackPathAlwaysReadsTheSameAndDefaultsToTheAppId() {
        assertEquals("/qq", BotConfig.builder("APP").clientSecret("s").webhook(80, "qq").build().webhookPath());
        assertEquals("/qq", BotConfig.builder("APP").clientSecret("s").webhook(80, "/qq/").build().webhookPath());
        assertEquals("/qq/APP", BotConfig.builder("APP").clientSecret("s").webhook(80, null).build().webhookPath());
        assertEquals("/qq/APP", BotConfig.builder("APP").clientSecret("s").webhook(80, "  ").build().webhookPath());
    }

    @Test
    void aWebhookBotNeedsTheKeyItVerifiesWith() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BotConfig.builder("APP").accessToken("token-only").webhook(8080, "/qq").build());
        assertTrue(error.getMessage().contains("botSecret"), error.getMessage());

        assertEquals("separate", BotConfig.builder("APP").accessToken("token-only").botSecret("separate")
                .webhook(8080, "/qq").build().botSecret());
    }

    @Test
    void nonsensePortCombinationsFailAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> BotConfig.builder("APP").clientSecret("s")
                .webhook(70_000, "/qq").build());
        assertThrows(IllegalArgumentException.class, () -> BotConfig.builder("APP").clientSecret("s")
                .webhook(-1, "/qq").build());

        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                () -> BotConfig.builder("APP").clientSecret("s").webhook(8443, "/qq")
                        .transport(BotConfig.Transport.WEBSOCKET).build());
        assertTrue(mixed.getMessage().contains("only meaningful with transport WEBHOOK"), mixed.getMessage());
    }

    @Test
    void toBuilderCarriesTheTransportSettingsAcross() {
        BotConfig original = BotConfig.builder("APP").clientSecret("s").botSecret("other")
                .webhookHost("127.0.0.1").webhook(0, "/hook").build();
        BotConfig copy = original.toBuilder().build();

        assertSame(BotConfig.Transport.WEBHOOK, copy.transport());
        assertEquals("other", copy.botSecret());
        assertEquals("127.0.0.1", copy.webhookHost());
        assertEquals(0, copy.webhookPort(), "port 0 survives, so the OS can keep picking one");
        assertEquals("/hook", copy.webhookPath());
    }
}
