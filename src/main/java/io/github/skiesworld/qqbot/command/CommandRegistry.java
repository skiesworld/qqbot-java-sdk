package io.github.skiesworld.qqbot.command;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.handler.BotEvent;
import io.github.skiesworld.qqbot.handler.BotHandler;
import io.github.skiesworld.qqbot.handler.HandlerRegistry;
import io.github.skiesworld.qqbot.event.MessageEvents;
import io.github.skiesworld.qqbot.message.ReplySequence;
import io.github.skiesworld.qqbot.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Command matching on top of the message events: {@link Command} methods run only for the dispatches whose text
 * actually matches, and only for senders whose role allows it.
 *
 * <p>Text arrives already addressed to the bot — a group message is pushed only when the bot is mentioned and the
 * platform strips that mention — so the default is no prefix at all, with {@link #usePrefixes} for a bot that
 * shares a group with other bots. A prefix that is configured and absent means no match, and the command does not
 * run.
 *
 * <p>Matching reads {@code content} only. Media, cards and quotes have no text to match, so an image sent to the
 * bot triggers no command; {@link io.github.skiesworld.qqbot.message.MessageSegments} is what reads those. Replies
 * go back through {@link CommandContext#reply(String)}, which picks the endpoint for the scene and keeps
 * {@code msg_seq} unique per original message.
 *
 * <pre>{@code
 * bot.commands().usePrefixes("/", "").register(new AdminCommands());
 * }</pre>
 */
public final class CommandRegistry {

    /** What {@code @Command} listens to unless it names a narrower set in {@code on}. */
    private static final EventType[] ALL_MESSAGE_EVENTS = MessageEvents.WITH_TEXT.toArray(new EventType[0]);

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final QQBotClient client;
    private final ReplySequence replies = new ReplySequence();
    private final List<Binding> bindings = new CopyOnWriteArrayList<>();
    private volatile List<String> prefixes = List.of("");

    public CommandRegistry(QQBotClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Prefixes a message may start with, for every command registered from now on. The empty string means "no
     * prefix needed"; a bot that must be addressed can drop it. Commands already registered keep what they got.
     */
    public CommandRegistry usePrefixes(String... prefixes) {
        List<String> copy = new ArrayList<>();
        for (String prefix : prefixes) {
            copy.add(prefix == null ? "" : prefix);
        }
        this.prefixes = List.copyOf(copy.isEmpty() ? List.of("") : copy);
        return this;
    }

    /** The sequence backing {@link CommandContext#reply(String)}. */
    public ReplySequence replies() {
        return replies;
    }

    /** Register every {@link Command} method of {@code handler}; each must take a {@link CommandContext}. */
    public EventBus.Subscription register(Object handler) {
        List<Method> methods = commandMethods(handler);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("no " + Command.class.getSimpleName() + " methods found on "
                    + handler.getClass().getName() + "; annotate one or register a @BotEvent handler instead");
        }
        List<EventBus.Subscription> subs = new ArrayList<>();
        for (Method method : methods) {
            subs.add(bind(handler, method));
        }
        return () -> {
            for (EventBus.Subscription s : subs) {
                s.close();
            }
        };
    }

    /** Register {@link BotHandler} classes found through the context class loader. */
    public int registerDiscovered() {
        return registerDiscovered(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Register discovered command classes, returning how many took part. A class with only {@link BotEvent}
     * methods is left to {@link HandlerRegistry#registerDiscovered()}, so both discovery calls can run.
     */
    public int registerDiscovered(ClassLoader loader) {
        int n = 0;
        for (BotHandler handler : ServiceLoader.load(BotHandler.class, loader)) {
            if (commandMethods(handler).isEmpty()) {
                continue;
            }
            try {
                register(handler);
                n++;
            } catch (RuntimeException | LinkageError e) {
                log.error("skipping discovered command class {}", handler.getClass().getName(), e);
            }
        }
        return n;
    }

    /** One line per command, for a {@code /help} answer or a startup log. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Binding b : bindings) {
            out.add(b.describe());
        }
        return out;
    }

    private static List<Method> commandMethods(Object handler) {
        List<Method> out = new ArrayList<>();
        for (Method method : HandlerRegistry.candidateMethods(handler.getClass())) {
            if (method.isAnnotationPresent(Command.class)) {
                out.add(method);
            }
        }
        return out;
    }

    private EventBus.Subscription bind(Object handler, Method method) {
        String name = handler.getClass().getName() + "#" + method.getName();
        if (method.isAnnotationPresent(BotEvent.class)) {
            throw new IllegalArgumentException(name + " has both @Command and @BotEvent; it would run twice per"
                    + " message, pick one");
        }
        if (!takesContext(method)) {
            throw new IllegalArgumentException(name + " is a @Command but takes no CommandContext, so nothing"
                    + " would filter it; add a CommandContext parameter or use @BotEvent");
        }
        Command annotation = method.getAnnotation(Command.class);
        Binding binding = new Binding(annotation, name);
        Map<Class<?>, Function<QQEvent, Object>> extra = Map.of(CommandContext.class, binding::context);
        List<HandlerRegistry.Route> routes = List.of(new HandlerRegistry.Route(method, events(annotation, name),
                new String[0]));
        EventBus.Subscription subscription = client.handlers().register(handler, routes,
                HandlerRegistry.RouteSpec.of(extra));
        bindings.add(binding);
        return subscription;
    }

    /** {@code on} defaults to every message event and may only narrow within them. */
    private static EventType[] events(Command command, String name) {
        if (command.on().length == 0) {
            return ALL_MESSAGE_EVENTS;
        }
        for (EventType type : command.on()) {
            if (!MessageEvents.WITH_TEXT.contains(type)) {
                throw new IllegalArgumentException(name + " asks for " + type + ", which carries no message"
                        + " text; a command there could never match");
            }
        }
        return command.on();
    }

    private static boolean takesContext(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type == CommandContext.class) {
                return true;
            }
        }
        return false;
    }

    /** One {@link Command} method with its words or compiled patterns and the prefixes in force for it. */
    private final class Binding {

        private final Command command;
        private final String name;
        private final List<String> words;
        private final List<Pattern> patterns;
        private final List<String> prefixes;

        Binding(Command command, String name) {
            this.command = command;
            this.name = name;
            List<String> declared = new ArrayList<>(Arrays.asList(command.value()));
            declared.addAll(Arrays.asList(command.alias()));
            declared.removeIf(String::isEmpty);
            this.words = List.copyOf(declared);
            this.patterns = new ArrayList<>();
            if (command.kind() == Command.Kind.REGEX) {
                for (String source : this.words) {
                    try {
                        patterns.add(Pattern.compile(source));
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException(name + " has a @Command pattern that does not compile:"
                                + " \"" + source + "\"", e);
                    }
                }
            }
            if (this.words.isEmpty() && patterns.isEmpty()) {
                throw new IllegalArgumentException(name + " has an empty @Command value");
            }
            this.prefixes = command.prefix().length == 0
                    ? CommandRegistry.this.prefixes : List.of(command.prefix());
        }

        /** The context for this dispatch, or null when the message is not this command. */
        CommandContext context(QQEvent event) {
            String text = content(event);
            if (text == null) {
                return null;
            }
            String stripped = stripPrefix(text);
            if (stripped == null) {
                return null;
            }
            Role actual = role(event);
            if (!command.role().allows(actual)) {
                log.debug("{} needs {}, the sender is {}", name, command.role(),
                        actual == null ? "not a group member with a role" : actual.toString());
                return null;
            }
            return command.kind() == Command.Kind.REGEX
                    ? regexMatch(event, stripped, actual) : wordMatch(event, stripped, actual);
        }

        private CommandContext wordMatch(QQEvent event, String stripped, Role actual) {
            for (String word : words) {
                if (stripped.equals(word)) {
                    return new CommandContext(client, event, word, stripped, "", List.of(), actual);
                }
                boolean followedBySpace = stripped.length() > word.length() && stripped.startsWith(word)
                        && Character.isWhitespace(stripped.charAt(word.length()));
                if (followedBySpace) {
                    return new CommandContext(client, event, word, stripped,
                            stripped.substring(word.length()).trim(), List.of(), actual);
                }
            }
            return null;
        }

        private CommandContext regexMatch(QQEvent event, String stripped, Role actual) {
            for (Pattern pattern : patterns) {
                Matcher m = pattern.matcher(stripped);
                if (m.matches()) {
                    List<String> groups = new ArrayList<>();
                    for (int i = 1; i <= m.groupCount(); i++) {
                        groups.add(m.group(i));
                    }
                    return new CommandContext(client, event, pattern.pattern(), stripped, stripped, groups,
                            actual);
                }
            }
            return null;
        }

        private String stripPrefix(String text) {
            for (String prefix : prefixes) {
                if (prefix.isEmpty()) {
                    return text;
                }
                if (text.startsWith(prefix) && text.length() > prefix.length()) {
                    return text.substring(prefix.length()).trim();
                }
            }
            return null;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (command.kind() == Command.Kind.REGEX) {
                sb.append(patterns.get(0).pattern());
                if (patterns.size() > 1) {
                    sb.append(" (+").append(patterns.size() - 1).append(" more)");
                }
            } else {
                sb.append(words.get(0));
                if (words.size() > 1) {
                    sb.append(" | ").append(String.join(" | ", words.subList(1, words.size())));
                }
            }
            if (!prefixes.get(0).isEmpty()) {
                sb.append(" [prefix ").append(prefixes.get(0)).append(']');
            }
            if (command.role() != Role.ANY) {
                sb.append(" [").append(command.role()).append(']');
            }
            if (Strings.isNotBlank(command.description())) {
                sb.append(" - ").append(command.description());
            }
            return sb.toString();
        }
    }

    private static String content(QQEvent event) {
        JsonObject payload = event.rawObject();
        if (payload.get("content") == null || payload.get("content").isJsonNull()) {
            return null;
        }
        String content = payload.get("content").getAsString().trim();
        return content.isEmpty() ? null : content;
    }

    /** The group role the sender reports; null outside a group, or where {@code author} has none. */
    private static Role role(QQEvent event) {
        JsonObject author = event.rawObject().get("author") instanceof JsonObject a ? a : null;
        if (author == null || author.get("member_role") == null || author.get("member_role").isJsonNull()) {
            return null;
        }
        return Role.fromWire(author.get("member_role").getAsString());
    }
}
