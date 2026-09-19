package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.websocket.Intent;

import java.time.Duration;

/** Shared bootstrap for the runnable examples; credentials come from the environment. */
final class Env {

    private static final String TOKEN = System.getenv("QQ_ACCESS_TOKEN");

    private Env() {
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("set " + name + " (and QQ_APP_SECRET, or QQ_ACCESS_TOKEN)");
        }
        return value;
    }

    /**
     * A client for the examples. {@code QQ_API_BASE} points at a sandbox or a local stub when needed.
     */
    static QQBotClient client(Intent... intents) {
        BotConfig.Builder builder = BotConfig.builder(required("QQ_APP_ID"))
                .intents(intents)
                .retryBaseDelay(Duration.ofMillis(300));
        if (TOKEN != null && !TOKEN.isBlank()) {
            builder.accessToken(TOKEN);
        } else {
            builder.clientSecret(required("QQ_APP_SECRET"));
        }
        String base = System.getenv("QQ_API_BASE");
        if (base != null && !base.isBlank()) {
            builder.apiBase(base);
        }
        return QQBotClient.create(builder.build());
    }

    static void log(String message, Object... args) {
        System.out.println("[example] " + String.format(message.replace("{}", "%s"), args));
    }
}
