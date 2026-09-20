package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import io.github.skiesworld.qqbot.model.InteractionData;
import io.github.skiesworld.qqbot.util.Json;

import java.util.List;

/**
 * {@code INTERACTION_CREATE}: somebody pressed a button or picked a menu entry, and the client keeps that control
 * pending until the bot acknowledges it. Acknowledge late and the user has already given up on it, so do the
 * answer first and the work after.
 */
public final class InteractionEvent extends QQNoticeEvent {

    /** The code that means "shown", which is what stops the pending state. */
    public static final long CODE_SHOWN = 1L;

    public InteractionEvent(String id, int op, Long seq, String name, EventType type, JsonElement data,
            Outbound outbound) {
        super(id, op, seq, name, type, data, outbound);
    }

    /** This one event. */
    public static List<EventType> eventTypes() {
        return List.of(EventType.INTERACTION_CREATE);
    }

    /** The interaction token {@link #acknowledge()} answers with. */
    public String interactionId() {
        return EventValues.text(rawObject(), "id");
    }

    /** What kind of control was used; the docs number buttons 11 and the shortcut menu 12. */
    public Long interactionType() {
        JsonElement type = rawObject().get("type");
        return type == null || !type.isJsonPrimitive() ? null : type.getAsLong();
    }

    /** The payload of the control: the resolved button data, its id and the authorisation code. */
    public InteractionData interactionData() {
        JsonElement data = rawObject().get("data");
        return data == null || !data.isJsonObject() ? null : Json.GSON.fromJson(data, InteractionData.class);
    }

    /** Tell the platform the interaction was handled, so the control stops spinning. */
    public void acknowledge() {
        acknowledge(CODE_SHOWN);
    }

    public void acknowledge(long code) {
        String interactionId = interactionId();
        Outbound outbound = outbound();
        if (outbound == null) {
            throw new IllegalStateException(name() + " arrived on an EventBus no bot attached, so it cannot be"
                    + " answered; dispatch it through a QQBotClient");
        }
        if (interactionId == null) {
            throw new IllegalStateException(name() + " payload carries no interaction id to acknowledge");
        }
        outbound.api().interactions().acknowledgeInteraction(interactionId, code);
    }
}
