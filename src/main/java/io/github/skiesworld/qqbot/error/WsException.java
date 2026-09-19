package io.github.skiesworld.qqbot.error;

/**
 * Gateway close codes carry the recovery strategy: some allow RESUME, some force a fresh IDENTIFY,
 * and a few (4914/4915) mean the bot may not connect at all.
 */
public class WsException extends QQBotException {

    public static final int INVALID_OPCODE = 4001;
    public static final int INVALID_PAYLOAD = 4002;
    public static final int BAD_SEQ = 4007;
    public static final int INVALID_SESSION = 4006;
    public static final int RATE_LIMITED = 4008;
    public static final int SESSION_EXPIRED = 4009;
    public static final int INVALID_SHARD = 4010;
    public static final int TOO_MANY_GUILDS = 4011;
    public static final int INVALID_VERSION = 4012;
    public static final int INVALID_INTENT = 4013;
    public static final int INTENT_UNAUTHORIZED = 4014;
    public static final int INTERNAL_ERROR_FIRST = 4900;
    public static final int INTERNAL_ERROR_LAST = 4913;
    public static final int BOT_DELISTED = 4914;
    public static final int BOT_BANNED = 4915;

    private final int code;
    private final String reason;

    public WsException(int code, String reason) {
        super(describe(code, reason));
        this.code = code;
        this.reason = reason;
    }

    private static String describe(int code, String reason) {
        String hint = switch (code) {
            case SESSION_EXPIRED, RATE_LIMITED -> " resume may be retried";
            case BAD_SEQ, INVALID_SESSION, INTERNAL_ERROR_FIRST, INTERNAL_ERROR_LAST -> " re-identify";
            case BOT_DELISTED, BOT_BANNED -> " the platform refuses connections, contact QQ open platform";
            default -> "";
        };
        return "Gateway error " + code + ": " + reason + hint;
    }

    public int code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    /** Close is fatal and no reconnection should be attempted. */
    public boolean fatal() {
        return code == BOT_DELISTED || code == BOT_BANNED
                || code == INVALID_SHARD || code == INVALID_INTENT || code == INTENT_UNAUTHORIZED
                || code == TOO_MANY_GUILDS || code == INVALID_VERSION || code == INVALID_OPCODE
                || code == INVALID_PAYLOAD;
    }

    /** The session may be restored with an existing session id and seq instead of a fresh IDENTIFY. */
    public boolean resumable() {
        return code == SESSION_EXPIRED || code == RATE_LIMITED;
    }
}
