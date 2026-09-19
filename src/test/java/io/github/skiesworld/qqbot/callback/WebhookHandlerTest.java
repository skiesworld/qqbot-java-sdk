package io.github.skiesworld.qqbot.callback;

import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.error.SignatureException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP callback mode: address validation, signature enforcement and event fan-out. */
class WebhookHandlerTest {

    private static final String SECRET = "naOC0ocQE3shWLAfffVLB1rhYPG7";
    private static final String APPID = "11111111";

    /** Compute what the platform would send in the two signature headers. */
    private static void sign(String body, String timestamp, Holder holder) {
        holder.signature = SignatureUtil.signHex(SECRET, timestamp + body);
        holder.timestamp = timestamp;
    }

    private static final class Holder {
        String signature;
        String timestamp;
    }

    @Test
    void answersTheValidationChallengeWithASignature() {
        String plainToken = "Arq0D5A61EgUu4OxUvOp";
        String eventTs = "1725442341";
        String body = "{\"d\":{\"plain_token\":\"" + plainToken + "\",\"event_ts\":\"" + eventTs
                + "\"},\"op\":13}";
        Holder h = new Holder();
        sign(body, eventTs, h);

        String response = new WebhookHandler(SECRET, APPID, new EventBus())
                .handle(body, h.timestamp, h.signature, APPID);

        assertTrue(response.contains("\"plain_token\":\"" + plainToken + "\""), response);
        assertEquals(SignatureUtil.signHex(SECRET, eventTs + plainToken),
                response.substring(response.indexOf("\"signature\":\"") + 13, response.length() - 2));
    }

    @Test
    void dispatchesVerifiedEventsAndAcksThem() {
        EventBus bus = new EventBus();
        List<QQEvent> received = new CopyOnWriteArrayList<>();
        bus.on(EventType.C2C_MESSAGE_CREATE, received::add);

        String body = "{\"op\":0,\"s\":42,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"E1\","
                + "\"d\":{\"id\":\"MSG1\",\"content\":\"hello\"}}";
        Holder h = new Holder();
        sign(body, "1700000000", h);

        String ack = new WebhookHandler(SECRET, APPID, bus).handle(body, h.timestamp, h.signature, APPID);
        assertEquals(WebhookHandler.ACK, ack);
        assertEquals(1, received.size());
        assertEquals("E1", received.get(0).id());
        assertEquals(42L, received.get(0).seq());
        assertEquals("hello", received.get(0).rawObject().get("content").getAsString());
    }

    @Test
    void rejectsTamperedBodies() {
        String body = "{\"op\":0,\"t\":\"FRIEND_ADD\",\"d\":{\"openid\":\"U\"}}";
        Holder h = new Holder();
        sign(body, "1700000000", h);
        WebhookHandler handler = new WebhookHandler(SECRET, null, new EventBus());

        String tampered = body.replace("U\"", "EVIL\"");
        assertThrows(SignatureException.class,
                () -> handler.handle(tampered, h.timestamp, h.signature, null));
    }

    @Test
    void rejectsReplayedOrUnsignedRequests() {
        String body = "{\"op\":0,\"t\":\"FRIEND_ADD\",\"d\":{}}";
        Holder h = new Holder();
        sign(body, "1700000000", h);
        WebhookHandler handler = new WebhookHandler(SECRET, null, new EventBus());
        assertThrows(SignatureException.class, () -> handler.handle(body, "1700000001", h.signature, null));
        assertThrows(SignatureException.class, () -> handler.handle(body, h.timestamp, "", null));
        assertThrows(SignatureException.class, () -> handler.handle(body, null, h.signature, null));
    }

    @Test
    void rejectsADifferentBotAppId() {
        String body = "{\"op\":0,\"t\":\"FRIEND_DEL\",\"d\":{}}";
        Holder h = new Holder();
        sign(body, "1700000000", h);
        WebhookHandler handler = new WebhookHandler(SECRET, APPID, new EventBus());
        SignatureException e = assertThrows(SignatureException.class,
                () -> handler.handle(body, h.timestamp, h.signature, "22222222"));
        assertTrue(e.getMessage().contains("appid"), e.getMessage());
    }

    @Test
    void toleratesEventsWithoutADispatchTarget() {
        String body = "{\"op\":12,\"d\":\"\"}";
        Holder h = new Holder();
        sign(body, "1700000000", h);
        String ack = new WebhookHandler(SECRET, null, new EventBus()).handle(body, h.timestamp, h.signature, null);
        assertEquals(WebhookHandler.ACK, ack);
    }
}
