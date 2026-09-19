package io.github.skiesworld.qqbot.command;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.handler.BotEvent;
import io.github.skiesworld.qqbot.message.Segment;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.websocket.Intent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matching the way the platform actually delivers: a group message arrives with its {@code @机器人} mention
 * already stripped, so the leading space in the samples below is part of the payload, not a typo.
 */
class CommandRegistryTest {

    private MockWebServer server;
    private QQBotClient client;
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = QQBotClient.create(BotConfig.builder("APP").accessToken("TOKEN")
                .apiBase("http://" + server.getHostName() + ":" + server.getPort())
                .maxRetries(0).retryBaseDelay(Duration.ofMillis(1))
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.close();
    }

    private static QQEvent message(String name, String content, String memberRole) {
        String role = memberRole == null ? "" : "\"member_role\":\"" + memberRole + "\",";
        return event(name, "{\"id\":\"MSG1\",\"author\":{" + role + "\"username\":\"小明\",\"bot\":false,"
                + "\"user_openid\":\"U7\",\"member_openid\":\"M7\"},\"group_openid\":\"G7\""
                + ",\"content\":\"" + content + "\",\"message_type\":0}");
    }

    private static QQEvent event(String name, String json) {
        return new QQEvent("MSG1", 0, 1L, name, EventType.from(name), Json.parseLenient(json).getAsJsonObject());
    }

    private void dispatch(QQEvent event) {
        client.events().dispatch(event);
    }

    @Test
    void aBareWordMatchesWithoutAnyPrefixAndTakesTheRestAsArguments() {
        client.commands().register(new WeatherCommands());
        dispatch(message("GROUP_AT_MESSAGE_CREATE", " 签到", null));
        dispatch(message("C2C_MESSAGE_CREATE", "签到 三天 连续", null));
        dispatch(message("C2C_MESSAGE_CREATE", "签到指南", null));
        assertEquals(List.of("checkin:|", "checkin:三天 连续|三天,连续"), ran,
                "a command word needs whitespace or the end of the message after it");
    }

    @Test
    void aliasesAndSeveralWordsShareOneMethod() {
        client.commands().register(new WeatherCommands());
        dispatch(message("C2C_MESSAGE_CREATE", "checkin now", null));
        dispatch(message("C2C_MESSAGE_CREATE", "weather 上海", null));
        assertEquals(List.of("checkin:now|now", "weather:上海|上海"), ran);
    }

