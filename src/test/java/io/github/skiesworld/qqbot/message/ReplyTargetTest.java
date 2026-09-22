package io.github.skiesworld.qqbot.message;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which conversation a dispatch belongs to, and which endpoint a reply to it actually hits. */
class ReplyTargetTest {

    private MockWebServer server;
    private QQBotClient client;

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

    /** 造的和真实工厂（EventEnvelopes）一样：消息类型给 QQMessageEvent，其余给 QQEvent。 */
    private static QQEvent event(String name, String json) {
        JsonObject d = json == null ? null : Json.parseLenient(json).getAsJsonObject();
        EventType type = EventType.from(name);
        return QQMessageEvent.eventTypes().contains(type)
                ? new QQMessageEvent("EVENT1", 0, 1L, name, type, d, null)
                : new QQEvent("EVENT1", 0, 1L, name, type, d);
    }

    @Test
    void theEventNameSaysItFirst() {
        assertEquals(ReplyTarget.C2C, ReplyTarget.of(event("C2C_MESSAGE_CREATE", "{}")));
        assertEquals(ReplyTarget.GROUP, ReplyTarget.of(event("GROUP_AT_MESSAGE_CREATE", "{}")));
        assertEquals(ReplyTarget.GROUP, ReplyTarget.of(event("GROUP_MESSAGE_CREATE", "{}")));
        assertEquals(ReplyTarget.CHANNEL, ReplyTarget.of(event("AT_MESSAGE_CREATE", "{}")));
        assertEquals(ReplyTarget.DIRECT, ReplyTarget.of(event("DIRECT_MESSAGE_CREATE", "{}")));
    }

    @Test
    void anUnknownEventNameIsStillRoutedById() {
        assertEquals(ReplyTarget.GROUP, ReplyTarget.of(event("GROUP_SOMETHING_NEW", "{\"group_openid\":\"G\"}")));
        assertEquals(ReplyTarget.C2C, ReplyTarget.of(event("C2C_SOMETHING_NEW", "{\"openid\":\"U\"}")));
        assertEquals(ReplyTarget.UNKNOWN, ReplyTarget.of(event("READY", null)));
    }

    @Test
    void aChannelWinsItsGuildForTheReply() {
        QQEvent message = event("MESSAGE_CREATE", "{\"guild_id\":\"G9\",\"channel_id\":\"C9\"}");
        assertEquals(ReplyTarget.CHANNEL, ReplyTarget.of(message));
        assertEquals("C9", ReplyTarget.CHANNEL.targetId(message));
        assertEquals(ReplyTarget.DIRECT, ReplyTarget.of(event("DIRECT_MESSAGE_CREATE",
                "{\"guild_id\":\"G9\",\"channel_id\":\"C9\"}")));
        assertEquals("G9", ReplyTarget.DIRECT.targetId(event("DIRECT_MESSAGE_CREATE",
                "{\"guild_id\":\"G9\",\"channel_id\":\"C9\"}")));
    }

    @Test
    void privateAndGroupOpenidsComeFromTheAuthorWhenThePayloadHasNone() {
        QQEvent c2c = event("C2C_MESSAGE_CREATE",
                "{\"author\":{\"user_openid\":\"U7\",\"bot\":false},\"content\":\"hi\"}");
        assertEquals("U7", ReplyTarget.C2C.targetId(c2c));
        assertNull(ReplyTarget.GROUP.targetId(c2c), "a private chat has no group to answer into");
    }

