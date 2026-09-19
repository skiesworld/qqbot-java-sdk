package io.github.skiesworld.qqbot.http;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * A declarative description of one OpenAPI operation. Endpoint constants are the vocabulary the
 * generated API classes share with the transport, and {@link #bucket()} is the rate-limit key.
 */
public final class Endpoint<T> {

    public enum Method {
        GET, POST, PUT, PATCH, DELETE;

        public boolean allowsBody() {
            return this != GET && this != DELETE;
        }
    }

    private final Method method;
    private final String pathTemplate;
    private final Type responseType;

    public Endpoint(Method method, String pathTemplate, Type responseType) {
        this.method = Objects.requireNonNull(method, "method");
        this.pathTemplate = Objects.requireNonNull(pathTemplate, "pathTemplate");
        this.responseType = responseType == null ? Void.class : responseType;
        if (!pathTemplate.startsWith("/")) {
            throw new IllegalArgumentException("pathTemplate must start with '/': " + pathTemplate);
        }
    }

    public static <T> Endpoint<T> of(Method method, String pathTemplate, Class<T> type) {
        return new Endpoint<>(method, pathTemplate, type);
    }

    /** Preserve generics: {@code typeOf(new TypeToken<List<Message>>(){}.getType())}. */
    public static <T> Endpoint<T> of(Method method, String pathTemplate, Type type) {
        return new Endpoint<>(method, pathTemplate, type);
    }

    public static Type listOf(Class<?> element) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{element};
            }

            @Override
            public Type getRawType() {
                return java.util.List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    public Method method() {
        return method;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    public Type responseType() {
        return responseType;
    }

    /** Placeholder-free path shape, used as a shared rate-limit bucket. */
    public String bucket() {
        return pathTemplate.replaceAll("\\{[^}]*}", "*");
    }

    /** Substitute {@code {name}} placeholders. Every placeholder in the template must be supplied. */
    public String resolvePath(Params params) {
        String out = pathTemplate;
        for (var e : params.pathValues().entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        if (out.indexOf('{') >= 0) {
            throw new IllegalArgumentException(
                    "missing path parameters for " + method + " " + pathTemplate + " (got " + params.pathValues() + ")");
        }
        return out;
    }

    @Override
    public String toString() {
        return method + " " + pathTemplate;
    }
}
