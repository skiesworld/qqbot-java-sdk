package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.util.Json;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an envelope promises about a dispatch: the shape it has, the people it can name, and the call its own
 * answer turns into.
 */
class EnvelopeTest {

    private final MockWebServer server = new MockWebServer();
    private QQBotClient bot;

    @BeforeEach
    void startBot() throws IOException {
        server.start();
        bot = QQBotClient.create(BotConfig.builder("APP").accessToken("TOKEN")
                .apiBase(server.url("/").toString())
                .build());
    }

    @AfterEach
    void stopBot() throws IOException {
        bot.close();
        server.close();
    }

    private QQEvent dispatch(String name, String payload) {
        return EventEnvelopes.of("E1", 0, 1L, name, Json.parseLenient(payload), bot.events().outbound());
    }

    @Test
    void aGroupMessageReadsAsOneTurnOfAConversation() {
        QQMessageEvent msg = assertInstanceOf(QQMessageEvent.class, dispatch("GROUP_AT_MESSAGE_CREATE",
                "{\"id\":\"MSG1\",\"content\":\" /签到 \",\"group_openid\":\"GROUP1\","
                        + "\"author\":{\"member_openid\":\"MEMBER1\",\"member_role\":\"admin\"}}"));

        assertEquals(" /签到 ", msg.content());
        assertEquals("GROUP1", msg.conversationId());
        assertEquals("MEMBER1", msg.senderId());
        assertEquals("admin", msg.author().memberRole);
        assertEquals(ReplyTarget.GROUP, msg.scene());
        assertEquals(" /签到 ", msg.segments().text());
    }

    @Test
    void replyGoesBackToTheConversationItCameFromWithAFreshSeq() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        QQMessageEvent msg = (QQMessageEvent) dispatch("C2C_MESSAGE_CREATE",
                "{\"id\":\"MSG1\",\"content\":\"hi\",\"user_openid\":\"USER1\"}");

        msg.reply("pong");
        msg.reply(MessageBuilder.of("again"));

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertTrue(first.getPath().endsWith("/v2/users/USER1/messages"), first.getPath());
        assertTrue(second.getBody().readUtf8().contains("\"msg_seq\":2"), second.getBody().readUtf8());
    }

    @Test
    void aNoticeNamesItsPeopleByTheRoleTheyPlayed() {
        QQNoticeEvent left = (QQNoticeEvent) dispatch("GROUP_MEMBER_REMOVE",
                "{\"group_openid\":\"GROUP1\",\"member_openid\":\"MEMBER1\",\"user_openid\":\"MEMBER1\"}");
        assertEquals(Optional.of("MEMBER1"), left.subject());
        assertEquals(Optional.empty(), left.actor(), "the docs do not say who removed the member");

        QQNoticeEvent robotJoined = (QQNoticeEvent) dispatch("GROUP_ADD_ROBOT",
                "{\"group_openid\":\"GROUP1\",\"op_member_openid\":\"INVITER\"}");
        assertEquals(Optional.of("INVITER"), robotJoined.actor());
        assertEquals(Optional.empty(), robotJoined.subject());

        QQNoticeEvent channel = (QQNoticeEvent) dispatch("CHANNEL_CREATE",
                "{\"id\":\"123\",\"guild_id\":\"456\",\"owner_id\":\"789\",\"op_user_id\":\"789\"}");
        assertEquals(Optional.empty(), channel.actor(), "owner_id is a guild id, not an openid");
        assertEquals("456", channel.conversationId());
    }

    @Test
    void aJoinRequestAnswersItself() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        GroupJoinRequestEvent request = (GroupJoinRequestEvent) dispatch("GROUP_JOIN_REQUEST",
                "{\"group_openid\":\"GROUP1\",\"member_openid\":\"APPLICANT\",\"join_request_id\":\"REQ1\","
                        + "\"verify_info\":{\"verify_message\":\"口令是关键词\"}}");

        assertEquals("REQ1", request.joinRequestId());
        assertEquals("APPLICANT", request.applicant());
        assertEquals("口令是关键词", request.verifyMessage());
        request.approve();

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertTrue(recorded.getPath().contains("/v2/groups/GROUP1/approval_join_request/APPLICANT"),
                recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"op\":\"approve\""));
    }

    @Test
    void aDeniedJoinRequestCarriesTheReasonTheApplicantSees() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        GroupJoinRequestEvent request = (GroupJoinRequestEvent) dispatch("GROUP_JOIN_REQUEST",
                "{\"group_openid\":\"GROUP1\",\"member_openid\":\"APPLICANT\",\"join_request_id\":\"REQ1\"}");

        request.deny("请先看群公告", true);

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"op\":\"decline\""), body);
        assertTrue(body.contains("\"reject_reason\":\"请先看群公告\""), body);
        assertTrue(body.contains("\"add_to_member_blacklist\":true"), body);
    }

    @Test
    void anInteractionAcknowledgesItself() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ret\":0}"));
        InteractionEvent interaction = (InteractionEvent) dispatch("INTERACTION_CREATE",
                "{\"id\":\"INTERACTION1\",\"type\":11,\"user_openid\":\"USER1\",\"group_openid\":\"GROUP1\"}");

        assertEquals("INTERACTION1", interaction.interactionId());
        assertEquals(11L, interaction.interactionType());
        assertEquals(Optional.of("USER1"), interaction.actor());
        assertEquals("GROUP1", interaction.conversationId());
        interaction.acknowledge();

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getPath().contains("/interactions/INTERACTION1"), recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"code\":1"));
    }

    @Test
    void anUnmodelledEventStillRoutesButStaysOnTheBaseEnvelope() {
        QQEvent future = dispatch("GROUP_SOMETHING_NEW", "{\"group_openid\":\"GROUP1\"}");

        assertEquals(QQEvent.class, future.getClass());
        assertEquals(EventType.UNKNOWN, future.type());
        assertEquals("GROUP_SOMETHING_NEW", future.name());
        assertEquals("GROUP1", future.conversationId());
        assertTrue(future.raw().isJsonObject());
    }

    @Test
    void anEnvelopeFromABusNobodyAttachedCannotAnswerItself() {
        QQEvent bare = EventEnvelopes.of("E1", 0, 1L, "C2C_MESSAGE_CREATE",
                new JsonObject(), null);
        QQMessageEvent msg = assertInstanceOf(QQMessageEvent.class, bare);

        assertThrows(IllegalStateException.class, () -> msg.reply("pong"));
    }
}
