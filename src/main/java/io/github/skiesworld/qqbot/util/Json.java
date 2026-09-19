package io.github.skiesworld.qqbot.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.Map;

/** Shared Gson instance. HTML escaping is disabled so message content such as {@code <@!1234>} stays literal. */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static final Type MAP_STRING_OBJECT = new TypeToken<Map<String, Object>>() { }.getType();

    private Json() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return GSON.fromJson(json, type);
    }

    public static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    /** Lenient parse used for gateway payloads, which may carry numbers as strings or booleans. */
    public static JsonElement parseLenient(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        return GSON.fromJson(json, MAP_STRING_OBJECT);
    }
}
