package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.MessageSegments;
import io.github.skiesworld.qqbot.model.User;
import io.github.skiesworld.qqbot.util.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * {@code content}, with any {@code <@...>} token that corresponds to an entry in {@link #mentions()} removed.
     *
     * <p>The platform's own behaviour here is not uniform: the docs say a group mention event arrives with the
     * {@code @机器人} prefix already stripped, and for some bots it does — but not for all. Measured on a real
     * group bot, a message that named it arrived as {@code "<@D602A5A6...> 123"}. So this strips the tokens that
     * {@code mentions} vouches for and leaves everything else alone, which is correct either way.
     *
     * <p>The information is not lost: {@link #mentions()} still lists who was named. The raw string is still on
     * the payload — {@code rawObject().get("content")}.
     */
    public String content() {
        return withoutMentions(EventValues.text(rawObject(), "content"));
    }

    /**
     * The {@code <@...>} tokens that name somebody in {@code mentions} taken out, along with the whitespace
     * they leave at the front (a mention sits at the start of a message addressed to a bot).
     *
     * <p>Only ids {@code mentions} vouches for are touched: a literal {@code <@something>} in a sentence stays.
     */
    private String withoutMentions(String text) {
        if (text == null || text.indexOf("<@") < 0) {
            return text;
        }
        Set<String> ids = new HashSet<>();
        for (User user : mentions()) {
            for (String id : new String[] {user.id, user.userOpenid, user.memberOpenid, user.unionOpenid}) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean removed = false;
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("<@", index);
            int end = start < 0 ? -1 : text.indexOf('>', start + 2);
            if (start < 0 || end < 0) {
                out.append(text, index, text.length());
                break;
            }
            String id = text.substring(start + 2, end);
            if (id.startsWith("!")) {
                id = id.substring(1);
            }
            out.append(text, index, ids.contains(id) ? start : end + 1);
            removed |= ids.contains(id);
            index = end + 1;
        }
        String stripped = out.toString();
        return removed ? stripped.stripLeading() : stripped;
    }

    /**
     * Whether this message is addressed to the bot that is running the handler.
     *
     * <p>There are two ways a message reaches a bot, and they are not the same test:
     *
     * <ul>
     *   <li>the platform sent it as a mention event — {@code GROUP_AT_MESSAGE_CREATE}, {@code AT_MESSAGE_CREATE}
     *       — or as a direct message. Those events only exist for messages that named this bot, so it is true by
     *       construction; nothing has to be compared.
     *   <li>group-wide mode pushes <em>every</em> group message to {@code GROUP_MESSAGE_CREATE}, and there a
     *       message meant for the bot looks like any other. {@code mentions} is the only hint.
     * </ul>
     *
     * <p>⚠️ The second case cannot tell <em>which</em> bot was named — a mention says a participant is a bot, not
     * which one — so in a group with several bots this can be true for a message meant for another one. A handler
     * that must only hear its own bot listens to the mention event instead; the platform did the filtering there.
     */
    public boolean addressedToBot() {
        EventType event = type();
        if (event == EventType.GROUP_AT_MESSAGE_CREATE || event == EventType.AT_MESSAGE_CREATE
                || event == EventType.C2C_MESSAGE_CREATE) {
            return true;
        }
        return mentionedBot();
    }

    /**
     * The id of the message itself — the payload's {@code id}, what the platform calls {@code d.id}.
     *
     * <p>⚠️ Not {@link #id()}: that is the <em>event</em> id (the envelope's {@code id}, e.g.
     * {@code GROUP_MESSAGE_CREATE:0d2kq...}). The two are different values, and a passive reply must quote
     * this one — the platform answers a message reply that carries the event id with
     * {@code 40034024 请求参数msg_id无效或越权}. Only {@code replyToEvent} wants the event id, and only for
     * the three dispatches that accept an {@code event_id}.
     */
    public String messageId() {
        return EventValues.text(rawObject(), "id");
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

    /**
     * The users this message names in {@code mentions} — the group and channel events carry it, and it is the
     * list to compare against {@link io.github.skiesworld.qqbot.QQBotClient#selfId()} when a bot has to tell
     * whether a message was meant for it. Empty when the payload reports none.
     */
    public List<User> mentions() {
        JsonElement array = rawObject().get("mentions");
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<User> mentioned = new ArrayList<>();
        for (JsonElement element : array.getAsJsonArray()) {
            if (element.isJsonObject()) {
                mentioned.add(Json.GSON.fromJson(element, User.class));
            }
        }
        return List.copyOf(mentioned);
    }

    /**
     * Whether one of this message's {@code mentions} is a bot, which in group-wide mode is how a message addressed
     * to the bot shows up: the platform pushes every group message to that event, mention or not.
     *
     * <p>The {@code User} a mention carries says <em>that</em> participant is a bot, not which one, so in a group
     * with several bots this can be true for a message meant for a different bot. A command that should only hear
     * its own bot listens to {@code GROUP_AT_MESSAGE_CREATE} instead — the platform did the filtering there.
     */
    public boolean mentionedBot() {
        return mentions().stream().anyMatch(user -> Boolean.TRUE.equals(user.bot));
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
