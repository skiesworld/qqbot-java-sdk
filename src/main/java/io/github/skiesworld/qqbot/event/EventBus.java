package io.github.skiesworld.qqbot.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Fan-out of gateway events to listeners.
 *
 * <p>A throwing listener is logged and never breaks the connection or the other listeners. The
 * default dispatcher runs listeners on the caller thread; pass an {@link Executor} to move user code
 * off the gateway reader thread.
 */
public final class EventBus {

    /** Handle for removing a registration. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<EventType, List<Consumer<QQEvent>>> typed = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<QQEvent>>> byName = new ConcurrentHashMap<>();
    private final List<Consumer<QQEvent>> wildcard = new CopyOnWriteArrayList<>();
    private final Executor executor;

    public EventBus() {
        this(Runnable::run);
    }

    public EventBus(Executor executor) {
        this.executor = executor;
    }

    public Subscription on(EventType type, Consumer<QQEvent> listener) {
        List<Consumer<QQEvent>> list = typed.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    /** Type-safe form: the payload is deserialized into {@code dataType} before the listener runs. */
    public <D> Subscription on(EventType type, Class<D> dataType, Consumer<D> listener) {
        return on(type, event -> {
            D data = event.data(dataType);
            if (data != null) {
                listener.accept(data);
            }
        });
    }

    /** Listen by raw event name, useful for events newer than this SDK version. */
    public Subscription onName(String eventName, Consumer<QQEvent> listener) {
        List<Consumer<QQEvent>> list = byName.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    public Subscription onAny(Consumer<QQEvent> listener) {
        wildcard.add(listener);
        return () -> wildcard.remove(listener);
    }

    public int listenerCount() {
        int n = wildcard.size();
        for (List<Consumer<QQEvent>> l : typed.values()) {
            n += l.size();
        }
        for (List<Consumer<QQEvent>> l : byName.values()) {
            n += l.size();
        }
        return n;
    }

    public void dispatch(QQEvent event) {
        executor.execute(() -> run(event));
    }

    private void run(QQEvent event) {
        for (Consumer<QQEvent> l : typed.getOrDefault(event.type(), List.of())) {
            accept(l, event);
        }
        for (Consumer<QQEvent> l : byName.getOrDefault(event.name(), List.of())) {
            accept(l, event);
        }
        for (Consumer<QQEvent> l : wildcard) {
            accept(l, event);
        }
    }

    private static void accept(Consumer<QQEvent> listener, QQEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.error("event listener threw for {}", event.name(), e);
        }
    }
}
