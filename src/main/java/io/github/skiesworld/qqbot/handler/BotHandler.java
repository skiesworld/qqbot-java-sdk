package io.github.skiesworld.qqbot.handler;

/**
 * Marker for a handler class that should be found through {@link java.util.ServiceLoader}.
 *
 * <p>Implementing it costs nothing: the interface declares no methods, so an annotated class stays a plain
 * POJO with a no-arg constructor.
 *
 * <pre>{@code
 * @BotHandlers
 * public class MyHandlers implements BotHandler {
 *
 *     @On(EventType.C2C_MESSAGE_CREATE)
 *     public void onMessage(C2CMessageCreate msg, QQEvent raw) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Discovery is the only reason this interface exists. A handler that is passed straight to
 * {@link io.github.skiesworld.qqbot.event.EventBus#register(Object)} or
 * {@link HandlerRegistry#register(Object)} does not have to implement it.
 */
public interface BotHandler {
}
