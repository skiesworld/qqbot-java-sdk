package io.github.skiesworld.qqbot.event;

import io.github.skiesworld.qqbot.websocket.Intent;

/**
 * Every event name the gateway can push, with the intent bit that must be subscribed to receive it.
 * {@link #from(String)} never fails: names the SDK does not know yet map to {@link #UNKNOWN} and stay
 * reachable through {@link QQEvent#name()}.
 */
public enum EventType {

    // gateway session lifecycle, not part of any intent
    READY(null),
    RESUMED(null),

    // GUILDS
    GUILD_CREATE(Intent.GUILDS),
    GUILD_UPDATE(Intent.GUILDS),
    GUILD_DELETE(Intent.GUILDS),
    CHANNEL_CREATE(Intent.GUILDS),
    CHANNEL_UPDATE(Intent.GUILDS),
    CHANNEL_DELETE(Intent.GUILDS),

    // GUILD_MEMBERS
    GUILD_MEMBER_ADD(Intent.GUILD_MEMBERS),
    GUILD_MEMBER_UPDATE(Intent.GUILD_MEMBERS),
    GUILD_MEMBER_REMOVE(Intent.GUILD_MEMBERS),

    // GUILD_MESSAGES (private area)
    MESSAGE_CREATE(Intent.GUILD_MESSAGES),
    MESSAGE_DELETE(Intent.GUILD_MESSAGES),

    // PUBLIC_GUILD_MESSAGES
    AT_MESSAGE_CREATE(Intent.PUBLIC_GUILD_MESSAGES),
    PUBLIC_MESSAGE_DELETE(Intent.PUBLIC_GUILD_MESSAGES),

    // GUILD_MESSAGE_REACTIONS
    MESSAGE_REACTION_ADD(Intent.GUILD_MESSAGE_REACTIONS),
    MESSAGE_REACTION_REMOVE(Intent.GUILD_MESSAGE_REACTIONS),

    // DIRECT_MESSAGE
    DIRECT_MESSAGE_CREATE(Intent.DIRECT_MESSAGE),
    DIRECT_MESSAGE_DELETE(Intent.DIRECT_MESSAGE),

    // GROUP_AND_C2C_EVENT
    C2C_MESSAGE_CREATE(Intent.GROUP_AND_C2C_EVENT),
    FRIEND_ADD(Intent.GROUP_AND_C2C_EVENT),
    FRIEND_DEL(Intent.GROUP_AND_C2C_EVENT),
    C2C_MSG_REJECT(Intent.GROUP_AND_C2C_EVENT),
    C2C_MSG_RECEIVE(Intent.GROUP_AND_C2C_EVENT),
    GROUP_ADD_ROBOT(Intent.GROUP_AND_C2C_EVENT),
    GROUP_DEL_ROBOT(Intent.GROUP_AND_C2C_EVENT),
    GROUP_AT_MESSAGE_CREATE(Intent.GROUP_AND_C2C_EVENT),
    GROUP_MESSAGE_CREATE(Intent.GROUP_AND_C2C_EVENT),
    GROUP_MSG_REJECT(Intent.GROUP_AND_C2C_EVENT),
    GROUP_MSG_RECEIVE(Intent.GROUP_AND_C2C_EVENT),
    /** Subscription-message authorisation changed; carries per-template results. */
    SUBSCRIBE_MESSAGE_STATUS(Intent.GROUP_AND_C2C_EVENT),

    // GROUP_MEMBER_EVENT
    GROUP_MEMBER_ADD(Intent.GROUP_MEMBER_EVENT),
    GROUP_MEMBER_REMOVE(Intent.GROUP_MEMBER_EVENT),
    GROUP_JOIN_REQUEST(Intent.GROUP_MEMBER_EVENT),

    // INTERACTION
    INTERACTION_CREATE(Intent.INTERACTION),

    // MESSAGE_AUDIT
    MESSAGE_AUDIT_PASS(Intent.MESSAGE_AUDIT),
    MESSAGE_AUDIT_REJECT(Intent.MESSAGE_AUDIT),

    // FORUMS_EVENT
    FORUM_THREAD_CREATE(Intent.FORUMS_EVENT),
    FORUM_THREAD_UPDATE(Intent.FORUMS_EVENT),
    FORUM_THREAD_DELETE(Intent.FORUMS_EVENT),
    FORUM_POST_CREATE(Intent.FORUMS_EVENT),
    FORUM_POST_DELETE(Intent.FORUMS_EVENT),
    FORUM_REPLY_CREATE(Intent.FORUMS_EVENT),
    FORUM_REPLY_DELETE(Intent.FORUMS_EVENT),
    FORUM_PUBLISH_AUDIT_RESULT(Intent.FORUMS_EVENT),

    // AUDIO_ACTION
    AUDIO_START(Intent.AUDIO_ACTION),
    AUDIO_FINISH(Intent.AUDIO_ACTION),
    AUDIO_ON_MIC(Intent.AUDIO_ACTION),
    AUDIO_OFF_MIC(Intent.AUDIO_ACTION),

    UNKNOWN(null);

    private final Intent intent;

    EventType(Intent intent) {
        this.intent = intent;
    }

    /** The intent bit required to receive this event, or null for session lifecycle events. */
    public Intent intent() {
        return intent;
    }

    public static EventType from(String name) {
        if (name != null) {
            for (EventType t : values()) {
                if (t.name().equals(name)) {
                    return t;
                }
            }
        }
        return UNKNOWN;
    }
}
