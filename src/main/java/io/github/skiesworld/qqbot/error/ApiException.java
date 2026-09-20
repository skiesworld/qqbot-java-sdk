package io.github.skiesworld.qqbot.error;

/**
 * An OpenAPI call failed. The platform reports failures through {@code err_code} in the JSON body;
 * the HTTP status alone is not sufficient because asynchronous successes (201/202) also carry a body.
 */
public class ApiException extends QQBotException {

    private final int errCode;
    private final int httpStatus;
    private final String traceId;
    private final String rawBody;

    public ApiException(int errCode, String message, int httpStatus, String traceId, String rawBody) {
        super("QQ OpenAPI call failed: err_code=" + errCode + ", message=" + message
                + ", http=" + httpStatus + (traceId == null ? "" : ", trace_id=" + traceId));
        this.errCode = errCode;
        this.httpStatus = httpStatus;
        this.traceId = traceId;
        this.rawBody = rawBody;
    }

    /** Platform business error code. {@code 0} means success. */
    public int errCode() {
        return errCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Link tracing id, quote it when asking the platform for help. */
    public String traceId() {
        return traceId;
    }

    public String rawBody() {
        return rawBody;
    }

    /**
     * Whether this failure means the message went to a reviewer instead of failing — the documented audit codes,
     * or a response that carried an audit id. The verdict comes later on the event bus, so this is not "not sent".
     */
    public boolean isAuditPending() {
        return AuditPendingException.isAuditCode(errCode) || auditId() != null;
    }

    /** The {@code message_audit.audit_id} this failure reported, or null when it reported none. */
    public String auditId() {
        return AuditPendingException.auditIdOf(rawBody);
    }
}
