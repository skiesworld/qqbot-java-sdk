package io.github.skiesworld.qqbot.event;

import io.github.skiesworld.qqbot.message.MessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fan-out of gateway events to listeners.
 *
 * <p>Routes run in {@code (priority, registration order)} and a route that was registered with
 * {@code block = true} ends the chain after it runs, which is how a command says "this message is mine". A route
 * reports whether it handled the dispatch, so one that skipped — text that matched no command, an argument that
 * would not bind — neither counts as handled nor stops anything.
 *
 * <p>What the {@link Executor} does with a dispatch is what makes the bus synchronous or not. The default
 * {@code Runnable::run} runs the whole chain on the thread that received the event. Any other executor gets one
 * task per conversation to keep the chain's order, and inside it one task per route per conversation, so a slow
 * handler delays only its own earlier calls in the same conversation — never a sibling route listening to the
 * same event. A blocking route is the exception: it asked to be the end of the chain, so it runs inline.
 *
 * <p>A throwing route is logged and never breaks the connection or the other routes.
 */
public final class EventBus {

    /** Handle for removing a registration. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** One dispatch, answered once. Return true when this route took the event. */
    public interface Listener {

        boolean handle(QQEvent event);

        /** A listener that always counts as having handled the dispatch. */
        static Listener of(Consumer<QQEvent> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            return event -> {
                consumer.accept(event);
                return true;
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final Comparator<Route> IN_ORDER = Comparator.comparingInt(Route::priority)
            .thenComparingInt(Route::sequence);

    private final Map<EventType, List<Route>> typed = new ConcurrentHashMap<>();
    private final Map<String, List<Route>> byName = new ConcurrentHashMap<>();
    private final List<Route> wildcard = new CopyOnWriteArrayList<>();
    private final Map<String, Lane> lanes = new HashMap<>();
    private final AtomicInteger routes = new AtomicInteger();
    private final Executor executor;
    private volatile Outbound outbound;

    public EventBus() {
        this(Runnable::run);
    }

    /**
     * @param executor where a dispatch runs; {@code Runnable::run} keeps user code on the receiving thread,
     *                 which is what the default does
     */
    public EventBus(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public Subscription on(EventType type, Consumer<QQEvent> listener) {
        return add(Listener.of(listener), List.of(type), List.of(), false, 0, false);
    }

    /** Type-safe form: the payload is deserialized into {@code dataType} before the listener runs. */
    public <D> Subscription on(EventType type, Class<D> dataType, Consumer<D> listener) {
        return add(event -> {
            D data = event.data(dataType);
            if (data == null) {
                return false;
            }
            listener.accept(data);
            return true;
        }, List.of(type), List.of(), false, 0, false);
    }

    /** Listen by raw event name, useful for events newer than this SDK version. */
    public Subscription onName(String eventName, Consumer<QQEvent> listener) {
        return add(Listener.of(listener), List.of(), List.of(eventName), false, 0, false);
    }

    public Subscription onAny(Consumer<QQEvent> listener) {
        return add(Listener.of(listener), List.of(), List.of(), true, 0, false);
    }

    /**
     * Register one route for a set of events, at {@code priority} (lower runs first), optionally ending the
     * chain after it handles a dispatch. Empty {@code types} and {@code names} with {@code any} listens to
     * every dispatch.
     */
    public Subscription add(Listener listener, List<EventType> types, List<String> names, boolean any,
            int priority, boolean block) {
        Objects.requireNonNull(listener, "listener");
        if (types.isEmpty() && names.isEmpty() && !any) {
            throw new IllegalArgumentException("a route with no events to listen to would never run");
        }
        Route route = new Route(routes.incrementAndGet(), priority, block, listener, types, names, any);
        if (any) {
            wildcard.add(route);
        }
        for (EventType type : types) {
            typed.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(route);
        }
        for (String name : names) {
            byName.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(route);
        }
        return () -> {
            wildcard.remove(route);
            typed.values().forEach(list -> list.remove(route));
            byName.values().forEach(list -> list.remove(route));
        };
    }

    /**
     * Register every {@link io.github.skiesworld.qqbot.handler.On} method of {@code handler}, with parameters
     * filled by type.
     *
     * <p>{@code Api}/{@code QQBotClient} parameters need the client and therefore cannot be bound through this
     * entry point; use {@code bot.handlers().register(handler)} for those.
     */
    public Subscription register(Object handler) {
        return new io.github.skiesworld.qqbot.handler.HandlerRegistry(this).register(handler);
    }

    /** The bot whose envelopes dispatched here answer through; set once by that client. */
    public void outbound(Outbound outbound) {
        Outbound previous = this.outbound;
        if (previous != null && previous != outbound) {
            log.debug("this bus was already answering through another bot; replies now go out as the new one");
        }
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    /** Null until a client attaches itself, after which an envelope can answer the dispatch it came from. */
    public Outbound outbound() {
        return outbound;
    }

    public int listenerCount() {
        LinkedHashSet<Route> all = new LinkedHashSet<>(wildcard);
        typed.values().forEach(all::addAll);
        byName.values().forEach(all::addAll);
        return all.size();
    }

    public void dispatch(QQEvent event) {
        List<Route> candidates = candidates(event);
        if (candidates.isEmpty()) {
            return;
        }
        // the chain itself runs on the conversation's strip, so two dispatches of one conversation cannot
        // interleave and hand their routes over out of order
        submit(conversationStrip(event), () -> chain(event, candidates));
    }

    /** The chain lane walks the routes in order, handing each one to its own lane. */
    private void chain(QQEvent event, List<Route> candidates) {
        for (Route route : candidates) {
            if (route.block) {
                if (handle(route, event)) {
                    return;
                }
                continue;
            }
            submit(strip(route, event), () -> handle(route, event));
        }
    }

    private boolean handle(Route route, QQEvent event) {
        try {
            return route.listener.handle(event);
        } catch (RuntimeException e) {
            log.error("event listener threw for {}", event.name(), e);
            return false;
        }
    }

    private List<Route> candidates(QQEvent event) {
        LinkedHashSet<Route> found = new LinkedHashSet<>();
        found.addAll(typed.getOrDefault(event.type(), List.of()));
        found.addAll(byName.getOrDefault(event.name(), List.of()));
        found.addAll(wildcard);
        List<Route> ordered = new ArrayList<>(found);
        ordered.sort(IN_ORDER);
        return ordered;
    }

    /** A route and a conversation, which is the pair that must not run out of order. */
    private static String strip(Route route, QQEvent event) {
        String conversation = event.conversationId();
        return "r" + route.sequence + '@' + (conversation == null ? event.name() : conversation);
    }

    /** One conversation's arrivals, in the order they came: the chain of each is handed over from here. */
    private static String conversationStrip(QQEvent event) {
        String conversation = event.conversationId();
        return "c@" + (conversation == null ? event.name() : conversation);
    }

    /** Run {@code task} after the earlier tasks of the same strip, on the configured executor. */
    private void submit(String strip, Runnable task) {
        Lane lane;
        boolean start;
        synchronized (lanes) {
            lane = lanes.computeIfAbsent(strip, Lane::new);
            lane.queued.addLast(task);
            start = !lane.running;
            lane.running = true;
        }
        if (start) {
            executor.execute(() -> drain(lane));
        }
    }

    private void drain(Lane lane) {
        while (true) {
            Runnable task;
            synchronized (lanes) {
                task = lane.queued.pollFirst();
                if (task == null) {
                    lane.running = false;
                    lanes.remove(lane.key, lane);
                    return;
                }
            }
            task.run();
        }
    }

    /**
     * One strip's pending tasks and whether an executor thread is already working through them. Everything is
     * touched under the {@code lanes} monitor, so a strip can never be split across two live lanes: the order
     * events arrived in is the order their tasks run in.
     */
    private static final class Lane {
        private final String key;
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        private boolean running;

        Lane(String key) {
            this.key = key;
        }
    }

    private static final class Route {
        private final int sequence;
        private final int priority;
        private final boolean block;
        private final Listener listener;
        private final List<EventType> types;
        private final List<String> names;
        private final boolean any;

        Route(int sequence, int priority, boolean block, Listener listener, List<EventType> types,
                List<String> names, boolean any) {
            this.sequence = sequence;
            this.priority = priority;
            this.block = block;
            this.listener = listener;
            this.types = List.copyOf(types);
            this.names = List.copyOf(names);
            this.any = any;
        }

        int priority() {
            return priority;
        }

        int sequence() {
            return sequence;
        }
    }
}
