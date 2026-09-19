package io.github.skiesworld.qqbot.callback;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.error.SignatureException;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * HTTP callback mode receiver.
 *
 * <p>Feed it the raw request body plus the two signature headers; it verifies, dispatches into an
 * {@link EventBus} and returns the body your HTTP layer should reply with. The platform validates the
 * callback address with opcode 13, which is answered with an Ed25519 signature over
 * {@code event_ts + plain_token} instead of dispatching an event.
 */
public final class WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookHandler.class);
    /** Acknowledgement telling the platform the push was received. */
    public static final String ACK = "{\"op\":12}";

    private final String botSecret;
    private final String expectedAppId;
    private final EventBus eventBus;

    public WebhookHandler(String botSecret, EventBus eventBus) {
        this(botSecret, null, eventBus);
    }

    public WebhookHandler(String botSecret, String expectedAppId, EventBus eventBus) {
        this.botSecret = Strings.requireNonBlank(botSecret, "botSecret");
        this.expectedAppId = expectedAppId;
        this.eventBus = eventBus;
    }

    /**
     * @param body      exact request bytes, decoded as UTF-8 without reformatting
     * @param timestamp {@code X-Signature-Timestamp}
     * @param signature {@code X-Signature-Ed25519}
     * @param appId     {@code X-Bot-Appid}, may be null
     * @return response body for the callback request
     */
    public String handle(String body, String timestamp, String signature, String appId) {
        if (Strings.isNotBlank(expectedAppId) && Strings.isNotBlank(appId) && !expectedAppId.equals(appId)) {
            throw new SignatureException("callback appid mismatch: expected " + expectedAppId + " got " + appId);
        }
        if (!SignatureUtil.verify(botSecret, timestamp, body.getBytes(StandardCharsets.UTF_8), signature)) {
            throw new SignatureException("callback signature verification failed");
        }
        JsonObject payload = Json.parseLenient(body).getAsJsonObject();
        int op = payload.has("op") ? payload.get("op").getAsInt() : -1;
        if (op == io.github.skiesworld.qqbot.websocket.OpCode.CALLBACK_VALIDATION.code()) {
            JsonObject d = payload.has("d") ? payload.getAsJsonObject("d") : new JsonObject();
            return SignatureUtil.validationResponse(botSecret,
                    d.has("event_ts") ? d.get("event_ts").getAsString() : null,
                    d.has("plain_token") ? d.get("plain_token").getAsString() : null);
        }
        if (eventBus != null && payload.has("t")) {
            String name = payload.get("t").getAsString();
            eventBus.dispatch(new QQEvent(
                    payload.has("id") ? payload.get("id").getAsString() : null,
                    op,
                    payload.has("s") && !payload.get("s").isJsonNull() ? payload.get("s").getAsLong() : null,
                    name, EventType.from(name),
                    payload.has("d") ? payload.get("d") : new JsonObject()));
        } else {
            log.debug("callback op={} dispatched nothing (no t field)", op);
        }
        return ACK;
    }

    public String handle(String body, String timestamp, String signature) {
        return handle(body, timestamp, signature, null);
    }
}