    @Test
    void everySceneRepliesToItsOwnEndpointCarryingTheMessageItAnswers() throws Exception {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setBody("{\"id\":\"SENT\",\"ret\":0}"));
        }
        ReplyTarget.C2C.send(client, event("C2C_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"author\":{\"user_openid\":\"U7\"}}"),
                MessageBuilder.of("pong"), new ReplySequence());
        ReplyTarget.GROUP.send(client, event("GROUP_AT_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"group_openid\":\"G7\"}"),
                MessageBuilder.of("pong"), new ReplySequence());
        ReplyTarget.CHANNEL.send(client, event("AT_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"channel_id\":\"C7\"}"),
                MessageBuilder.of("pong"), new ReplySequence());
        ReplyTarget.DIRECT.send(client, event("DIRECT_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"guild_id\":\"D7\"}"),
                MessageBuilder.of("pong"), new ReplySequence());

        List<RecordedRequest> requests = requests(4);
        assertEquals(List.of("/v2/users/U7/messages", "/v2/groups/G7/messages", "/channels/C7/messages",
                "/dms/D7/messages"), requests.stream().map(RecordedRequest::getPath).toList());
        JsonObject c2c = body(requests.get(0));
        assertEquals(0L, c2c.get("msg_type").getAsLong());
        assertEquals("MSG1", c2c.get("msg_id").getAsString(), "被动回复引的是消息自己的 id（d.id），不是信封的 id");
        assertEquals(1L, c2c.get("msg_seq").getAsLong());
        assertEquals("pong", c2c.get("content").getAsString());
        assertTrue(body(requests.get(1)).has("msg_seq"));
        assertNull(body(requests.get(2)).get("msg_seq"), "the channel body has no msg_seq field at all");
        assertEquals("MSG1", body(requests.get(3)).get("msg_id").getAsString());
    }

    @Test
    void aSecondReplyToTheSameMessageNeedsAnotherSequence() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"S1\"}"));
        server.enqueue(new MockResponse().setBody("{\"id\":\"S2\"}"));
        QQEvent group = event("GROUP_AT_MESSAGE_CREATE", "{\"id\":\"MSG1\",\"group_openid\":\"G7\"}");
        ReplySequence sequences = new ReplySequence();
        ReplyTarget.GROUP.send(client, group, MessageBuilder.of("第一句"), sequences);
        ReplyTarget.GROUP.send(client, group, MessageBuilder.of("第二句"), sequences);
        List<RecordedRequest> requests = requests(2);
        assertEquals(1L, body(requests.get(0)).get("msg_seq").getAsLong());
        assertEquals(2L, body(requests.get(1)).get("msg_seq").getAsLong());
    }

    @Test
    void aMessageEventWithoutAMessageIdIsAnErrorNotAnUnsolicitedSend() throws Exception {
        // 消息事件没有 d.id 是异常，不该悄悄改成主动发送 —— 那会占主动额度，或在用户关掉主动消息时被拒。
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        QQEvent noId = event("GROUP_AT_MESSAGE_CREATE", "{\"group_openid\":\"G7\"}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReplyTarget.GROUP.send(client, noId, MessageBuilder.of("pong"), new ReplySequence()));
        assertTrue(error.getMessage().contains("no message id"), error.getMessage());
        assertEquals(0, server.getRequestCount(), "抛了就不该真的发出去");
    }

    @Test
    void aNonMessageDispatchIsSentAsTheCallerBuiltIt() throws Exception {
        // 交互之类的非消息事件走 event_id（与 msg_id 二选一）—— 这时候不能去动 builder。
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        QQEvent interaction = event("GROUP_ADD_ROBOT", "{\"group_openid\":\"G7\"}");
        ReplyTarget.GROUP.send(client, interaction,
                MessageBuilder.of("欢迎").replyToEvent(interaction), new ReplySequence());

        JsonObject body = body(requests(1).get(0));
        assertEquals(interaction.eventId(), body.get("event_id").getAsString(), "event_id 要用信封的 id");
        assertFalse(body.has("msg_id"), "非消息事件不该冒出一个 msg_id：" + body);
        assertFalse(body.has("msg_seq"), body.toString());
    }

    @Test
    void replyToRejectsANonMessageEventAndPointsAtReplyToEvent() {
        QQEvent interaction = event("GROUP_ADD_ROBOT", "{\"group_openid\":\"G7\",\"id\":\"EVENT-ISH\"}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MessageBuilder.of("x").replyTo(interaction));
        assertTrue(error.getMessage().contains("replyToEvent"), error.getMessage());
    }

    @Test
    void anEventWithNoConversationSaysSoInsteadOfGuessing() {
        QQEvent friendAdd = event("FRIEND_ADD", "{\"openid\":\"U7\"}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReplyTarget.UNKNOWN.send(client, friendAdd, MessageBuilder.of("?"), new ReplySequence()));
        assertTrue(error.getMessage().contains("identifies no conversation"), error.getMessage());
    }

    private List<RecordedRequest> requests(int count) throws InterruptedException {
        List<RecordedRequest> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(server.takeRequest());
        }
        return out;
    }

    private static JsonObject body(RecordedRequest request) {
        return Json.parseLenient(request.getBody().readUtf8()).getAsJsonObject();
    }
}
