package io.github.skiesworld.qqbot.util;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void keepsMentionSyntaxLiteral() {
        String json = Json.toJson(new Sample("<@!1234> hello & <b>", 7));
        assertTrue(json.contains("<@!1234> hello & <b>"), json);
        assertFalse(json.contains("\\u003c"), json);
    }

    @Test
    void omitsNullFields() {
        String json = Json.toJson(new Sample(null, 0));
        assertFalse(json.contains("content"), json);
        assertTrue(json.contains("\"msg_type\":0"), json);
    }

    @Test
    void parsesGatewayPayload() {
        JsonObject o = Json.parseLenient("{\"op\":0,\"s\":\"12\",\"t\":\"READY\",\"d\":{\"session_id\":\"a\"}}")
                .getAsJsonObject();
        assertEquals(0, o.get("op").getAsInt());
        assertEquals("a", o.getAsJsonObject("d").get("session_id").getAsString());
    }

    @Test
    void emptyInputYieldsNull() {
        assertNull(Json.fromJson("", Sample.class));
        assertNull(Json.fromJson(null, Sample.class));
    }

    @SuppressWarnings("unused")
    private static final class Sample {
        @com.google.gson.annotations.SerializedName("content")
        private final String content;
        @com.google.gson.annotations.SerializedName("msg_type")
        private final int msgType;

        Sample(String content, int msgType) {
            this.content = content;
            this.msgType = msgType;
        }
    }
}
