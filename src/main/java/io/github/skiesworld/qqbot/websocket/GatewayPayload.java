package io.github.skiesworld.qqbot.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.util.Json;

/** The single envelope used by both directions of the gateway: {@code {id, op, s, t, d}}. */
public final class GatewayPayload {

    private final String id;
    private final int op;
    private final Long seq;
    private final String type;
    private final JsonElement data;

    public GatewayPayload(String id, int op, Long seq, String type, JsonElement data) {
        this.id = id;
        this.op = op;
        this.seq = seq;
        this.type = type;
        this.data = data == null ? JsonNull.INSTANCE : data;
    }

    public static GatewayPayload parse(String json) {
        JsonObject o = Json.parseLenient(json).getAsJsonObject();
        return new GatewayPayload(
                str(o, "id"),
                o.has("op") ? o.get("op").getAsInt() : -1,
                o.has("s") && !o.get("s").isJsonNull() ? o.get("s").getAsLong() : null,
                str(o, "t"),
                o.has("d") ? o.get("d") : JsonNull.INSTANCE);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** {@code {"op":n,"d":{...}}} envelope for upstream frames. */
    public static String upstream(int op, JsonElement d) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("d", d == null ? JsonNull.INSTANCE : d);
        return Json.toJson(o);
    }

    public String id() {
        return id;
    }

    public int op() {
        return op;
    }

    public Long seq() {
        return seq;
    }

    public String type() {
        return type;
    }

    public JsonElement data() {
        return data;
    }

    public JsonObject dataObject() {
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }

    @Override
    public String toString() {
        return "GatewayPayload{op=" + op + ", t=" + type + ", s=" + seq + "}";
    }
}
