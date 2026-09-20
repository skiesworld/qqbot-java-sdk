package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.MessageSegments;
import io.github.skiesworld.qqbot.model.User;
import io.github.skiesworld.qqbot.util.Json;

import java.util.List;

/**
 * A dispatch the platform sent as one turn in a conversation: it carries text, an author and a place to answer.
 *
 * <p>Declaring this type on a handler parameter is how you say "every message event" — see
 * {@link io.github.skiesworld.qqbot.handler.On} — and the set it stands for is generated from
 * {@link EventRouting}, so an event the docs add later lands in it without anyone editing a list here.
 */
public class QQMessageEvent extends QQEvent {

    private static final List<String> SENDER_KEYS = List.of("user_openid", "member_openid", "id");

    public QQMessageEvent(String id, int op, Long seq, String name, EventType type, JsonElement data,
            Outbound outbound) {
        super(id, op, seq, name, type, data, outbound);
    }

    /** The events built into this envelope. */
    public static List<EventType> eventTypes() {
        return EventRouting.eventTypes(EventRouting.Envelope.MESSAGE);
    }

    /** {@code content} as the platform reported it; for a group mention the bot's own mention is already gone. */
    public String content() {
        return EventValues.text(rawObject(), "content");
    }

    /** The sender, or null when the payload reports none — which the message events always do. */
    public User author() {
        JsonElement author = rawObject().get("author");
        return author == null || !author.isJsonObject() ? null : Json.GSON.fromJson(author, User.class);
    }

    /** Whoever sent it, as the openid this scene reports: {@code user_openid} or, in a group, {@code member_openid}. */
    public String senderId() {
        return EventValues.firstText(EventValues.child(rawObject(), "author"), SENDER_KEYS);
    }

    /** The message read as segments: text, media, card and quoted elements. */
    public MessageSegments segments() {
        return MessageSegments.of(this);
    }
    /** Answer in the conversation this message came from, with the reply fields and {@code msg_seq} filled in. */
    public void reply(String text) {
        reply(MessageBuilder.of(text));
    }

    /** Answer with anything the builder can express: markdown, an uploaded file, a card. */
    public void reply(MessageBuilder body) {
        Outbound answer = outbound();
        if (answer == null) {
            throw new IllegalStateException(name() + " arrived on an EventBus no bot attached, so it cannot be"
                    + " answered; dispatch it through a QQBotClient");
        }
        answer.reply(this, body);
    }
}
