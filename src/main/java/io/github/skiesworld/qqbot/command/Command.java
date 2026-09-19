package io.github.skiesworld.qqbot.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import io.github.skiesworld.qqbot.event.EventType;

import java.lang.annotation.Target;

/**
 * Marks a method as the handler of a command, matched against the text of an incoming message.
 *
 * <p>Parameters bind exactly as they do for {@link io.github.skiesworld.qqbot.handler.BotEvent} methods, plus
 * {@link CommandContext}, whose absence of a match is what filters the dispatches: when the text matches no
 * command — or the sender lacks {@link #role()} — the method does not run.
 *
 * <pre>{@code
 * @Command(value = {"签到", "checkin"}, role = Role.MEMBER)
 * public void checkIn(CommandContext ctx) {
 *     ctx.reply("今天已经签过啦");
 * }
 *
 * @Command(value = "mute (\\d+)", kind = Command.Kind.REGEX, role = Role.ADMIN)
 * public void mute(CommandContext ctx) {
 *     long seconds = Long.parseLong(ctx.groups().get(0));
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Command {

    /** How {@link #value()} is read. */
    enum Kind {
        /** A command word at the start of the message, followed by whitespace or the end. */
        WORD,
        /** A regular expression that must match the whole message text. */
        REGEX
    }

    /** Command words, or patterns when {@link #kind()} is {@link Kind#REGEX}. */
    String[] value();

    /** Extra words accepted beside {@link #value()}. */
    String[] alias() default {};

    Kind kind() default Kind.WORD;

    /**
     * Which message events this command listens to; empty means all of them
     * ({@link io.github.skiesworld.qqbot.event.MessageEvents#WITH_TEXT}). Narrow it to say a command exists only
     * in a group — {@code on = EventType.GROUP_AT_MESSAGE_CREATE} — and anything that carries no message text is
     * rejected at registration, because such a command could never match.
     */
    EventType[] on() default {};

    /**
     * Prefixes for this command; empty means the registry's {@link CommandRegistry#usePrefixes}, which in turn
     * defaults to no prefix at all. Give one value here to pin a command to, say, {@code "/"} while the rest of
     * the bot answers to bare words in a private chat.
     */
    String[] prefix() default {};

    /**
     * Minimum group role required. {@link Role#ANY} by default, since a private chat reports no role and the
     * platform already filtered the message down to the bot.
     */
    Role role() default Role.ANY;

    /** Text for {@link CommandRegistry#describe()}, nothing functional. */
    String description() default "";
}