    @Test
    void aConfiguredPrefixIsNeededAndThenDropped() {
        client.commands().usePrefixes("/", "#").register(new WeatherCommands());
        dispatch(message("GROUP_AT_MESSAGE_CREATE", " 签到", null));
        assertTrue(ran.isEmpty(), "no prefix, no command");
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "/签到 今天", null));
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "#todayweather 上海", null));
        assertEquals(List.of("checkin:今天|今天", "weather:上海|上海"), ran);
    }

    @Test
    void regexCommandsHandOverTheirCaptureGroups() {
        client.commands().usePrefixes("").register(new AdminCommands());
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "mute 60 @tom", "owner"));
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "请 mute 60 @tom", "owner"));
        assertEquals(List.of("mute:60|@tom"), ran, "the pattern has to cover the whole message");
    }

    @Test
    void roleGatesApplyWhereThePlatformReportsARole() {
        client.commands().register(new AdminCommands());
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "mute 60 @tom", "member"));
        assertTrue(ran.isEmpty(), "a plain member may not mute");
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "mute 60 @tom", "admin"));
        dispatch(message("C2C_MESSAGE_CREATE", "mute 60 @tom", null));
        assertEquals(List.of("mute:60|@tom", "mute:60|@tom"), ran,
                "a private chat reports no role, so the gate does not apply there");
    }

    @Test
    void aMediaOnlyMessageMatchesNothingButItsSegmentsAreReadable() {
        client.commands().register(new WeatherCommands());
        client.commands().register(new MediaAware());
        dispatch(event("C2C_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"content\":\"  \",\"message_type\":0,"
                + "\"attachments\":[{\"content_type\":\"image/png\",\"url\":\"https://x/y.png\"}]}"));
        assertTrue(ran.isEmpty(), "an image with no text is not a command");
        dispatch(event("C2C_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"content\":\"看图\","
                + "\"attachments\":[{\"content_type\":\"image/png\",\"url\":\"https://x/y.png\"}]}"));
        assertEquals(List.of("saw:image/png"), ran);
    }

    @Test
    void otherEventsNeverReachACommand() {
        client.commands().register(new WeatherCommands());
        dispatch(event("FRIEND_ADD", "{\"openid\":\"U7\"}"));
        dispatch(event("GROUP_AT_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"content\":\"签到\"}"));
        assertEquals(List.of("checkin:|"), ran);
    }

    @Test
    void replyGoesBackWhereTheCommandCameFromWithAFreshSequence() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"S1\",\"ret\":0}"));
        server.enqueue(new MockResponse().setBody("{\"id\":\"S2\",\"ret\":0}"));
        client.commands().register(new PingCommands());
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "ping", null));
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "ping", null));

        List<RecordedRequest> requests = List.of(server.takeRequest(), server.takeRequest());
        assertEquals("/v2/groups/G7/messages", requests.get(0).getPath());
        JsonObject first = Json.parseLenient(requests.get(0).getBody().readUtf8()).getAsJsonObject();
        JsonObject second = Json.parseLenient(requests.get(1).getBody().readUtf8()).getAsJsonObject();
        assertEquals("MSG1", first.get("msg_id").getAsString());
        assertEquals(1L, first.get("msg_seq").getAsLong());
        assertEquals(2L, second.get("msg_seq").getAsLong(), "the same msg_id needs a new seq to be accepted");
        assertEquals("pong", first.get("content").getAsString());
        assertEquals(0L, first.get("msg_type").getAsLong());
    }

    @Test
    void theContextCarriesTheMatchTheSenderAndTheScene() {
        client.commands().register(new ContextCommands());
        dispatch(message("GROUP_AT_MESSAGE_CREATE", "who 一句 两声", null));
        assertEquals(List.of("who|who 一句 两声|MSG1|GROUP|小明|null|一句,两声"), ran,
                "text keeps everything after the prefix, args() splits what came after the word");
    }

    @Test
    void describeListsWhatWasRegistered() {
        client.commands().usePrefixes("/").register(new AdminCommands());
        client.commands().register(new WeatherCommands());
        assertEquals(List.of("mute (\\d+) (\\S+) [prefix /] [ADMIN] - 禁言某人",
                "todayweather | weather [prefix /]",
                "签到 | checkin | 今日天气 [prefix /] - 每日签到"),
                client.commands().describe().stream().sorted().toList());
    }

    @Test
    void refusesWhatCouldNeverMatch() {
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                () -> client.commands().register(new Conflicting()));
        assertTrue(both.getMessage().contains("both @Command and @BotEvent"), both.getMessage());

        IllegalArgumentException noContext = assertThrows(IllegalArgumentException.class,
                () -> client.commands().register(new NoContext()));
        assertTrue(noContext.getMessage().contains("takes no CommandContext"), noContext.getMessage());

        IllegalArgumentException badPattern = assertThrows(IllegalArgumentException.class,
                () -> client.commands().register(new BadRegex()));
        assertTrue(badPattern.getMessage().contains("does not compile"), badPattern.getMessage());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> client.commands().register(new Object()));
        assertTrue(none.getMessage().contains("no Command methods"), none.getMessage());
    }

    @Test
    void aSubscriptionClosesTheCommandsItCreated() {
        var subscription = client.commands().register(new WeatherCommands());
        subscription.close();
        dispatch(message("C2C_MESSAGE_CREATE", "签到", null));
        assertTrue(ran.isEmpty());
        assertEquals(0, client.events().listenerCount(), "closing unregistered every route it added");
    }

    @SuppressWarnings("unused")
    class WeatherCommands {

        @Command(value = "签到", alias = {"checkin", "今日天气"}, description = "每日签到")
        public void checkIn(CommandContext ctx) {
            ran.add("checkin:" + ctx.rest() + "|" + String.join(",", ctx.args()));
        }

        @Command(value = "todayweather", alias = "weather")
        public void weather(CommandContext ctx) {
            ran.add("weather:" + ctx.rest() + "|" + String.join(",", ctx.args()));
        }
    }

    @SuppressWarnings("unused")
    class AdminCommands {

        @Command(value = "mute (\\d+) (\\S+)", kind = Command.Kind.REGEX, role = Role.ADMIN,
                description = "禁言某人")
        public void mute(CommandContext ctx) {
            ran.add("mute:" + ctx.groups().get(0) + "|" + ctx.groups().get(1));
        }
    }

    @SuppressWarnings("unused")
    class MediaAware {

        @Command("看图")
        public void picture(CommandContext ctx) {
            ran.add("saw:" + ctx.segments().segmentsOfType(Segment.Media.class).get(0).attachment().contentType);
        }
    }

    @SuppressWarnings("unused")
    class PingCommands {

        @Command("ping")
        public void ping(CommandContext ctx) {
            ctx.reply("pong");
        }

    }

    @SuppressWarnings("unused")
    class ContextCommands {

        @Command("who")
        public void who(CommandContext ctx) {
            ran.add(ctx.command() + "|" + ctx.text() + "|" + ctx.messageId() + "|" + ctx.scene() + "|"
                    + ctx.author().username + "|" + ctx.role() + "|" + String.join(",", ctx.args()));
        }
    }

    @SuppressWarnings("unused")
    class Conflicting {

        @Command("x")
        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void both(CommandContext ctx) {
            ran.add("never");
        }
    }

    @SuppressWarnings("unused")
    class NoContext {

        @Command("y")
        public void noContext(QQEvent event) {
            ran.add("never");
        }
    }

    @SuppressWarnings("unused")
    class BadRegex {

        @Command(value = "mute (\\d+", kind = Command.Kind.REGEX)
        public void broken(CommandContext ctx) {
            ran.add("never");
        }
    }
}
