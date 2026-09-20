package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventEnvelopes;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command half of {@link On}: matching text, prefixes, arguments, and what ordering and blocking mean when
 * two commands could answer the same message.
 */
class OnCommandTest {

    private final EventBus bus = new EventBus();
    private final QQBotClient bot = QQBotClient.create(BotConfig.builder("APP").clientSecret("s").build());
    private final HandlerRegistry handlers = new HandlerRegistry(bus, bot);

    private void say(String content) {
        bus.dispatch(EventEnvelopes.of("M1", 0, 1L, "C2C_MESSAGE_CREATE", Json.parseLenient(
                "{\"id\":\"M1\",\"content\":\"" + content + "\",\"user_openid\":\"U1\","
                        + "\"author\":{\"user_openid\":\"U1\"}}"), bot.events().outbound()));
    }

    @Test
    void aWordCommandRunsOnlyForTheMessageThatSaysIt() {
        Commands handler = new Commands();
        handlers.register(handler);

        say("签到");
        say("签到 三天");
        say("签到榜");
        say("别的消息");

        assertEquals(List.of("", "三天"), handler.args);
        assertEquals(List.of("签到", "签到"), handler.words);
    }

    @Test
    void aliasesAreOneCommandAndHelpReportsTheFirstWordAndTheDescription() {
        Commands handler = new Commands();
        handlers.register(handler);

        say("checkin");

        assertEquals(List.of("checkin"), handler.words);
        assertTrue(handlers.describe().contains("checkin | 签到 - 每日签到"), handlers.describe().toString());
    }

    @Test
    void aRegexMatchHandsOverItsCaptureGroups() {
        Commands handler = new Commands();
        handlers.register(handler);

        say("mute 60 tom");
        say("mute notanumber tom");

        assertEquals(List.of("60"), handler.seconds);
        assertEquals(List.of("tom"), handler.targets);
    }

    @Test
    void anEmptyTextMatchesNothing() {
        Commands handler = new Commands();
        handlers.register(handler);

        say("   ");
        assertEquals(0, handler.args.size());
    }

    @Test
    void aConfiguredPrefixFiltersWhatHasNone() {
        handlers.usePrefixes("/");
        Prefixed handler = new Prefixed();
        handlers.register(handler);

        say("无前缀");
        say("/ping");

        assertEquals(List.of("ping"), handler.matched);
    }

    @Test
    void aCommandCanPinItsOwnPrefixAgainstTheRegistryDefault() {
        handlers.usePrefixes("");
        Prefixed handler = new Prefixed();
        handlers.register(handler);

        say("ping");
        say("!ping");

        assertEquals(List.of("ping"), handler.banged);
    }

    @Test
    void aBlockingCommandKeepsTheMessageFromTheRoutesBehindIt() {
        Chain handler = new Chain();
        handlers.register(handler);

        say("独占");
        say("别的");

        assertEquals(List.of("独占:blocked", "别的:both"), handler.log);
    }

    @Test
    void priorityDecidesWhoSeesItFirst() {
        Ordered handler = new Ordered();
        handlers.register(handler);

        say("你好");

        assertEquals(List.of("logger", "replier"), handler.log);
    }

    @Test
    void aCommandOnAnEventWithoutTextCouldNeverMatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new CommandOnANotice()));
        assertTrue(error.getMessage().contains("carries no message"), error.getMessage());
    }

    @Test
    void anUncompilablePatternIsAStartupFailureNotASilentDeadCommand() {
        assertThrows(IllegalArgumentException.class, () -> handlers.register(new BadPattern()));
    }

    @Test
    void aCommandListenedToOnRawNamesHasNoTextToMatch() {
        assertThrows(IllegalArgumentException.class, () -> handlers.register(new CommandOnAName()));
    }

    static class Commands {

        final List<String> words = new java.util.ArrayList<>();
        final List<String> args = new java.util.ArrayList<>();
        final List<String> seconds = new java.util.ArrayList<>();
        final List<String> targets = new java.util.ArrayList<>();

        @On(command = {"checkin", "签到"}, description = "每日签到")
        public void checkIn(OnContext ctx) {
            words.add(ctx.command());
            args.add(ctx.rest());
        }

        @On(command = "mute (\\d+) (\\S+)", kind = On.Kind.REGEX)
        public void mute(OnContext ctx) {
            seconds.add(ctx.groups().get(0));
            targets.add(ctx.groups().get(1));
        }
    }

    static class Prefixed {

        final List<String> matched = new java.util.ArrayList<>();
        final List<String> banged = new java.util.ArrayList<>();

        @On(command = "ping")
        public void ping(OnContext ctx) {
            matched.add(ctx.command());
        }

        @On(command = "ping", prefix = "!")
        public void bangPing(OnContext ctx) {
            banged.add(ctx.command());
        }
    }

    static class Chain {

        final List<String> log = new java.util.ArrayList<>();

        @On(command = "独占", block = true)
        public void blocker(OnContext ctx) {
            log.add("独占:blocked");
        }

        @On(value = EventType.C2C_MESSAGE_CREATE, priority = 1)
        public void every(QQEvent event) {
            log.add(event.rawObject().get("content").getAsString().startsWith("独占")
                    ? "独占:should-not-run" : "别的:both");
        }
    }

    static class Ordered {

        final List<String> log = new java.util.ArrayList<>();

        @On(EventType.C2C_MESSAGE_CREATE)
        public void replier(QQMessageEvent msg) {
            log.add("replier");
        }

        @On(value = EventType.C2C_MESSAGE_CREATE, priority = -1)
        public void logger(QQEvent event) {
            log.add("logger");
        }
    }

    static class CommandOnANotice {

        @On(command = "hi", value = EventType.FRIEND_ADD)
        public void on(OnContext ctx) {
        }
    }

    static class BadPattern {

        @On(command = "mute (\\d+", kind = On.Kind.REGEX)
        public void on(OnContext ctx) {
        }
    }

    static class CommandOnAName {

        @On(command = "hi", name = "GROUP_SOMETHING_NEW")
        public void on(OnContext ctx) {
        }
    }
}
