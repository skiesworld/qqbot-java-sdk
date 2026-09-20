package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.QQEvent;

/**
 * A reusable answer to "may this dispatch through".
 *
 * <p>Two ways to write one:
 *
 * <ul>
 *   <li>a lambda or a class implementing {@link #allows}, which sees the dispatch and the bot and nothing else;
 *   <li>a class with a single {@code boolean} method of its own — {@code check(QQMessageEvent msg)},
 *       {@code check(GroupJoinRequestEvent request, QQBotClient bot)} — whose parameters are filled by the same
 *       type rules as a handler's, so the rule asks only for what it actually looks at. A dispatch that cannot
 *       answer for one of them is denied rather than let through, which is what makes
 *       {@code boolean check(QQMessageEvent msg)} read as "only where there is a message to read".
 * </ul>
 *
 * <p>Name the class in {@link On#requires()} or {@link Check#type()} to use it. One that needs configuration —
 * a set of ids, a scene, a threshold — has no no-arg constructor, so hand it to
 * {@link HandlerRegistry#permission(Class, java.util.function.Supplier)} first. The built-in answers are in
 * {@link Permissions}.
 *
 * <p>A gate runs after the method's own arguments have bound, so on a command it is only consulted for messages
 * that actually matched. Denied, throwing and unbindable all mean "not allowed" — a gate that cannot decide must
 * never let the action through — and each is logged.
 */
public interface Permission {

    /**
     * @param bot the client this handler was registered on, null when it was registered on a bare
     *            {@link io.github.skiesworld.qqbot.event.EventBus} and so has none to offer
     */
    boolean allows(QQEvent event, QQBotClient bot);
}
