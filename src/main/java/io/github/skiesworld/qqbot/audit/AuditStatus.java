package io.github.skiesworld.qqbot.audit;

/** What a review verdict said about one send. */
public enum AuditStatus {

    /** {@code MESSAGE_AUDIT_PASS}: the message went out. */
    PASSED,

    /** {@code MESSAGE_AUDIT_REJECT}: it did not. */
    REJECTED,

    /** Nothing arrived within the wait, which is not the same as a verdict. */
    TIMED_OUT
}
