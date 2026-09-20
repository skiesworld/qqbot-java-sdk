package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A dispatch reporting that something happened: a member left, a channel was renamed, a message went through
 * audit. It has a place, and sometimes people, but no text to read and nothing to answer.
 *
 * <p>Who took part is <em>not</em> one accessor, because the payload does not use one key: {@code GROUP_ADD_ROBOT}
 * reports the member who pulled the bot in, {@code GROUP_MEMBER_REMOVE} reports the member who left, and
 * {@code CHANNEL_CREATE} reports nobody in the id space the rest of this class speaks. The two questions that
 * hold across events are answered here, by the key names {@link EventRouting} records per event; anything else
 * is {@link QQEvent#data()}'s named field, which says what it is.
 */
public class QQNoticeEvent extends QQEvent {

    public QQNoticeEvent(String id, int op, Long seq, String name, EventType type, JsonElement data,
            Outbound outbound) {
        super(id, op, seq, name, type, data, outbound);
    }

    /** The events built into this envelope: the notices, and the requests that subclass them. */
    public static List<EventType> eventTypes() {
        List<EventType> types = new ArrayList<>(EventRouting.eventTypes(EventRouting.Envelope.NOTICE));
        for (EventType request : EventRouting.eventTypes(EventRouting.Envelope.REQUEST)) {
            if (!types.contains(request)) {
                types.add(request);
            }
        }
        return types;
    }

    /** Whoever set this off, when the payload names one; empty for the events that report no such person. */
    public Optional<String> actor() {
        return role(EventRouting.of(name()).actorKeys());
    }

    /** Whoever this happened to, when the payload names one. */
    public Optional<String> subject() {
        return role(EventRouting.of(name()).subjectKeys());
    }

    private Optional<String> role(List<String> keys) {
        return Optional.ofNullable(EventValues.firstText(rawObject(), keys));
    }
}
