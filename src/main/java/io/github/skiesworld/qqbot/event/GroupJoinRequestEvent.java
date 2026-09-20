package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import io.github.skiesworld.qqbot.model.request.ApproveGroupJoinRequestRequest;

import java.util.List;

/**
 * {@code GROUP_JOIN_REQUEST}: somebody wants into a group, and nothing happens until the bot answers, so this is
 * the one envelope whose event carries its own action.
 *
 * <p>The bot has to be a group administrator for the call to go through; the platform's own auto-approval
 * strategy may also have settled the request before this event was pushed, in which case the answer is a no-op
 * error code rather than a membership change.
 */
public final class GroupJoinRequestEvent extends QQNoticeEvent {

    public GroupJoinRequestEvent(String id, int op, Long seq, String name, EventType type, JsonElement data,
            Outbound outbound) {
        super(id, op, seq, name, type, data, outbound);
    }

    /** This one event. */
    public static List<EventType> eventTypes() {
        return List.of(EventType.GROUP_JOIN_REQUEST);
    }

    /** The request token the approval call needs; the payload's {@code join_request_id}. */
    public String joinRequestId() {
        return EventValues.text(rawObject(), "join_request_id");
    }

    /** The applicant. */
    public String applicant() {
        return EventValues.text(rawObject(), "member_openid");
    }

    /** The group the request is about. */
    public String groupId() {
        return EventValues.text(rawObject(), "group_openid");
    }

    /** Why the platform wants this member in, as reported; may be absent. */
    public String verifyMessage() {
        return EventValues.text(EventValues.child(rawObject(), "verify_info"), "verify_message");
    }

    public void approve() {
        answer("approve", null, false);
    }

    /** Turn the request down, with the reason the applicant sees. */
    public void deny(String reason) {
        answer("decline", reason, false);
    }

    /** Turn it down and add the applicant to the group's member blacklist, which stops them reapplying. */
    public void deny(String reason, boolean addToBlacklist) {
        answer("decline", reason, addToBlacklist);
    }

    private void answer(String op, String reason, boolean blacklist) {
        String group = groupId();
        String applicant = applicant();
        Outbound outbound = outbound();
        if (outbound == null) {
            throw new IllegalStateException(name() + " arrived on an EventBus no bot attached, so it cannot be"
                    + " answered; dispatch it through a QQBotClient");
        }
        if (group == null || applicant == null || joinRequestId() == null) {
            throw new IllegalStateException(name() + " payload is missing "
                    + (group == null ? "group_openid" : applicant == null ? "member_openid" : "join_request_id")
                    + ", so there is nothing to answer");
        }
        ApproveGroupJoinRequestRequest request = new ApproveGroupJoinRequestRequest();
        request.op = op;
        request.joinRequestId = joinRequestId();
        request.rejectReason = reason;
        request.addToMemberBlacklist = blacklist ? Boolean.TRUE : null;
        outbound.api().group().approveGroupJoinRequest(group, applicant, request);
    }
}
