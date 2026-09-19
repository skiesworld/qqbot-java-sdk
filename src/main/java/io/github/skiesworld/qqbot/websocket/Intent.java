package io.github.skiesworld.qqbot.websocket;

import java.util.ArrayList;
import java.util.List;

/**
 * Event subscription bit flags, exactly as documented in "事件订阅 Intents".
 *
 * <p>Only {@link #GUILDS}, {@link #GUILD_MEMBERS} and {@link #PUBLIC_GUILD_MESSAGES} are granted by
 * default. Identifying with a bit the bot has not been approved for closes the connection with
 * gateway code 4014, so keep the mask as small as the bot actually needs.
 */
public enum Intent {

    /** GUILD_CREATE, GUILD_UPDATE, GUILD_DELETE, CHANNEL_CREATE, CHANNEL_UPDATE, CHANNEL_DELETE. */
    GUILDS(0),
    /** GUILD_MEMBER_ADD, GUILD_MEMBER_UPDATE, GUILD_MEMBER_REMOVE. */
    GUILD_MEMBERS(1),
    /** MESSAGE_CREATE, MESSAGE_DELETE. Private-area bots only. */
    GUILD_MESSAGES(9),
    /** MESSAGE_REACTION_ADD, MESSAGE_REACTION_REMOVE. */
    GUILD_MESSAGE_REACTIONS(10),
    /** DIRECT_MESSAGE_CREATE, DIRECT_MESSAGE_DELETE. */
    DIRECT_MESSAGE(12),
    /** GROUP_MEMBER_ADD, GROUP_MEMBER_REMOVE, GROUP_JOIN_REQUEST. */
    GROUP_MEMBER_EVENT(24),
    /** C2C and group message events, friend events and push-switch events. */
    GROUP_AND_C2C_EVENT(25),
    /** INTERACTION_CREATE. */
    INTERACTION(26),
    /** MESSAGE_AUDIT_PASS, MESSAGE_AUDIT_REJECT. */
    MESSAGE_AUDIT(27),
    /** FORUM_* events. Private-area bots only. */
    FORUMS_EVENT(28),
    /** AUDIO_START, AUDIO_FINISH, AUDIO_ON_MIC, AUDIO_OFF_MIC. */
    AUDIO_ACTION(29),
    /** AT_MESSAGE_CREATE, PUBLIC_MESSAGE_DELETE. Public-area message events. */
    PUBLIC_GUILD_MESSAGES(30);

    private final int shift;

    Intent(int shift) {
        this.shift = shift;
    }

    public long bit() {
        return 1L << shift;
    }

    public int shift() {
        return shift;
    }

    public boolean isSetIn(long mask) {
        return (mask & bit()) != 0;
    }

    public static long maskOf(Intent... intents) {
        long bits = 0;
        for (Intent i : intents) {
            bits |= i.bit();
        }
        return bits;
    }

    public static List<Intent> parse(long mask) {
        List<Intent> out = new ArrayList<>();
        for (Intent i : values()) {
            if (i.isSetIn(mask)) {
                out.add(i);
            }
        }
        return out;
    }
}
