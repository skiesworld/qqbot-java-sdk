package io.github.skiesworld.qqbot.message;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.media.FileType;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading the payloads the docs publish for GROUP_AT_MESSAGE_CREATE and C2C_MESSAGE_CREATE as segments, and
 * pinning the one thing the wire does not give back: where media sat inside the text.
 */
class MessageSegmentsTest {

    private static MessageSegments parse(String json) {
        JsonObject d = Json.parseLenient(json).getAsJsonObject();
        return MessageSegments.of(new QQEvent("M1", 0, 1L, "GROUP_AT_MESSAGE_CREATE",
                EventType.GROUP_AT_MESSAGE_CREATE, d));
    }

    @Test
    void plainTextIsOneSegmentVerbatim() {
        MessageSegments msg = parse("""
                {"id":"M1","content":" /今日天气 ","group_openid":"G1","message_type":0}""");
        assertEquals(0L, msg.messageType());
        assertEquals(List.of(new Segment.Text(" /今日天气 ")), msg.segments());
        assertEquals(" /今日天气 ", msg.text());
        assertFalse(msg.hasMedia());
    }

    @Test
    void anAttachedImageHasNoPositionInsideTheText() {
        MessageSegments msg = parse("""
                {"id":"M2","content":" 看看这张风景照 ","group_openid":"G1","message_type":0,
                 "attachments":[{"content_type":"image/jpeg","filename":"photo.jpg",
                   "url":"https://multimedia.nt.qq.com.cn/download?appid=x","width":1920,"height":1080,
                   "size":256000}]}""");
        assertEquals(" 看看这张风景照 ", msg.text(), "the content string is exactly what the platform sent");
        assertFalse(msg.text().contains("photo.jpg"), "nothing in the text marks where the image sat");
        assertEquals(1, msg.segmentsOfType(Segment.Text.class).size());
        List<Segment.Media> media = msg.segmentsOfType(Segment.Media.class);
        assertEquals(1, media.size());
        assertEquals(FileType.IMAGE, media.get(0).kind());
        assertEquals(1920L, media.get(0).attachment().width);
        assertEquals("photo.jpg", media.get(0).attachment().filename);
        assertEquals(msg.media(), List.of(media.get(0).attachment()));
    }

    @Test
    void attachmentKindsComeFromTheContentType() {
        MessageSegments msg = parse("""
                {"content":"mixed","message_type":0,"attachments":[
                  {"content_type":"video/mp4","url":"u1"},
                  {"content_type":"voice","url":"u2","voice_wav_url":"w2","asr_refer_text":"打开空调"},
                  {"content_type":"file","filename":"a.pdf","url":"u3"}]}""");
        List<Segment.Media> media = msg.segmentsOfType(Segment.Media.class);
        assertEquals(List.of(FileType.VIDEO, FileType.VOICE, FileType.FILE),
                media.stream().map(Segment.Media::kind).toList());
        assertEquals("打开空调", media.get(1).attachment().asrReferText);
    }

    @Test
    void aCardBecomesOneSegmentAndKeepsItsFields() {
        MessageSegments msg = parse("""
                {"id":"M3","content":"[卡片消息] 小程序\\n摘要: [每日打卡]快来完成今日学习打卡","message_type":3,
                 "ark_data":{"ark_type":"miniapp","ark_name":"小程序","prompt":"打卡",
                   "fields":{"title":"快来完成今日学习打卡","source":"学习助手"}}}""");
        assertEquals(3L, msg.messageType());
        Segment.Card card = msg.segmentsOfType(Segment.Card.class).get(0);
        assertEquals("miniapp", card.data().arkType);
        assertEquals("快来完成今日学习打卡", msg.card().fields.get("title"));
    }

    @Test
    void quotesNestTheirOriginalMessages() {
        MessageSegments msg = parse("""
                {"id":"M4","content":"为什么？","message_type":103,
                 "msg_elements":[{"msg_idx":"REFIDX_a","message_type":0,"content":"原文一",
                    "author":{"id":"U1","username":"甲"}},
                  {"msg_idx":"REFIDX_b","message_type":3,"content":"[卡片] 分享",
                    "ark_data":{"ark_type":"feed","fields":{}}},
                  {"msg_idx":"REFIDX_c","message_type":102,"content":"聊天记录",
                    "msg_elements":[{"message_type":0,"content":"更深一层"}]}]}""");
        List<Segment.Element> elements = msg.elements();
        assertEquals(3, elements.size());
        assertEquals("原文一", elements.get(0).text());
        assertEquals("甲", elements.get(0).author().username);
        assertEquals(3L, elements.get(1).messageType());
        assertEquals("聊天记录", elements.get(2).text());
        assertEquals(List.of("REFIDX_a", "REFIDX_b", "REFIDX_c"),
                elements.stream().map(Segment.Element::msgIdx).toList());
        assertEquals("为什么？原文一[卡片] 分享聊天记录更深一层", msg.allText());
    }

    @Test
    void mentionsAreSegmentsOfTheirOwn() {
        MessageSegments msg = parse("""
                {"content":" 帮帮我","group_openid":"G1",
                 "mentions":[{"id":"U9","username":"助手","bot":true}]}""");
        assertEquals(1, msg.mentions().size());
        assertEquals("助手", msg.mentions().get(0).username);
        assertEquals(Boolean.TRUE, msg.segmentsOfType(Segment.Mention.class).get(0).user().bot);
    }

    @Test
    void anEventThatIsNotAMessageParsesToNothing() {
        assertEquals(List.of(), MessageSegments.of(new QQEvent("E1", 0, 1L, "FRIEND_ADD",
                EventType.FRIEND_ADD, Json.parseLenient("{\"openid\":\"U\"}").getAsJsonObject())).segments());
        assertEquals("", MessageSegments.of(new QQEvent("E2", 0, 1L, "READY", EventType.READY, null)).text());
        assertNull(parse("{\"content\":\"x\"}").card());
    }

    @Test
    void nestedTextIsReachableWithoutRebuildingTheJson() {
        MessageSegments msg = parse("""
                {"content":"顶层","message_type":102,"msg_elements":[{"message_type":0,"content":"一层",
                  "msg_elements":[{"message_type":0,"content":"二层"}]}]}""");
        Segment.Element outer = msg.elements().get(0);
        assertNotNull(outer);
        assertEquals("一层", outer.text());
        assertEquals("顶层一层二层", msg.allText());
    }
}
