package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/** Reading one string out of a payload, the way every envelope accessor in this package does it. */
final class EventValues {

    static String text(JsonObject payload, String key) {
        JsonElement value = payload.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    /** The first key in {@code keys} the payload reports a value under, or null. */
    static String firstText(JsonObject payload, List<String> keys) {
        for (String key : keys) {
            String text = text(payload, key);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    static JsonObject child(JsonObject payload, String key) {
        return payload.get(key) instanceof JsonObject child ? child : new JsonObject();
    }

    private EventValues() {
    }
}
