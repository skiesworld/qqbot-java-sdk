package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.util.Json;

/** A gateway dispatch: the envelope plus lazy access to its typed payload. */
public final class QQEvent {

    private final String id;
    private final int op;
    private final Long seq;
    private final String name;
    private final EventType type;
    private final JsonElement data;

    public QQEvent(String id, int op, Long seq, String name, EventType type, JsonElement data) {
        this.id = id;
        this.op = op;
        this.seq = seq;
        this.name = name;
        this.type = type;
        this.data = data;
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

    /** Best available conversation target: the group, user or channel the event concerns. */
    public String targetId() {
        JsonObject o = rawObject();
        for (String key : new String[]{"group_openid", "user_openid", "channel_id", "guild_id", "group_id",
                "openid", "to_openid"}) {
            if (o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsString();
            }
        }
        if (o.has("author") && o.get("author").isJsonObject()) {
            JsonObject author = o.getAsJsonObject("author");
            for (String key : new String[]{"user_openid", "member_openid", "id"}) {
                if (author.has(key) && !author.get(key).isJsonNull()) {
                    return author.get(key).getAsString();
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "QQEvent{" + name + " id=" + id + " seq=" + seq + "}";
    }
}
