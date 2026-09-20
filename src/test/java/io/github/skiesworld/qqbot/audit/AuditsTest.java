package io.github.skiesworld.qqbot.audit;

import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventEnvelopes;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Waiting for a review verdict that the platform owes later, and what "it has not come yet" is allowed to mean.
 */
class AuditsTest {

    private final EventBus bus = new EventBus();
    private final Audits audits = new Audits(bus);

    private void verdict(String event, String payload) {
        bus.dispatch(EventEnvelopes.of("E1", 0, 1L, event, Json.parseLenient(payload), null));
    }

    @Test
    void aPassingVerdictCompletesTheWait() throws Exception {
        var waiting = audits.resultOf("A1", Duration.ofSeconds(5));

        verdict("MESSAGE_AUDIT_PASS", "{\"audit_id\":\"A1\",\"message_id\":\"M1\"}");

        AuditOutcome outcome = waiting.get(2, TimeUnit.SECONDS);
        assertEquals(AuditStatus.PASSED, outcome.status());
        assertTrue(outcome.passed());
        assertEquals("M1", outcome.event().rawObject().get("message_id").getAsString());
    }

    @Test
    void aRejectedVerdictArrivesThroughTheSameWait() throws Exception {
        var waiting = audits.resultOf("A2", Duration.ofSeconds(5));

        verdict("MESSAGE_AUDIT_REJECT", "{\"audit_id\":\"A2\",\"message_id\":\"M2\"}");

        assertEquals(AuditStatus.REJECTED, waiting.get(2, TimeUnit.SECONDS).status());
    }

    @Test
    void anUnrelatedDispatchLeavesTheWaitOpen() throws Exception {
        var waiting = audits.resultOf("A3", Duration.ofSeconds(5));

        verdict("MESSAGE_AUDIT_PASS", "{\"audit_id\":\"OTHER\",\"message_id\":\"M3\"}");
        verdict("FRIEND_ADD", "{\"openid\":\"U1\"}");

        assertFalse(waiting.isDone());
    }

    @Test
    void anExpiredWaitIsAStatusAndNotAFailure() throws Exception {
        AuditOutcome outcome = audits.resultOf("A4", Duration.ofMillis(80)).get(2, TimeUnit.SECONDS);

        assertEquals(AuditStatus.TIMED_OUT, outcome.status());
        assertEquals("A4", outcome.auditId());
        assertEquals(null, outcome.event());
    }

    @Test
    void twoWaitersOnOneVerdictBothHearItAndTheBusStopsCarryingThem() throws Exception {
        var first = audits.resultOf("A5", Duration.ofSeconds(5));
        var second = audits.resultOf("A5", Duration.ofSeconds(5));
        assertEquals(2, audits.waiting());

        verdict("MESSAGE_AUDIT_PASS", "{\"audit_id\":\"A5\"}");

        assertTrue(first.get(2, TimeUnit.SECONDS).passed());
        assertTrue(second.get(2, TimeUnit.SECONDS).passed());
        assertEquals(0, audits.waiting());
    }

    @Test
    void closingReleasesTheListenerRatherThanLeavingItOnTheBus() throws Exception {
        var waiting = audits.resultOf("A6", Duration.ofSeconds(5));
        assertTrue(bus.listenerCount() > 0, "the watch is installed on first use");

        audits.close();

        assertEquals(0, bus.listenerCount());
        assertEquals(AuditStatus.TIMED_OUT, waiting.get(2, TimeUnit.SECONDS).status());
    }

    @Test
    void waitingWithoutAnIdIsRefusedBecauseItCouldNeverBeSettled() {
        assertThrows(IllegalArgumentException.class, () -> audits.resultOf(" ", Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> audits.resultOf("A7", null));
    }

    @Test
    void aTimeoutOfZeroStillReportsAStatus() throws Exception {
        assertEquals(AuditStatus.TIMED_OUT,
                audits.resultOf("A8", Duration.ofNanos(1)).get(2, TimeUnit.SECONDS).status());
    }

    @Test
    void noWaiterIsLeftAfterATimeoutExpires() throws Exception {
        audits.resultOf("A9", Duration.ofMillis(50)).get(2, TimeUnit.SECONDS);
        assertEquals(0, audits.waiting());
    }

    @Test
    void aVerdictThatComesAfterTheWaitExpiredChangesNothing() throws Exception {
        var waiting = audits.resultOf("A10", Duration.ofMillis(50));
        waiting.get(2, TimeUnit.SECONDS);

        verdict("MESSAGE_AUDIT_PASS", "{\"audit_id\":\"A10\"}");

        assertEquals(AuditStatus.TIMED_OUT, waiting.get(2, TimeUnit.SECONDS).status());
    }

    @Test
    void anExpiredWaitDoesNotCompleteExceptionally() throws Exception {
        var waiting = audits.resultOf("A11", Duration.ofMillis(50));

        waiting.get(2, TimeUnit.SECONDS);

        assertFalse(waiting.isCompletedExceptionally());
    }
}
