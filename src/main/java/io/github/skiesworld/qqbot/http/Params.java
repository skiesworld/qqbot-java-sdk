package io.github.skiesworld.qqbot.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Path and query parameters of a call. Null and blank values are dropped. */
public final class Params {

    private final Map<String, String> pathValues = new LinkedHashMap<>();
    private final Map<String, List<String>> queryValues = new LinkedHashMap<>();

    private Params() {
    }

    public static Params of() {
        return new Params();
    }

    public Params pathValue(String name, Object value) {
        if (value != null) {
            pathValues.put(name, String.valueOf(value));
        }
        return this;
    }

    public Params queryValue(String name, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) {
            queryValues.computeIfAbsent(name, k -> new ArrayList<>()).add(String.valueOf(value));
        }
        return this;
    }

    /** Repeatable query parameter. */
    public Params queryValue(String name, Iterable<?> values) {
        if (values != null) {
            for (Object v : values) {
                queryValue(name, v);
            }
        }
        return this;
    }

    public Map<String, String> pathValues() {
        return pathValues;
    }

    public Map<String, List<String>> queryValues() {
        return queryValues;
    }
}
