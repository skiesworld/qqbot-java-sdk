package io.github.skiesworld.qqbot.audit;

import io.github.skiesworld.qqbot.event.QQEvent;

import java.util.Objects;

/**
 * One review verdict, or the absence of one within the wait.
 *
 * <p>{@link #event()} is the {@code MESSAGE_AUDIT_PASS} / {@code MESSAGE_AUDIT_REJECT} dispatch it came from, so
 * the reason, the hint and the affected message id are all reachable; it is null when the answer was
 * {@link AuditStatus#TIMED_OUT}, which is a statement about the wait and not about the message.
 */
public final class AuditOutcome {

    private final String auditId;
    private final AuditStatus status;
    private final QQEvent event;

    AuditOutcome(String auditId, AuditStatus status, QQEvent event) {
        this.auditId = Objects.requireNonNull(auditId, "auditId");
        this.status = Objects.requireNonNull(status, "status");
        this.event = event;
    }

    static AuditOutcome of(String auditId, QQEvent event) {
        return new AuditOutcome(auditId,
                event.type() == io.github.skiesworld.qqbot.event.EventType.MESSAGE_AUDIT_PASS
                        ? AuditStatus.PASSED : AuditStatus.REJECTED, event);
    }

    static AuditOutcome timedOut(String auditId) {
        return new AuditOutcome(auditId, AuditStatus.TIMED_OUT, null);
    }

    public String auditId() {
        return auditId;
    }

    public AuditStatus status() {
        return status;
    }

    public boolean passed() {
        return status == AuditStatus.PASSED;
    }

    public QQEvent event() {
        return event;
    }

    @Override
    public String toString() {
        return "AuditOutcome{" + auditId + ' ' + status + '}';
    }
}
