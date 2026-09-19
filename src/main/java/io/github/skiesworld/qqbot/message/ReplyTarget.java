package io.github.skiesworld.qqbot.message;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.util.Strings;

import java.util.Map;

/**
 * The four conversations a bot replies into, and which id each one's endpoint wants.
 *
 * <p>Scene is read off the event name first and the payload's keys second, so an event the SDK does not know
 * yet still routes as long as it carries a recognisable id. {@link #UNKNOWN} covers everything else: it routes
 * nowhere, which {@link #send} reports instead of guessing at a conversation.
 */
public enum ReplyTarget {

    /** Private chat, {@code POST /v2/users/{user_openid}/messages}. */
    C2C("user_openid"),

    /** QQ group, {@code POST /v2/groups/{group_openid}/messages}. */
    GROUP("group_openid"),

    /** Sub-channel, {@code POST /channels/{channel_id}/messages}. */
    CHANNEL("channel_id"),

    /** Channel direct message, {@code POST /dms/{guild_id}/messages}. */
    DIRECT("guild_id"),

    /** Nothing in the dispatch identifies a conversation. */
    UNKNOWN(null);

    private static final Map<String, ReplyTarget> BY_EVENT = Map.ofEntries(
            Map.entry("C2C_MESSAGE_CREATE", C2C),
            Map.entry("GROUP_AT_MESSAGE_CREATE", GROUP),
            Map.entry("GROUP_MESSAGE_CREATE", GROUP),
            Map.entry("AT_MESSAGE_CREATE", CHANNEL),
            Map.entry("MESSAGE_CREATE", CHANNEL),
            Map.entry("DIRECT_MESSAGE_CREATE", DIRECT));

    private final String idKey;

    ReplyTarget(String idKey) {
        this.idKey = idKey;
    }

    public static ReplyTarget of(QQEvent event) {
        ReplyTarget byName = BY_EVENT.get(event.name());
        if (byName != null) {
            return byName;
        }
        JsonObject payload = event.rawObject();
        // a guild message carries both ids and the channel one is where the reply belongs
        if (payload.has("group_openid")) {
            return GROUP;
        }
        if (payload.has("channel_id")) {
            return CHANNEL;
        }
        if (payload.has("user_openid") || payload.has("openid")) {
            return C2C;
        }
        if (payload.has("guild_id")) {
            return DIRECT;
        }
        return UNKNOWN;
    }

    /** The conversation id this scene's endpoint takes, or null when the payload has none. */
    public String targetId(QQEvent event) {
        if (idKey == null) {
            return null;
        }
        JsonObject payload = event.rawObject();
        String direct = text(payload, idKey);
        if (direct != null) {
            return direct;
        }
        // c2c and group events nest the sender's openid in author
        return payload.get("author") instanceof JsonObject author ? text(author, idKey) : null;
    }

    /**
     * Send {@code body} into the conversation {@code event} came from, as a passive reply to it. The
     * {@code msg_id} and a fresh {@code msg_seq} are added for you; a dispatch without an id sends proactively,
     * which the platform only allows where the bot may push messages.
     */
    public void send(QQBotClient client, QQEvent event, MessageBuilder body, ReplySequence sequence) {
        String target = targetId(event);
        if (this == UNKNOWN || Strings.isBlank(target)) {
            throw new IllegalStateException("cannot reply to " + event.name() + ": the payload identifies no "
                    + "conversation this SDK can send to");
        }
        String messageId = event.id();
        MessageBuilder reply = Strings.isBlank(messageId)
                ? body
                : body.replyTo(messageId).seq(sequence.next(messageId));
        switch (this) {
            case C2C -> client.api().c2c().sendC2CMessage(target, reply.toC2C());
            case GROUP -> client.api().group().sendGroupMessage(target, reply.toGroup());
            case CHANNEL -> client.api().channelMessages().sendChannelMessage(target, reply.toChannel());
            case DIRECT -> client.api().channelMessages().sendDirectMessage(target, reply.toChannel());
            default -> throw new IllegalStateException("no send route for " + this);
        }
    }

    private static String text(JsonObject payload, String key) {
        if (payload.get(key) == null || payload.get(key).isJsonNull()) {
            return null;
        }
        String value = payload.get(key).getAsString();
        return Strings.isBlank(value) ? null : value;
    }
}
