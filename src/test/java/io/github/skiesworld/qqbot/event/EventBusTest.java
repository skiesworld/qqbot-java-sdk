package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatch kernel: what order routes run in, what stopping the chain means, and what changes when the bus
 * runs on a pool instead of inline.
 */
class EventBusTest {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    private static QQEvent groupMessage(String group, String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("group_openid", group);
        payload.addProperty("content", text);
        return EventEnvelopes.of("ID", 0, 1L, "GROUP_MESSAGE_CREATE", payload, null);
    }

    @Test
    void routesRunByPriorityThenRegistrationOrder() {
        EventBus bus = new EventBus();
        bus.add(handled("late"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 10, false);
        bus.add(handled("first"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, -1, false);
        bus.add(handled("default"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 0, false);

        bus.dispatch(groupMessage("G", "hi"));
        assertEquals(List.of("first", "default", "late"), seen);
    }

    @Test
    void aBlockingRouteEndsTheChainAfterItHandledTheDispatch() {
        EventBus bus = new EventBus();
        bus.add(handled("blocking"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 0, true);
        bus.add(handled("after"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 1, false);

        bus.dispatch(groupMessage("G", "mine"));
        assertEquals(List.of("blocking"), seen);
    }

    @Test
    void aBlockingRouteThatDidNotHandleTheDispatchLeavesTheChainAlone() {
        EventBus bus = new EventBus();
        bus.add(event -> false, List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 0, true);
        bus.add(handled("reached"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 1, false);

        bus.dispatch(groupMessage("G", "not mine"));
        assertEquals(List.of("reached"), seen);
    }

    @Test
    void typedNameAndWildcardRoutesEachSeeTheSameDispatch() {
        EventBus bus = new EventBus();
        bus.on(EventType.FRIEND_ADD, e -> seen.add("typed"));
        bus.onName("FRIEND_ADD", e -> seen.add("byName"));
        bus.onAny(e -> seen.add("any"));

        bus.dispatch(EventEnvelopes.of(null, 0, null, "FRIEND_ADD", new JsonObject(), null));
        assertEquals(List.of("typed", "byName", "any"), seen);
        assertEquals(3, bus.listenerCount());
    }

    @Test
    void closingASubscriptionRemovesOnlyThatRoute() {
        EventBus bus = new EventBus();
        EventBus.Subscription gone = bus.on(EventType.C2C_MESSAGE_CREATE, e -> seen.add("gone"));
        bus.on(EventType.C2C_MESSAGE_CREATE, e -> seen.add("stays"));

        gone.close();
        bus.dispatch(EventEnvelopes.of(null, 0, null, "C2C_MESSAGE_CREATE", new JsonObject(), null));
        assertEquals(List.of("stays"), seen);
        assertEquals(1, bus.listenerCount());
    }

    @Test
    void aThrowingRouteNeitherBreaksTheChainNorCountsAsHandled() {
        EventBus bus = new EventBus();
        bus.add(event -> {
            throw new IllegalStateException("user code");
        }, List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 0, true);
        bus.add(handled("next"), List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 1, false);

        bus.dispatch(groupMessage("G", "hi"));
        assertEquals(List.of("next"), seen);
    }

    @Test
    void aRouteRegisteredForNothingCouldNeverRun() {
        EventBus bus = new EventBus();
        assertThrows(IllegalArgumentException.class,
                () -> bus.add(handled("never"), List.of(), List.of(), false, 0, false));
    }

    @Test
    void onAPoolASlowRouteDoesNotDelayTheSiblingRouteOfTheSameEvent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            EventBus bus = new EventBus(pool);
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch siblingRan = new CountDownLatch(1);
            CountDownLatch slowDone = new CountDownLatch(1);
            bus.add(event -> {
                slowStarted.countDown();
                await(release);
                seen.add("slow:done");
                slowDone.countDown();
                return true;
            }, List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 0, false);
            bus.add(event -> {
                seen.add("sibling");
                siblingRan.countDown();
                return true;
            }, List.of(EventType.GROUP_MESSAGE_CREATE), List.of(), false, 1, false);

            bus.dispatch(groupMessage("G1", "one"));
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS), "the first route started");
            assertTrue(siblingRan.await(2, TimeUnit.SECONDS),
                    "the sibling route ran while the first one was still working");
            release.countDown();
            assertTrue(slowDone.await(2, TimeUnit.SECONDS), "the slow route finished");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameConversationAndRouteKeepArrivalOrderWhileOtherConversationsRunAlong() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            EventBus bus = new EventBus(pool);
            CountDownLatch done = new CountDownLatch(3);
            bus.on(EventType.GROUP_MESSAGE_CREATE, event -> {
                String group = event.conversationId();
                String text = event.rawObject().get("content").getAsString();
                if ("slow".equals(text)) {
                    await(new CountDownLatch(1));
                }
                seen.add(group + ':' + text);
                done.countDown();
            });
            bus.dispatch(groupMessage("G1", "slow"));
            bus.dispatch(groupMessage("G1", "second"));
            bus.dispatch(groupMessage("G2", "other"));

            assertTrue(done.await(5, TimeUnit.SECONDS), "all three finished: " + seen);
            assertTrue(seen.indexOf("G1:slow") < seen.indexOf("G1:second"),
                    "the two dispatches of one conversation finished in arrival order: " + seen);
            assertEquals(3, seen.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private EventBus.Listener handled(String what) {
        return event -> {
            seen.add(what);
            return true;
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aDispatchWithNoListenerTakesNoTaskAtAll() {
        AtomicInteger submissions = new AtomicInteger();
        EventBus bus = new EventBus(command -> {
            submissions.incrementAndGet();
            command.run();
        });
        bus.dispatch(groupMessage("G", "nobody is listening"));
        assertEquals(0, submissions.get());
    }
}
