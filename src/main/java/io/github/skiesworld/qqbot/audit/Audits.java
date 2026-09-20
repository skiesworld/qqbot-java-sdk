package io.github.skiesworld.qqbot.audit;

import com.google.gson.JsonElement;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.util.Strings;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Turning a send that went into review back into something you can wait for.
 *
 * <p>A message the platform audits is accepted first and judged later: the send answers with an
 * {@code audit_id} (see {@link io.github.skiesworld.qqbot.error.AuditPendingException}) and the verdict arrives on
 * the bus as {@code MESSAGE_AUDIT_PASS} or {@code MESSAGE_AUDIT_REJECT}. This class listens for that verdict once
 * and hands it to whoever asked:
 *
 * <pre>{@code
 * try {
 *     bot.api().c2c().sendC2CMessage(openid, body);
 * } catch (AuditPendingException e) {
 *     bot.audits().resultOf(e.auditId(), Duration.ofMinutes(5))
 *        .thenAccept(o -> log.info("audit {} -> {}", o.auditId(), o.status()));
 * }
 * }</pre>
 *
 * <p>The verdict may never come — reviews are not promised a deadline — so a wait answers
 * {@link AuditStatus#TIMED_OUT} instead of hanging or failing exceptionally. Each {@code audit_id} can be waited
 * for more than once; the listener leaves the bus when the bot closes.
 */
public final class Audits implements AutoCloseable {

    private final EventBus bus;
    private final Map<String, List<CompletableFuture<AuditOutcome>>> waiting = new ConcurrentHashMap<>();
    private volatile EventBus.Subscription watcher;

    public Audits(EventBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    /** The verdict for {@code auditId}, completed on the dispatch thread that carried it. */
    public CompletableFuture<AuditOutcome> resultOf(String auditId, Duration timeout) {
        if (Strings.isBlank(auditId)) {
            throw new IllegalArgumentException("an audit id is required; read it off AuditPendingException#auditId()");
        }
        Objects.requireNonNull(timeout, "timeout");
        watchOnce();
        CompletableFuture<AuditOutcome> future = new CompletableFuture<>();
        waiting.compute(auditId, (key, futures) -> {
            List<CompletableFuture<AuditOutcome>> list = futures == null ? new CopyOnWriteArrayList<>() : futures;
            list.add(future);
            return list;
        });
        future.whenComplete((outcome, error) -> waiting.computeIfPresent(auditId, (key, futures) -> {
            futures.remove(future);
            return futures.isEmpty() ? null : futures;
        }));
        return future.completeOnTimeout(AuditOutcome.timedOut(auditId), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** How many verdicts are being waited for right now. */
    public int waiting() {
        return waiting.values().stream().mapToInt(List::size).sum();
    }

    private void watchOnce() {
        if (watcher != null) {
            return;
        }
        synchronized (this) {
            if (watcher == null) {
                watcher = bus.onAny(this::settled);
            }
        }
    }

    private void settled(QQEvent event) {
        if (event.type() != EventType.MESSAGE_AUDIT_PASS && event.type() != EventType.MESSAGE_AUDIT_REJECT) {
            return;
        }
        String auditId = auditId(event);
        if (auditId == null) {
            return;
        }
        for (CompletableFuture<AuditOutcome> future :
                waiting.getOrDefault(auditId, List.of())) {
            future.complete(AuditOutcome.of(auditId, event));
        }
    }

    @Override
    public void close() {
        EventBus.Subscription subscription = watcher;
        if (subscription != null) {
            subscription.close();
            watcher = null;
        }
        waiting.values().forEach(list -> list.forEach(future ->
                future.complete(AuditOutcome.timedOut("closed"))));
        waiting.clear();
    }

    /** The {@code audit_id} a verdict event carries; the payload models do not name it. */
    private static String auditId(QQEvent event) {
        JsonElement payload = event.raw();
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonElement auditId = payload.getAsJsonObject().get("audit_id");
        return auditId == null || auditId.isJsonNull() ? null : auditId.getAsString();
    }
}
