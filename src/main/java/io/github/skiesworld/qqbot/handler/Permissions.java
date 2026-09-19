package io.github.skiesworld.qqbot.handler;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.util.Strings;

import java.util.Collection;
import java.util.Set;

/**
 * The checks the SDK already knows how to express, so a handler can name a rule instead of writing it.
 *
 * <p>The nested classes take {@link Check#type()}: they need no configuration. {@link #scene} and
 * {@link #senderIn} are for rules whose answer comes from the application's own config — build them once and
 * hand them to {@link HandlerRegistry#permission} so a {@code @Check(type = ...)} can name them, or call them
 * straight from a {@link Check} method.
 */
public final class Permissions {

    private static final Permission FROM_C2C = scene(ReplyTarget.C2C);
    private static final Permission FROM_GROUP = scene(ReplyTarget.GROUP);
    private static final Permission FROM_CHANNEL = scene(ReplyTarget.CHANNEL);
    private static final Permission FROM_DIRECT = scene(ReplyTarget.DIRECT);

    /**
     * The person the dispatch is attributed to: the author's openid whichever scene it reports one under, and
     * the top-level {@code openid} for the events that carry no author object.
     */
    public static String senderId(QQEvent event) {
        JsonObject payload = event.rawObject();
        if (payload.get("author") instanceof JsonObject author) {
            for (String key : new String[]{"user_openid", "member_openid", "id"}) {
                if (author.get(key) != null && !author.get(key).isJsonNull()
                        && Strings.isNotBlank(author.get(key).getAsString())) {
                    return author.get(key).getAsString();
                }
            }
        }
        for (String key : new String[]{"user_openid", "openid"}) {
            if (payload.get(key) != null && !payload.get(key).isJsonNull()
                    && Strings.isNotBlank(payload.get(key).getAsString())) {
                return payload.get(key).getAsString();
            }
        }
        return null;
    }

    /** The role the group reported for the sender, or null outside a group and where none was reported. */
    public static String memberRole(QQEvent event) {
        if (event.rawObject().get("author") instanceof JsonObject author
                && author.get("member_role") != null && !author.get("member_role").isJsonNull()) {
            return author.get("member_role").getAsString();
        }
        return null;
    }

    /** Only dispatches coming from {@code scene}, e.g. {@link ReplyTarget#GROUP}. */
    public static Permission scene(ReplyTarget scene) {
        return (event, bot) -> ReplyTarget.of(event) == scene;
    }

    /** Only senders listed in {@code ids}. Group events usually report a {@code member_openid}; pass both ids
     * if the same people can reach the bot privately and in a group. */
    public static Permission senderIn(Collection<String> ids) {
        Set<String> allowed = Set.copyOf(ids);
        return (event, bot) -> {
            String sender = senderId(event);
            return sender != null && allowed.contains(sender);
        };
    }

    /** Private chat. */
    public static final class Private implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return FROM_C2C.allows(event, bot);
        }
    }

    /** QQ group, mentioned or not. */
    public static final class Group implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return FROM_GROUP.allows(event, bot);
        }
    }

    /** Sub-channel. */
    public static final class Channel implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return FROM_CHANNEL.allows(event, bot);
        }
    }

    /** Channel direct message. */
    public static final class Direct implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return FROM_DIRECT.allows(event, bot);
        }
    }

    /** A group admin or the owner; nobody else, since a group that reports no role has none to honour. */
    public static final class GroupAdmin implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            String role = memberRole(event);
            return "admin".equalsIgnoreCase(role) || "owner".equalsIgnoreCase(role);
        }
    }

    /** The owner of the group the dispatch came from. */
    public static final class GroupOwner implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return "owner".equalsIgnoreCase(memberRole(event));
        }
    }

    private Permissions() {
    }
}
