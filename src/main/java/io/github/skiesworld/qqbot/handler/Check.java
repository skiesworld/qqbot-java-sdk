package io.github.skiesworld.qqbot.handler;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a routed method behind one or more checks, and doubles as the marker for the checks themselves.
 *
 * <p>On a handler or command method it names what has to allow the call. One line, as many as you need:
 *
 * <pre>{@code
 * @Command("清档") @Check({"groupAdmin", "superUser"})          // 本类里的判定方法，声明顺序即判定顺序
 * public void wipe(CommandContext ctx) { ... }
 *
 * @BotEvent(EventType.GROUP_AT_MESSAGE_CREATE)
 * @Check(type = {Permissions.Group.class, Permissions.GroupAdmin.class})
 * public void onGroup(GroupAtMessageCreate msg) { ... }
 *
 * @Check({"groupAdmin", type = Permissions.Group.class})       // 一条里混用两种
 * public void mixed(QQEvent raw) { ... }
 * }</pre>
 *
 * <p>On a method of its own it <em>declares</em> a check: no name needed, {@code boolean} return, parameters
 * filled by the same type rules as a handler's, so it asks only for what it looks at.
 *
 * <pre>{@code
 * @Check
 * boolean superUser(C2CMessageCreate msg) {
 *     return superUsers.contains(msg.author.userOpenid);
 * }
 * }</pre>
 *
 * <p>Names resolve against the handler's own class and its superclasses, at registration; an unknown name, a
 * method that does not return {@code boolean}, or a {@link #type()} that cannot be instantiated is an error
 * there rather than a silently ungated action. A check that throws denies.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Check {

    /** Names of {@code @Check} methods on this handler class or a superclass, evaluated in order. */
    String[] value() default {};

    /** Reusable rules, evaluated after the named ones. */
    Class<? extends Permission>[] type() default {};
}
