package io.github.skiesworld.qqbot.event;

import io.github.skiesworld.qqbot.message.MessageBuilder;

/**
 * The way a dispatch answers back, attached by {@link io.github.skiesworld.qqbot.QQBotClient} to the
 * {@link EventBus} it owns.
 *
 * <p>An envelope built on a bus nobody attached — a bare {@code new EventBus()} in a test, a bus of someone
 * else's — has no {@link QQEvent#outbound()} and so cannot answer; {@link QQMessageEvent#reply(MessageBuilder)}
 * says so instead of dropping the reply somewhere.
 */
public interface Outbound {

    /** Answer {@code event} in the conversation it came from, as a passive reply to it. */
    void reply(QQEvent event, MessageBuilder body);

    /** The OpenAPI calls an event can be answered with, e.g. a join-request verdict or an interaction ack. */
    io.github.skiesworld.qqbot.api.Api api();
}
