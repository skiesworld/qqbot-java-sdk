package io.github.skiesworld.qqbot.error;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.util.Json;

/**
 * The platform took the message but put it in front of a reviewer, so nothing was delivered yet — which is a
 * success of a kind, and asynchronous: the verdict arrives later as {@code MESSAGE_AUDIT_PASS} or
 * {@code MESSAGE_AUDIT_REJECT} on the event bus.
 *
 * <p>Thrown for the documented audit {@code err_code}s and for a send response that carries
 * {@code message_audit.audit_id}, whether the HTTP status said 201, 202 or 400. Read {@link #auditId()} and wait
 * for the verdict with {@code bot.audits().resultOf(auditId, timeout)}; {@link #httpStatus()} and
 * {@link #errCode()} still say what the call itself reported.
 */
public class AuditPendingException extends ApiException {

    private static final int AUDIT_ON_PUSH = 304023;
    private static final int AUDIT_ON_REPLY = 304024;

    private final String auditId;

    public AuditPendingException(String auditId, int errCode, String message, int httpStatus, String traceId,
            String rawBody) {
        super(errCode, message, httpStatus, traceId, rawBody);
        this.auditId = auditId;
    }

    /** The token the verdict event will carry, or null when the response reported none. */
    public String auditId() {
        return auditId;
    }

    public boolean isAuditPending() {
        return true;
    }

    /** Whether this failure means "in review" rather than "not sent": the two codes, or an audit id. */
    public static boolean isAuditCode(int errCode) {
        return errCode == AUDIT_ON_PUSH || errCode == AUDIT_ON_REPLY;
    }

    /** The {@code message_audit.audit_id} of a response body, or null. */
    public static String auditIdOf(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = Json.parseLenient(rawBody);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            JsonElement holder = obj.get("message_audit");
            JsonObject audit = holder instanceof JsonObject asObject ? asObject : obj;
            JsonElement auditId = audit.get("audit_id");
            return auditId == null || auditId.isJsonNull() ? null : auditId.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
