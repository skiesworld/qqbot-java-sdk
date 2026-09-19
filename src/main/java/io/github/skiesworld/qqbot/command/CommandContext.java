package io.github.skiesworld.qqbot.command;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.api.Api;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.MessageSegments;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.model.User;
import io.github.skiesworld.qqbot.util.Json;

import java.util.List;
import java.util.Objects;

/**
 * What one {@link Command} method is handed: the dispatch it came from plus the match itself.
 *
 * <p>{@link #text()} is the message after the prefix was removed, {@link #rest()} the part after the command
 * word, so {@code /mute 60 @tom} gives {@code command()="mute"} and {@code rest()="60 @tom"}.
 */
public final class CommandContext {

    private final QQBotClient client;
    private final QQEvent event;
    private final String command;
    private final String text;
    private final String rest;
    private final List<String> groups;
    private final Role role;
    private final MessageSegments segments;

    CommandContext(QQBotClient client, QQEvent event, String command, String text, String rest,
            List<String> groups, Role role) {
        this.client = Objects.requireNonNull(client, "client");
        this.event = Objects.requireNonNull(event, "event");
        this.command = Objects.requireNonNull(command, "command");
        this.text = Objects.requireNonNull(text, "text");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.groups = List.copyOf(groups);
        this.role = role;
        this.segments = MessageSegments.of(event);
    }

    /** The word or pattern that matched. */
    public String command() {
        return command;
    }

    /** Message text with the prefix stripped. */
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

    /** Capture groups when {@link Command.Kind#REGEX} matched, in order, empty otherwise. */
    public List<String> groups() {
        return groups;
    }

    public QQEvent event() {
        return event;
    }

    public QQBotClient client() {
        return client;
    }

    public Api api() {
        return client.api();
    }

    /** The message read as segments, including any media the sender attached. */
    public MessageSegments segments() {
        return segments;
    }

    /** The author as reported by the event, or null when the payload has no {@code author}. */
    public User author() {
        return event.rawObject().get("author") instanceof JsonObject author
                ? Json.GSON.fromJson(author, User.class) : null;
    }

    /** The sender's group role, null outside a group. */
    public Role role() {
        return role;
    }

    /** Which conversation {@link #reply(String)} will answer. */
    public ReplyTarget scene() {
        return ReplyTarget.of(event);
    }

    /** The id the platform expects for a passive reply to this message. */
    public String messageId() {
        return event.id();
    }

    /** Reply to the message that triggered this command. */
    public void reply(String text) {
        reply(MessageBuilder.of(text));
    }

    /** Reply with anything the builder can express: markdown, an uploaded file, a card. */
    public void reply(MessageBuilder body) {
        scene().send(client, event, body, client.commands().replies());
    }

    @Override
    public String toString() {
        return "CommandContext{" + command + " args=" + rest + " scene=" + scene() + " id=" + event.id() + "}";
    }
}
