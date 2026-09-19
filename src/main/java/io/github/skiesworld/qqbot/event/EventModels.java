package io.github.skiesworld.qqbot.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the generated payload class for an event by naming convention:
 * {@code C2C_MESSAGE_CREATE} becomes {@code io.github.skiesworld.qqbot.event.model.C2CMessageCreate}.
 *
 * <p>Resolution is cached and failures fall back to {@link Void}, so an event the SDK has no model for
 * still dispatches through {@link QQEvent#raw()}.
 */
public final class EventModels {

    public static final String PACKAGE = "io.github.skiesworld.qqbot.event.model.";

    private static final Map<String, Class<?>> CACHE = new ConcurrentHashMap<>();

    /**
     * Events the docs define by reference to an existing model instead of giving their own body table:
     * the channel message pages say "内容为 Message 对象", and reactions carry a MessageReaction.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("AT_MESSAGE_CREATE", "io.github.skiesworld.qqbot.model.Message"),
            Map.entry("MESSAGE_CREATE", "io.github.skiesworld.qqbot.model.Message"),
            Map.entry("DIRECT_MESSAGE_CREATE", "io.github.skiesworld.qqbot.model.Message"),
            Map.entry("MESSAGE_AUDIT_PASS", "io.github.skiesworld.qqbot.model.MessageAudited"),
            Map.entry("MESSAGE_AUDIT_REJECT", "io.github.skiesworld.qqbot.model.MessageAudited"),
            Map.entry("MESSAGE_REACTION_ADD", "io.github.skiesworld.qqbot.model.MessageReaction"),
            Map.entry("MESSAGE_REACTION_REMOVE", "io.github.skiesworld.qqbot.model.MessageReaction"));

    private EventModels() {
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<T> dataClass(EventType type, String name) {
        String key = name != null ? name : type.name();
        Class<?> cached = CACHE.computeIfAbsent(key, EventModels::load);
        return (Class<T>) cached;
    }

    public static String className(String eventName) {
        return PACKAGE + io.github.skiesworld.qqbot.util.Strings.snakeToUpperCamel(eventName);
    }

    private static Class<?> load(String eventName) {
        String alias = ALIASES.get(eventName);
        if (alias != null) {
            try {
                return Class.forName(alias);
            } catch (ClassNotFoundException | LinkageError e) {
                return Void.class;
            }
        }
        try {
            return Class.forName(className(eventName));
        } catch (ClassNotFoundException | LinkageError e) {
            return Void.class;
        }
    }

    /** Override the mapping, e.g. to reuse one model for several event names. */
    public static void register(String eventName, Class<?> type) {
        CACHE.put(eventName, type);
    }
}
