package io.github.skiesworld.qqbot.websocket;

/** Gateway operation codes, as listed in the official opcode table. */
public enum OpCode {

    /** Server pushes an event; {@code t} names it and {@code d} carries it. */
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    RESUME(6),
    RECONNECT(7),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    /** Reply in HTTP callback mode acknowledging a pushed event. */
    HTTP_CALLBACK_ACK(12),
    /** Callback address validation performed by the open platform. */
    CALLBACK_VALIDATION(13);

    private final int code;

    OpCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static OpCode from(int code) {
        for (OpCode o : values()) {
            if (o.code == code) {
                return o;
            }
        }
        return null;
    }
}
