package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.event.QQNoticeEvent;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.model.User;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The rules the SDK already knows how to express, so a handler can name one instead of writing it.
 *
 * <p>The nested classes go in {@link On#requires()} or {@link Check#type()} and need no configuration.
 * {@link #scene} and {@link #senderIn} are for rules whose answer comes from the application's own config — build
 * one and hand it to {@link HandlerRegistry#permission} so a name can point at it, or call it straight from a
 * {@link Check} method.
 */
public final class Permissions {

    /** The events the platform only pushes when the bot itself was addressed. */
    private static final List<EventType> ADDRESSED = List.of(EventType.GROUP_AT_MESSAGE_CREATE,
            EventType.AT_MESSAGE_CREATE, EventType.DIRECT_MESSAGE_CREATE);

    /**
     * The person this dispatch is about: who sent the message, or for a notice who set it off and — where the
     * event names no actor — who it happened to. Null for the events that report nobody in the openid space,
     * which includes the guild-side ones whose {@code user_id} is a different id altogether.
     */
    public static String senderId(QQEvent event) {
        if (event instanceof QQMessageEvent message) {
            return message.senderId();
        }
        if (event instanceof QQNoticeEvent notice) {
            return notice.actor().orElseGet(() -> notice.subject().orElse(null));
        }
        return null;
    }

    /** The role the group reported for the sender, or null outside a group and where none was reported. */
    public static String memberRole(QQEvent event) {
        User author = event instanceof QQMessageEvent message ? message.author() : null;
        return author == null ? null : author.memberRole;
    }

    /** Only dispatches coming from {@code scene}, e.g. {@link ReplyTarget#GROUP}. */
    public static Permission scene(ReplyTarget scene) {
        return (event, bot) -> event.scene() == scene;
    }

    /**
     * Only senders listed in {@code ids}. A group event may report a {@code member_openid} where a private one
     * reports a {@code user_openid}, so pass both ids if the same people can reach the bot either way.
     */
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
            return event.scene() == ReplyTarget.C2C;
        }
    }

    /** QQ group, mentioned or not. */
    public static final class Group implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return event.scene() == ReplyTarget.GROUP;
        }
    }

    /** Sub-channel. */
    public static final class Channel implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return event.scene() == ReplyTarget.CHANNEL;
        }
    }

    /** Channel direct message. */
    public static final class Direct implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return event.scene() == ReplyTarget.DIRECT;
        }
    }

    /**
     * A group admin or the owner. A dispatch from outside a group reports no role and is denied: this rule is a
     * statement about a group, so a private chat or a channel notice is not its business.
     */
    public static final class GroupAdmin implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            String role = memberRole(event);
            return "admin".equalsIgnoreCase(role) || "owner".equalsIgnoreCase(role);
        }
    }

    /** The owner of the group the sender is in. */
    public static final class GroupOwner implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return "owner".equalsIgnoreCase(memberRole(event));
        }
    }

    /**
     * Only where the platform addressed the bot: the mention events, and the channel direct messages, which have
     * nobody else to be for. Anywhere else the answer is no, because the bot was not being spoken to.
     */
    public static final class ToMe implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return ADDRESSED.contains(event.type());
        }
    }

    private Permissions() {
    }
}
