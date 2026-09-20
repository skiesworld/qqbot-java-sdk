package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.event.EventType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Routes one method to the events it handles, and — when it names a command — to the messages that command appears
 * in. This is the whole routing surface: which dispatches reach the method, in what order, and who may trigger it.
 *
 * <pre>{@code
 * @On(EventType.GROUP_AT_MESSAGE_CREATE)
 * public void onGroup(QQMessageEvent msg) {                 // the envelope says what the method can read
 *     msg.reply("收到 " + msg.content());
 * }
 *
 * @On(command = {"签到", "checkin"})                             // a command word, on every message event
 * public void checkIn(OnContext ctx) { ctx.reply("已签到"); }
 *
 * @On(command = "mute (\\S+) (\\d+)", kind = On.Kind.REGEX, requires = Permissions.GroupAdmin.class)
 * public void mute(OnContext ctx) { ... }                   // capture groups in ctx.groups()
 *
 * @On(command = "清档", priority = 10, block = true)         // runs after the default 0s, ends the chain
 * public void wipe(OnContext ctx) { ... }
 *
 * @On(name = "GROUP_SOMETHING_NEW")                         // an event name newer than this SDK version
 * public void onFuture(JsonObject body) { ... }
 * }</pre>
 *
 * <h2>What the events are</h2>
 *
 * <p>Leave {@link #value()} and {@link #name()} empty and the events come from the method's own parameters: a
 * {@link io.github.skiesworld.qqbot.event.QQMessageEvent} parameter means every event that carries message text,
 * a {@link io.github.skiesworld.qqbot.event.QQNoticeEvent} parameter every event that reports something having
 * happened, and a concrete one like {@link io.github.skiesworld.qqbot.event.GroupJoinRequestEvent} just its own
 * event. Naming the events and taking an envelope parameter is allowed as long as the two can meet: a
 * {@code QQMessageEvent} parameter on an event with no text is refused at registration, because such a method
 * could never run.
 *
 * <h2>Ordering</h2>
 *
 * <p>Routes run by ascending {@link #priority()}, ties broken by registration order. {@link #block()} stops the
 * routes that would have run after this one once this one has handled the dispatch. Both are about the chain in
 * one conversation, which the bus keeps in order; see {@link io.github.skiesworld.qqbot.event.EventBus} for what
 * changes when the bus runs on a thread pool.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface On {

    /** How {@link #command()} is read. */
    enum Kind {
        /** A command word at the start of the message, followed by whitespace or the end. */
        WORD,
        /** A regular expression that must match the whole message text. */
        REGEX
    }

    /** Modelled events this method handles; empty means infer them from the parameters. */
    EventType[] value() default {};

    /** Raw {@code t} values, for events newer than this SDK version. */
    String[] name() default {};

    /**
     * Command words, or patterns when {@link #kind()} is {@link Kind#REGEX}. Several words are one command with
     * aliases: the first is what {@link OnContext#command()} and {@link HandlerRegistry#describe()} report.
     */
    String[] command() default {};

    Kind kind() default Kind.WORD;

    /**
     * Prefixes for this command; empty means the registry's {@link HandlerRegistry#usePrefixes}, which defaults to
     * no prefix at all, since a group message reaches the bot only when it is mentioned and that mention is
     * already stripped from {@code content}.
     */
    String[] prefix() default {};

    /** Runs before the routes with a higher number. */
    int priority() default 0;

    /** Stop the routes after this one from seeing the dispatch this method handled. */
    boolean block() default false;

    /** Gates that must allow the call, by name here or by type in {@link Check}. */
    Class<? extends Permission>[] requires() default {};

    /** Text for {@link HandlerRegistry#describe()}, nothing functional. */
    String description() default "";
}
