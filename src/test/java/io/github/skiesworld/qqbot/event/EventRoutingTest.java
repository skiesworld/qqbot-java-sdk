package io.github.skiesworld.qqbot.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated routing table: it must place every event the gateway can push, and the roles it names must be
 * keys the official payloads actually carry.
 */
class EventRoutingTest {

    @Test
    void everyEventTypeNameHasARow() {
        List<String> missing = new ArrayList<>();
        for (EventType type : EventType.values()) {
            if (type == EventType.UNKNOWN) {
                continue;
            }
            if ("UNKNOWN".equals(EventRouting.of(type.name()).name())) {
                missing.add(type.name());
            }
        }
        assertEquals(List.of(), missing, "add a row to naming.json eventRoles for the events above");
    }

    @Test
    void theMessageEnvelopeIsExactlyTheEventsThatCarryText() {
        assertEquals(List.of(EventType.C2C_MESSAGE_CREATE, EventType.GROUP_AT_MESSAGE_CREATE,
                EventType.GROUP_MESSAGE_CREATE, EventType.AT_MESSAGE_CREATE, EventType.MESSAGE_CREATE,
                EventType.DIRECT_MESSAGE_CREATE), EventRouting.eventTypes(EventRouting.Envelope.MESSAGE));
        for (EventType type : EventRouting.eventTypes(EventRouting.Envelope.MESSAGE)) {
            assertTrue(QQMessageEvent.eventTypes().contains(type), type + " builds a message envelope");
        }
    }

    @Test
    void requestsAreNoticesForEveryoneWhoListensToNotices() {
        List<EventType> requests = EventRouting.eventTypes(EventRouting.Envelope.REQUEST);
        assertEquals(List.of(EventType.GROUP_JOIN_REQUEST, EventType.INTERACTION_CREATE), requests);
        assertTrue(QQNoticeEvent.eventTypes().containsAll(requests),
                "a handler asking for every notice also receives the ones that owe an answer");
        assertSame(EventRouting.Envelope.PLAIN, EventRouting.of("GROUP_SOMETHING_NEW").envelope(),
                "an unmodelled name stays on the base envelope");
    }

    @Test
    void sessionLifecycleIsNotAConversation() {
        assertSame(EventRouting.Envelope.PLAIN, EventRouting.of("READY").envelope());
        assertSame(EventRouting.Envelope.PLAIN, EventRouting.of("RESUMED").envelope());
        assertEquals(EventRouting.Envelope.PLAIN, EventRouting.of(null).envelope());
    }

    @Test
    void thePersonRolesAreTheKeysTheOfficialPayloadsUse() {
        assertEquals(List.of("member_openid"), EventRouting.of("GROUP_JOIN_REQUEST").actorKeys());
        assertEquals(List.of("member_openid"), EventRouting.of("GROUP_MEMBER_REMOVE").subjectKeys());
        assertEquals(List.of("op_member_openid"), EventRouting.of("GROUP_ADD_ROBOT").actorKeys());
        assertEquals(List.of("openid"), EventRouting.of("FRIEND_ADD").actorKeys());
        assertEquals(List.of("user_openid", "group_member_openid"),
                EventRouting.of("INTERACTION_CREATE").actorKeys());
        assertEquals(List.of(), EventRouting.of("MESSAGE_CREATE").actorKeys());
    }

    /**
     * The guild-side ids are a different space from the openids every allowlist is built from, so the table names
     * no person for those events rather than handing back something that cannot be compared.
     */
    @Test
    void guildSpaceIdsAreNotOfferedAsPeople() {
        for (String name : List.of("GUILD_CREATE", "CHANNEL_UPDATE", "MESSAGE_REACTION_ADD", "GUILD_MEMBER_ADD")) {
            assertEquals(List.of(), EventRouting.of(name).actorKeys(), name);
            assertEquals(List.of(), EventRouting.of(name).subjectKeys(), name);
        }
    }
}
