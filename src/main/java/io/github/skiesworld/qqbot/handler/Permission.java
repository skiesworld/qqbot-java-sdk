package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.QQEvent;

/**
 * A gate in front of one handler method: allowed, or not, for this dispatch.
 *
 * <p>Two ways to write one. As a class here, when the rule is shared across bots or plugins — the built-ins in
 * {@link Permissions}, or your own with a public no-arg constructor, or one registered through
 * {@link HandlerRegistry#permission}. Or as a method on the handler itself, which needs no interface at all:
 * annotate it {@link Check} with no name, let its parameters be filled exactly like a handler's, and return
 * {@code boolean}.
 *
 * <p>A gate runs after the method's own arguments have bound, so on a {@code @Command} it is only consulted for
 * messages that actually matched the command. Denied, throwing and unbindable all mean "not allowed" — a gate that
 * cannot decide must never let the action through — and each is logged.
 */
public interface Permission {

    /**
     * @param bot the client this handler was registered on, null when it was registered on a bare
     *            {@link io.github.skiesworld.qqbot.event.EventBus} and so has none to offer
     */
    boolean allows(QQEvent event, QQBotClient bot);
}
