package io.github.skiesworld.qqbot.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.auth.AccessTokenProvider;
import io.github.skiesworld.qqbot.error.ApiException;
import io.github.skiesworld.qqbot.error.QQBotException;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.util.Strings;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Blocking HTTP layer: auth header, retries with exponential backoff, and the platform's error
 * conventions.
 *
 * <p>Failures are reported through {@code err_code} inside the body rather than through the HTTP
 * status, and asynchronous acceptances (HTTP 201/202, audit codes) also carry a body, so both fields
 * are kept on {@link ApiException}.
 */
public final class HttpTransport implements Closeable {

    public static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    public static final String TRACE_HEADER = "X-Tps-trace-ID";

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);
    private static final int AUDIT_PUSH = 304023;
    private static final int AUDIT_REPLY = 304024;

    private final BotConfig config;
    private final OkHttpClient client;
    private final AccessTokenProvider tokens;

    public HttpTransport(BotConfig config) {
        this(config, defaultClient(config), null);
    }

    public HttpTransport(BotConfig config, OkHttpClient client) {
        this(config, client, null);
    }

    public HttpTransport(BotConfig config, OkHttpClient client, AccessTokenProvider tokens) {
        this.config = config;
        this.client = client;
        this.tokens = tokens != null ? tokens
                : Strings.isBlank(config.staticAccessToken())
                ? new AccessTokenProvider(config, client)
                : AccessTokenProvider.ofStaticToken(config);
    }

    public static OkHttpClient defaultClient(BotConfig config) {
        return new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.writeTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public BotConfig config() {
        return config;
    }

    public OkHttpClient client() {
        return client;
    }

    public AccessTokenProvider tokens() {
        return tokens;
    }

    public HttpTransport withTokens(AccessTokenProvider provider) {
        return new HttpTransport(config, client, provider);
    }

    public String url(String path) {
        return path.startsWith("http") ? path : config.apiBase() + path;
    }

    /** {@code body} is serialized to JSON, or flattened into query parameters for GET/DELETE. */
    public <T> T execute(Endpoint<T> endpoint, Params params, Object body) {
        if (endpoint.method().allowsBody()) {
            return send(endpoint, params, jsonBody(body), null);
        }
        return send(endpoint, params, null, body);
    }

    public <T> T executeRawBody(Endpoint<T> endpoint, Params params, RequestBody requestBody) {
        return send(endpoint, params, requestBody, null);
    }

    public <T> T executeMultipart(Endpoint<T> endpoint, Params params, MultipartBody part) {
        return send(endpoint, params, part, null);
    }

    /** Upload one chunk to a presigned url; those live on object storage, not on the API host. */
    public void putBytes(String absoluteUrl, byte[] data, MediaType contentType) {
        Request request = new Request.Builder()
                .url(absoluteUrl)
                .put(RequestBody.create(data, contentType))
                .build();
        IOException last = null;
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return;
                }
                if (response.code() < 500 && response.code() != 429) {
                    throw new ApiException(response.code(),
                            "chunk upload rejected: http=" + response.code(), response.code(),
                            response.header(TRACE_HEADER), bodyText(response));
                }
            } catch (IOException e) {
                last = e;
            }
            sleep(backoffMillis(attempt, null));
        }
        throw new QQBotException("chunk upload failed after " + (config.maxRetries() + 1) + " attempts", last);
    }

    private static RequestBody jsonBody(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof RequestBody rb) {
            return rb;
        }
        return RequestBody.create(Json.toJson(body), JSON_TYPE);
    }

    private <T> T send(Endpoint<T> endpoint, Params params, RequestBody preparedBody, Object bodyToFlatten) {
        Params effective = params == null ? Params.of() : params;
        if (!endpoint.method().allowsBody() && bodyToFlatten != null) {
            effective = flattenIntoQuery(effective, bodyToFlatten);
        }
        boolean authRetried = false;
        IOException ioFailure = null;
        int attempt = 0;
        while (true) {
            Request request;
            try {
                request = build(endpoint, effective, preparedBody);
            } catch (IOException e) {
                throw new ApiException(-1, "cannot obtain access_token: " + e.getMessage(), 0, null, null);
            }
            try (Response response = client.newCall(request).execute()) {
                int status = response.code();
                String text = bodyText(response);
                if (status == 401 && !authRetried) {
                    authRetried = true;
                    tokens.invalidate();
                    continue;
                }
                if ((status == 429 || status >= 500) && attempt < config.maxRetries()) {
                    attempt++;
                    sleep(backoffMillis(attempt, response.header("Retry-After")));
                    continue;
                }
                return decode(endpoint, status, text, response.header(TRACE_HEADER));
            } catch (IOException e) {
                ioFailure = e;
                log.debug("{} {} failed (attempt {}/{})", endpoint.method(), endpoint.pathTemplate(),
                        attempt + 1, config.maxRetries() + 1, e);
                if (attempt >= config.maxRetries()) {
                    break;
                }
                attempt++;
                sleep(backoffMillis(attempt, null));
            }
        }
        throw new ApiException(-1, endpoint.method() + " " + endpoint.pathTemplate()
                + " failed after " + (attempt + 1) + " attempts: " + ioFailure, 0, null, null);
    }

    private Request build(Endpoint<?> endpoint, Params params, RequestBody body) throws IOException {
        HttpUrl base = HttpUrl.parse(url(endpoint.resolvePath(params)));
        if (base == null) {
            throw new IllegalArgumentException("unparseable url for " + endpoint);
        }
        HttpUrl.Builder urlBuilder = base.newBuilder();
        for (Map.Entry<String, List<String>> q : params.queryValues().entrySet()) {
            for (String v : q.getValue()) {
                urlBuilder.addQueryParameter(q.getKey(), v);
            }
        }
        Request.Builder rb = new Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .header("User-Agent", config.userAgent())
                .header("Authorization", tokens.authorization());
        RequestBody effective = body;
        if (endpoint.method().allowsBody() && effective == null) {
            effective = RequestBody.create(new byte[0], null);
        }
        switch (endpoint.method()) {
            case GET -> rb.get();
            case DELETE -> {
                if (effective == null) {
                    rb.delete();
                } else {
                    rb.delete(effective);
                }
            }
            case POST -> rb.post(effective);
            case PUT -> rb.put(effective);
            case PATCH -> rb.patch(effective);
        }
        return rb.build();
    }

    /** Documented GET operations carry their filters as a "请求体"; on the wire they are query parameters. */
    private Params flattenIntoQuery(Params params, Object body) {
        JsonElement element = Json.GSON.toJsonTree(body);
        if (!element.isJsonObject()) {
            return params;
        }
        Params out = Params.of();
        out.pathValues().putAll(params.pathValues());
        params.queryValues().forEach((k, v) -> {
            for (String s : v) {
                out.queryValue(k, s);
            }
        });
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                continue;
            }
            if (v.isJsonArray()) {
                JsonArray arr = v.getAsJsonArray();
                if (arr.isEmpty()) {
                    continue;
                }
                for (JsonElement item : arr) {
                    out.queryValue(e.getKey(), item.isJsonPrimitive() ? item.getAsString() : item.toString());
                }
            } else if (v.isJsonObject()) {
                out.queryValue(e.getKey(), v.toString());
            } else {
                out.queryValue(e.getKey(), v.getAsString());
            }
        }
        return out;
    }

    private <T> T decode(Endpoint<T> endpoint, int status, String text, String traceId) {
        if (Strings.isBlank(text)) {
            if (status >= 400) {
                throw new ApiException(status, endpoint.method() + " " + endpoint.pathTemplate()
                        + " failed with http " + status + " and an empty body", status, traceId, null);
            }
            return null;
        }
        JsonElement parsed = Json.parseLenient(text);
        if (parsed.isJsonObject()) {
            JsonObject obj = parsed.getAsJsonObject();
            Integer errCode = readErrCode(obj);
            if (errCode != null && errCode != 0) {
                throw new ApiException(errCode, readMessage(obj), status,
                        obj.has("trace_id") ? obj.get("trace_id").getAsString() : traceId, text);
            }
            if (status >= 400) {
                throw new ApiException(errCode == null ? status : errCode, readMessage(obj), status, traceId, text);
            }
        } else if (status >= 400) {
            throw new ApiException(status, text, status, traceId, text);
        }
        return Json.GSON.fromJson(text, endpoint.responseType());
    }

    static Integer readErrCode(JsonObject obj) {
        for (String field : new String[]{"err_code", "code"}) {
            if (obj.has(field) && !obj.get(field).isJsonNull()) {
                try {
                    return obj.get(field).getAsInt();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String readMessage(JsonObject obj) {
        if (obj.has("message") && !obj.get("message").isJsonNull()) {
            return obj.get("message").getAsString();
        }
        if (obj.has("error") && obj.get("error").isJsonObject()) {
            JsonObject err = obj.getAsJsonObject("error");
            if (err.has("message")) {
                return err.get("message").getAsString();
            }
        }
        return "(no message)";
    }

    private static String bodyText(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? null : body.string();
    }

    /** True when the platform accepted the message for manual audit instead of delivering it. */
    public static boolean auditPending(ApiException e) {
        return (e.errCode() == AUDIT_PUSH || e.errCode() == AUDIT_REPLY)
                || (e.httpStatus() == 201 || e.httpStatus() == 202);
    }

    private long backoffMillis(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                return Math.min(config.retryMaxDelay().toMillis(),
                        (long) (Double.parseDouble(retryAfter.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                return config.retryBaseDelay().toMillis();
            }
        }
        long base = config.retryBaseDelay().toMillis();
        long delay = base << Math.min(10, attempt);
        long capped = Math.min(config.retryMaxDelay().toMillis(), Math.max(base, delay));
        return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QQBotException("interrupted while backing off", e);
        }
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
