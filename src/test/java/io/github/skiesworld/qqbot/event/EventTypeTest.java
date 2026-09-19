package io.github.skiesworld.qqbot.event;

import io.github.skiesworld.qqbot.websocket.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Event naming: documented payloads resolve to models, and the intent bits match "事件订阅 Intents". */
class EventTypeTest {

    @Test
    void documentedChannelEventsResolveToTheirDeclaredModels() {
        assertSame(io.github.skiesworld.qqbot.model.Message.class, EventModels.dataClass(EventType.AT_MESSAGE_CREATE, null));
        assertSame(io.github.skiesworld.qqbot.model.Message.class, EventModels.dataClass(EventType.MESSAGE_CREATE, null));
        assertSame(io.github.skiesworld.qqbot.model.Message.class,
                EventModels.dataClass(EventType.DIRECT_MESSAGE_CREATE, null));
        assertSame(io.github.skiesworld.qqbot.model.MessageAudited.class,
                EventModels.dataClass(EventType.MESSAGE_AUDIT_PASS, null));
        assertSame(io.github.skiesworld.qqbot.model.MessageReaction.class,
                EventModels.dataClass(EventType.MESSAGE_REACTION_ADD, null));
    }

    @Test
    void everyEventTypeNameIsItsOwnIdentifier() {
        for (EventType type : EventType.values()) {
            assertEquals(type, EventType.from(type.name()), type.name());
        }
        assertEquals(EventType.UNKNOWN, EventType.from("NOT_YET_DOCUMENTED"));
        assertEquals(EventType.UNKNOWN, EventType.from(null));
    }

    @Test
    void intentBitsMatchTheOfficialTable() {
        assertEquals(1L << 0, Intent.GUILDS.bit());
        assertEquals(1L << 1, Intent.GUILD_MEMBERS.bit());
        assertEquals(1L << 9, Intent.GUILD_MESSAGES.bit());
        assertEquals(1L << 10, Intent.GUILD_MESSAGE_REACTIONS.bit());
        assertEquals(1L << 12, Intent.DIRECT_MESSAGE.bit());
        assertEquals(1L << 24, Intent.GROUP_MEMBER_EVENT.bit());
        assertEquals(1L << 25, Intent.GROUP_AND_C2C_EVENT.bit());
        assertEquals(1L << 26, Intent.INTERACTION.bit());
        assertEquals(1L << 27, Intent.MESSAGE_AUDIT.bit());
        assertEquals(1L << 28, Intent.FORUMS_EVENT.bit());
        assertEquals(1L << 29, Intent.AUDIO_ACTION.bit());
        assertEquals(1L << 30, Intent.PUBLIC_GUILD_MESSAGES.bit());
    }

    @Test
    void maskRoundTripsThroughParse() {
        long mask = Intent.maskOf(Intent.GUILDS, Intent.GROUP_AND_C2C_EVENT, Intent.INTERACTION);
        assertEquals((1L << 0) | (1L << 25) | (1L << 26), mask);
        assertEquals(List.of(Intent.GUILDS, Intent.GROUP_AND_C2C_EVENT, Intent.INTERACTION),
                Intent.parse(mask));
        assertTrue(Intent.GUILD_MEMBERS.isSetIn(Intent.maskOf(Intent.GUILD_MEMBERS)));
    }

    @Test
    void sessionEventsCarryNoIntentAndDocumentedEventsCarryOne() {
        assertNull(EventType.READY.intent());
        assertNull(EventType.RESUMED.intent());
        assertNull(EventType.UNKNOWN.intent());
        for (EventType type : EventType.values()) {
            if (type == EventType.READY || type == EventType.RESUMED || type == EventType.UNKNOWN) {
                continue;
            }
            assertNotSame(null, type.intent(), type.name() + " must declare the intent it needs");
        }
    }
}
