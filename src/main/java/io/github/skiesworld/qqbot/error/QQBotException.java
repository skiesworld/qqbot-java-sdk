package io.github.skiesworld.qqbot.error;

/** Root of every exception thrown by the SDK. */
public class QQBotException extends RuntimeException {

    public QQBotException(String message) {
        super(message);
    }

    public QQBotException(String message, Throwable cause) {
        super(message, cause);
    }
}
