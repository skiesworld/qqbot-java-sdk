package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.api.Api;
import io.github.skiesworld.qqbot.event.Outbound;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.MessageSegments;

import java.util.List;
import java.util.Objects;

/**
 * The match a {@code @On(command = ...)} method asked for, next to the message it matched.
 *
 * <p>{@code "/mute 60 tom"} with {@code @On(value = "mute (\\S+) (\\d+)", kind = REGEX)} gives
 * {@code text()="mute 60 tom"} and {@code groups()=["60","tom"]}; with the word {@code "mute"} it gives
 * {@code command()="mute"}, {@code rest()="60 tom"} and {@code args()=["60","tom"]}. A non-matching message never
 * reaches the method at all, so there is nothing here to test for "no match".
 */
public final class OnContext {

    private final QQMessageEvent message;
    private final String command;
    private final String text;
    private final String rest;
    private final List<String> groups;

    OnContext(QQMessageEvent message, String command, String text, String rest, List<String> groups) {
        this.message = Objects.requireNonNull(message, "message");
        this.command = Objects.requireNonNull(command, "command");
        this.text = Objects.requireNonNull(text, "text");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.groups = List.copyOf(groups);
    }

    /** The word or pattern that matched. */
    public String command() {
        return command;
    }

    /** Message text with the prefix removed. */
    public String text() {
        return text;
    }

    /** What came after the command word, trimmed; for a regex match this is the whole {@link #text()}. */
    public String rest() {
        return rest;
    }

    /** {@link #rest()} split on whitespace, empty when there are no arguments. */
    public List<String> args() {
        return rest.isEmpty() ? List.of() : List.of(rest.split("\\s+"));
    }

    /** Capture groups when {@link On.Kind#REGEX} matched, in order, empty otherwise. */
    public List<String> groups() {
        return groups;
    }

    /** The message itself, with its author, its segments and its way back. */
    public QQMessageEvent message() {
        return message;
    }

    /** The message read as segments, including any media the sender attached. */
    public MessageSegments segments() {
        return message.segments();
    }

    /** Answer in the conversation this message came from. */
    public void reply(String text) {
        message.reply(text);
    }

    /** Answer with anything the builder can express: markdown, an uploaded file, a card. */
    public void reply(MessageBuilder body) {
        message.reply(body);
    }

    /** The OpenAPI calls of the bot this command ran on. */
    public Api api() {
        return outbound().api();
    }

    private Outbound outbound() {
        Outbound outbound = message.outbound();
        if (outbound == null) {
            throw new IllegalStateException(message.name() + " arrived on an EventBus no bot attached, so this"
                    + " command has nothing to answer with; dispatch it through a QQBotClient");
        }
        return outbound;
    }

    @Override
    public String toString() {
        return "OnContext{" + command + " rest=" + rest + " id=" + message.id() + "}";
    }
}
