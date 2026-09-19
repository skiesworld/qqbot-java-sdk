package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {

    private static QQEvent event(String type, String data) {
        JsonObject d = Json.parseLenient(data).getAsJsonObject();
        return new QQEvent("ID1", 0, 5L, type, EventType.from(type), d);
    }

    @Test
    void deliversToTypeListenersAndClosesSubscriptions() {
        EventBus bus = new EventBus();
        List<String> hits = new CopyOnWriteArrayList<>();
        EventBus.Subscription s = bus.on(EventType.C2C_MESSAGE_CREATE, e -> hits.add(e.name()));
        bus.on(EventType.FRIEND_ADD, e -> hits.add("friend"));

        bus.dispatch(event("C2C_MESSAGE_CREATE", "{\"content\":\"hi\"}"));
        bus.dispatch(event("FRIEND_ADD", "{\"openid\":\"U\"}"));
        assertEquals(List.of("C2C_MESSAGE_CREATE", "friend"), hits);

        s.close();
        bus.dispatch(event("C2C_MESSAGE_CREATE", "{\"content\":\"again\"}"));
        assertEquals(2, hits.size(), "a closed subscription stops receiving");
    }

    @Test
    void typedListenerDeserializesThePayload() {
        EventBus bus = new EventBus();
        AtomicReference<Payload> seen = new AtomicReference<>();
        bus.on(EventType.GROUP_AT_MESSAGE_CREATE, Payload.class, seen::set);
        bus.dispatch(event("GROUP_AT_MESSAGE_CREATE", "{\"content\":\"hello\",\"group_openid\":\"G1\"}"));
        assertNotNull(seen.get());
        assertEquals("hello", seen.get().content);
        assertEquals("G1", seen.get().groupOpenid);
    }

    @Test
    void listenerFailuresIsolateOtherListeners() {
        EventBus bus = new EventBus();
        AtomicReference<String> survivor = new AtomicReference<>();
        bus.on(EventType.C2C_MESSAGE_CREATE, e -> {
            throw new IllegalStateException("boom");
        });
        bus.on(EventType.C2C_MESSAGE_CREATE, e -> survivor.set(e.id()));
        bus.dispatch(event("C2C_MESSAGE_CREATE", "{}"));
        assertEquals("ID1", survivor.get());
    }

    @Test
    void unknownEventNamesStayReachable() {
        EventBus bus = new EventBus();
        AtomicReference<QQEvent> any = new AtomicReference<>();
        bus.on(EventType.UNKNOWN, e -> any.set(e));
        bus.dispatch(event("SOME_FUTURE_EVENT", "{\"x\":1}"));
        assertNotNull(any.get());
        assertEquals(EventType.UNKNOWN, any.get().type());
        assertEquals("SOME_FUTURE_EVENT", any.get().name());
        assertEquals(1, any.get().rawObject().get("x").getAsInt());
    }

    @Test
    void nameListenersCatchEventsWithoutAModelledType() {
        EventBus bus = new EventBus();
        List<String> names = new CopyOnWriteArrayList<>();
        bus.onName("AUDIO_START", e -> names.add(e.name()));
        bus.dispatch(event("AUDIO_START", "{\"channel_id\":\"C\"}"));
        bus.dispatch(event("AUDIO_FINISH", "{\"channel_id\":\"C\"}"));
        assertEquals(List.of("AUDIO_START"), names);
    }

    @Test
    void asyncDispatcherMovesWorkOffTheCallingThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "bus-test"));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        try {
            EventBus bus = new EventBus(pool::execute);
            bus.onAny(e -> {
                thread.set(Thread.currentThread().getName());
                done.countDown();
            });
            bus.dispatch(event("FRIEND_DEL", "{}"));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("bus-test", thread.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reportsListenerTotals() {
        EventBus bus = new EventBus();
        assertEquals(0, bus.listenerCount());
        bus.onAny(e -> { });
        bus.on(EventType.GUILD_CREATE, e -> { });
        bus.onName("X", e -> { });
        assertEquals(3, bus.listenerCount());
    }

    @Test
    void qqEventExposesEnvelopeAndConvenienceAccessors() {
        QQEvent e = event("C2C_MESSAGE_CREATE",
                "{\"id\":\"MSG1\",\"content\":\"text\",\"author\":{\"user_openid\":\"U7\"}}");
        assertEquals("text", e.rawObject().get("content").getAsString());
        assertEquals("U7", e.targetId());
        assertNotNull(e.raw());
        assertEquals("MSG1", e.rawObject().get("id").getAsString());
        assertTrue(e.toString().contains("C2C_MESSAGE_CREATE"), e.toString());
    }

    @SuppressWarnings("unused")
    private static final class Payload {
        String content;
        @com.google.gson.annotations.SerializedName("group_openid")
        String groupOpenid;
    }
}
