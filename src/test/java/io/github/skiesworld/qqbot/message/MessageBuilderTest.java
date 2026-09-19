package io.github.skiesworld.qqbot.message;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.model.Keyboard;
import io.github.skiesworld.qqbot.model.MessageArk;
import io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest;
import io.github.skiesworld.qqbot.model.request.SendChannelMessageRequest;
import io.github.skiesworld.qqbot.model.request.SendGroupMessageRequest;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lowering one builder onto the three request shapes, and refusing the combinations the wire has no field for. */
class MessageBuilderTest {

    private static JsonObject body(Object request) {
        return Json.GSON.toJsonTree(request).getAsJsonObject();
    }

    @Test
    void textIsMsgTypeZeroAndNothingElse() {
        SendC2CMessageRequest request = MessageBuilder.of("你好，世界").toC2C();
        assertEquals(0L, request.msgType);
        assertEquals("你好，世界", request.content);
        assertEquals(Set.of("msg_type", "content"), keys(body(request)),
                "Gson drops the unset fields, so the wire carries exactly these");
    }

    private static Set<String> keys(JsonObject object) {
        return object.entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    @Test
    void markdownAndMediaPickTheirOwnMsgType() {
        SendGroupMessageRequest markdown = MessageBuilder.create().markdown("**hi**").toGroup();
        assertEquals(2L, markdown.msgType);
        assertEquals("**hi**", markdown.markdown.content);
        assertNull(markdown.content);

        SendGroupMessageRequest media = MessageBuilder.create().media("fvv1:4:abc").toGroup();
        assertEquals(7L, media.msgType);
        assertEquals("fvv1:4:abc", media.media.fileInfo);
        assertEquals(7L, MessageBuilder.create().media("fvv1:4:abc").msgType());
    }

    @Test
    void theLastBodyWinsBecauseOneMessageCarriesOneBody() {
        SendC2CMessageRequest request = MessageBuilder.of("first").markdown("second").toC2C();
        assertEquals(2L, request.msgType);
        assertNull(request.content);
        assertEquals("second", request.markdown.content);
    }

    @Test
    void replyFieldsReplaceEachOtherAndCarryThrough() {
        SendC2CMessageRequest byMessage = MessageBuilder.of("pong").replyTo("M1").seq(3L).toC2C();
        assertEquals("M1", byMessage.msgId);
        assertEquals(3L, byMessage.msgSeq);
        assertNull(byMessage.eventId);

        SendC2CMessageRequest byEvent = MessageBuilder.of("pong").replyTo("M1").replyToEvent("E1").toC2C();
        assertNull(byEvent.msgId, "a reply answers either a message or an event");
        assertEquals("E1", byEvent.eventId);
    }

    @Test
    void quotingAddsTheReferenceAndKeyboardStaysAlongsideTheBody() {
        SendC2CMessageRequest request = MessageBuilder.of("看这个").quote("M0").keyboard(new Keyboard())
                .toC2C();
        assertEquals("M0", request.messageReference.messageId);
        assertEquals(0L, request.msgType);
        assertTrue(body(request).has("keyboard"));
    }

    @Test
    void anEmptyBuilderSaysSoBeforeThePlatformDoes() {
        IllegalStateException empty = assertThrows(IllegalStateException.class, MessageBuilder.create()::toC2C);
        assertTrue(empty.getMessage().contains("nothing to send"), empty.getMessage());
        assertThrows(IllegalStateException.class, MessageBuilder.create()::msgType);
        assertThrows(IllegalStateException.class, MessageBuilder.create()::toChannel);
    }

    @Test
    void cardsOnlyGoToASubChannelAndUploadedFilesOnlyToAChat() {
        MessageBuilder card = MessageBuilder.create().ark(new MessageArk());
        assertNull(card.msgType(), "a card has no msg_type on the channel endpoint");
        assertThrows(IllegalStateException.class, card::toC2C);
        assertThrows(IllegalStateException.class, card::toGroup);
        assertTrue(body(card.toChannel()).has("ark"));

        MessageBuilder upload = MessageBuilder.create().media("fvv1:4:abc");
        assertThrows(IllegalStateException.class, upload::toChannel);
        assertThrows(IllegalStateException.class, MessageBuilder.of("x").keyboard(new Keyboard())::toChannel);
    }

    @Test
    void theGroupAndPrivateFormsShareTheirLowering() {
        MessageBuilder builder = MessageBuilder.of("同一份内容").markdown("换成了 markdown").replyTo("M7").seq(2L);
        JsonObject c2c = body(builder.toC2C());
        JsonObject group = body(builder.toGroup());
        assertEquals(c2c, group);
        SendChannelMessageRequest channel = MessageBuilder.of("文本").replyTo("M7").quote("M6").toChannel();
        assertEquals("文本", channel.content);
        assertEquals("M7", channel.msgId);
        assertEquals("M6", channel.messageReference.messageId);
        assertNull(channel.markdown);
    }
}
