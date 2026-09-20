package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.event.QQNoticeEvent;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(Permissions.class);

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
     * Only where the bot was addressed, read off the three things that can say so, in order of how much they can be
     * trusted:
     *
     * <ol>
     *   <li>the event the platform only pushes on a mention ({@code GROUP_AT_MESSAGE_CREATE},
     *       {@code AT_MESSAGE_CREATE}) and channel direct messages, which have nobody else to be for;
     *   <li>the bot's own id — {@link QQBotClient#selfId()}, cached from READY or {@code /users/@me} — appearing in
     *       this message's {@code mentions};
     *   <li>a bot among the {@code mentions}, which is what an @ looks like in group-wide mode when the id above
     *       has nothing to match: the payload says a bot was named, not which one, so a group with several bots can
     *       match here for a message meant for another.
     * </ol>
     *
     * <p>Whichever branch answered is logged at debug, because the third one being reached at all is the signal to
     * go and check whether the platform reuses the bot's id across scenes — that question is answered by observation,
     * not by the docs.
     */
    public static final class ToMe implements Permission {
        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            if (ADDRESSED.contains(event.type())) {
                return true;
            }
            if (!(event instanceof QQMessageEvent message)) {
                return false;
            }
            List<User> mentions = message.mentions();
            if (mentions.isEmpty()) {
                return false;
            }
            String selfId = bot == null ? null : bot.selfId();
            if (selfId != null && mentions.stream().anyMatch(user -> isSelf(user, selfId))) {
                log.debug("toMe: {} names this bot by id {}", event.name(), selfId);
                return true;
            }
            if (message.mentionedBot()) {
                log.debug("toMe: {} names a bot but not this bot's id ({}); answering on the bot flag alone."
                        + " In a group with several bots this can be the wrong one.", event.name(), selfId);
                return true;
            }
            return false;
        }

        private static boolean isSelf(User mentioned, String selfId) {
            return selfId.equals(mentioned.id) || selfId.equals(mentioned.userOpenid)
                    || selfId.equals(mentioned.memberOpenid);
        }
    }

    private Permissions() {
    }
}
