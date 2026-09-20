package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.util.Json;

/**
 * A gateway dispatch: the envelope plus lazy access to its typed payload.
 *
 * <p>What every dispatch can be asked is exactly what this class offers — its identity, its raw {@code d} member,
 * and the conversation it belongs to. Everything else depends on which event arrived, and comes from a subclass
 * {@link EventEnvelopes} builds for it: {@link QQMessageEvent} for the events carrying text,
 * {@link QQNoticeEvent} for the ones reporting something that happened.
 */
public class QQEvent {

    private static final String[] CONVERSATION_KEYS = {"group_openid", "user_openid", "channel_id", "guild_id",
            "group_id", "openid", "to_openid"};
    private static final String[] AUTHOR_KEYS = {"user_openid", "member_openid", "id"};

    private final String id;
    private final int op;
    private final Long seq;
    private final String name;
    private final EventType type;
    private final JsonElement data;
    private final Outbound outbound;

    public QQEvent(String id, int op, Long seq, String name, EventType type, JsonElement data) {
        this(id, op, seq, name, type, data, null);
    }

    public QQEvent(String id, int op, Long seq, String name, EventType type, JsonElement data,
            Outbound outbound) {
        this.id = id;
        this.op = op;
        this.seq = seq;
        this.name = name;
        this.type = type;
        this.data = data;
        this.outbound = outbound;
    }

    /** Event id. Passive replies pass it as {@code event_id}. */
    public String id() {
        return id;
    }

    public int op() {
        return op;
    }

    /** Gateway sequence number {@code s}; the value to resume from. */
    public Long seq() {
        return seq;
    }

    /** Raw {@code t} value, including names this SDK version does not model. */
    public String name() {
        return name;
    }

    public EventType type() {
        return type;
    }

    /** Raw {@code d} member. */
    public JsonElement raw() {
        return data;
    }

    public JsonObject rawObject() {
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }

    public <T> T data(Class<T> dataType) {
        return Json.GSON.fromJson(data, dataType);
    }

    /** Bind to the model class this SDK generates for the event, or null when {@code d} is not an object. */
    @SuppressWarnings("unchecked")
    public <T> T data() {
        Class<T> resolved = (Class<T>) EventModels.dataClass(type, name);
        return resolved == Void.class ? null : Json.GSON.fromJson(data, resolved);
    }

    /**
     * The conversation this dispatch belongs to: the group, user, channel or guild the payload identifies. Null
     * when it identifies none, which is true of the session lifecycle events.
     */
    public String conversationId() {
        JsonObject payload = rawObject();
        String direct = EventValues.firstText(payload, java.util.List.of(CONVERSATION_KEYS));
        return direct != null ? direct : EventValues.firstText(EventValues.child(payload, "author"),
                java.util.List.of(AUTHOR_KEYS));
    }

    /** Which kind of conversation {@link #conversationId()} reported, and which endpoint answers it. */
    public ReplyTarget scene() {
        return ReplyTarget.of(this);
    }

    /** The bot this dispatch arrived on, or null when the bus had none attached. */
    public Outbound outbound() {
        return outbound;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + name + " id=" + id + " seq=" + seq + "}";
    }
}
