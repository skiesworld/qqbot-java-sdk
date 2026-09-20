package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import io.github.skiesworld.qqbot.event.EventRouting.Envelope;

import java.util.List;
import java.util.Map;

/**
 * The one place that turns a dispatch into an envelope, so the gateway and the callback endpoint cannot drift
 * apart about what a handler's parameter type means.
 *
 * <p>Which envelope an event name gets is {@link EventRouting}'s generated answer; the two names with an action
 * of their own are the exception, and are listed here because that is the only kind of decision the table cannot
 * carry.
 */
public final class EventEnvelopes {

    private static final Map<String, Class<?>> WITH_ACTION = Map.of(
            "GROUP_JOIN_REQUEST", GroupJoinRequestEvent.class,
            "INTERACTION_CREATE", InteractionEvent.class);

    /** Build the envelope {@code eventName}'s row asks for. */
    public static QQEvent of(String id, int op, Long seq, String eventName, JsonElement data, Outbound outbound) {
        EventType type = EventType.from(eventName);
        Envelope envelope = EventRouting.of(eventName).envelope();
        if (envelope == Envelope.MESSAGE) {
            return new QQMessageEvent(id, op, seq, eventName, type, data, outbound);
        }
        if (envelope == Envelope.REQUEST) {
            Class<?> action = WITH_ACTION.get(eventName);
            if (action == GroupJoinRequestEvent.class) {
                return new GroupJoinRequestEvent(id, op, seq, eventName, type, data, outbound);
            }
            if (action == InteractionEvent.class) {
                return new InteractionEvent(id, op, seq, eventName, type, data, outbound);
            }
        }
        if (envelope == Envelope.NOTICE || envelope == Envelope.REQUEST) {
            return new QQNoticeEvent(id, op, seq, eventName, type, data, outbound);
        }
        return new QQEvent(id, op, seq, eventName, type, data, outbound);
    }

    /**
     * The events a handler parameter of {@code envelopeType} is asking for.
     *
     * @return null for {@link QQEvent} itself and for anything that is not an envelope, both of which mean "any
     *         dispatch", which the caller resolves against the bus's wildcard route
     */
    public static List<EventType> eventTypes(Class<?> envelopeType) {
        if (envelopeType == QQMessageEvent.class) {
            return QQMessageEvent.eventTypes();
        }
        if (envelopeType == QQNoticeEvent.class) {
            return QQNoticeEvent.eventTypes();
        }
        if (envelopeType == GroupJoinRequestEvent.class) {
            return GroupJoinRequestEvent.eventTypes();
        }
        if (envelopeType == InteractionEvent.class) {
            return InteractionEvent.eventTypes();
        }
        return null;
    }

    /** Whether {@code event} was built as {@code envelopeType}, which is how a parameter gets filled or skipped. */
    public static boolean accepts(Class<?> envelopeType, QQEvent event) {
        return envelopeType.isInstance(event);
    }

    private EventEnvelopes() {
    }
}
