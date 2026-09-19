package io.github.skiesworld.qqbot.event;

import java.util.List;

/**
 * The events whose payload carries message text.
 *
 * <p>One list, two users: command matching listens to exactly these, and {@code @Command(on = ...)} may only
 * narrow within it — a command on an event with no {@code content} could never match anything, which is a bug
 * worth reporting at registration rather than a dead handler discovered in production.
 */
public final class MessageEvents {

    /** Private chat, group (mentioned and not), sub-channel and channel direct messages. */
    public static final List<EventType> WITH_TEXT = List.of(
            EventType.C2C_MESSAGE_CREATE,
            EventType.GROUP_AT_MESSAGE_CREATE,
            EventType.GROUP_MESSAGE_CREATE,
            EventType.AT_MESSAGE_CREATE,
            EventType.MESSAGE_CREATE,
            EventType.DIRECT_MESSAGE_CREATE);

    private MessageEvents() {
    }
}
