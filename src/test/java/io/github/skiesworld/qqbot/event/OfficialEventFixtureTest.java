package io.github.skiesworld.qqbot.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.websocket.Intent;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the event payloads published by the official docs (captured under tools/docgen/fixtures)
 * against the generated models, so wire names, nesting and optionality are checked with real samples
 * instead of hand-written ones.
 */
class OfficialEventFixtureTest {

    private static final Path FIXTURES = Path.of("tools", "docgen", "fixtures");

    record Fixture(String file, String event, JsonObject payload) {
    }

    static List<Fixture> load() throws IOException {
        List<Fixture> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(FIXTURES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject doc = Json.parseLenient(
                        Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                out.add(new Fixture(p.getFileName().toString(), doc.get("event").getAsString(),
                        Json.parseLenient(doc.get("raw").getAsString()).getAsJsonObject()));
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> everyDocumentedEventResolvesToAModel() throws IOException {
        List<Fixture> fixtures = load();
        assertFalse(fixtures.isEmpty(), "the doc fixtures were not extracted");
        return fixtures.stream().map(f -> DynamicTest.dynamicTest(f.event, () -> {
            EventType type = EventType.from(f.event);
            assertNot(type, EventType.UNKNOWN, "no EventType entry for " + f.event);
            if (type != EventType.READY && type != EventType.RESUMED) {
                assertNotNull(type.intent(), f.event + " needs an intent mapping to be subscribable");
            }

            Class<?> model = EventModels.dataClass(type, f.event);
            assertNot(model, Void.class, "no generated payload class " + EventModels.className(f.event));
            Object data = Json.GSON.fromJson(f.payload, model);
            assertNotNull(data, f.event + " did not deserialize into " + model.getSimpleName());

            QQEvent envelope = new QQEvent("EVID", 0, 1L, f.event, type, f.payload);
            assertSame(data.getClass(), envelope.data().getClass(),
                    "QQEvent.data() should pick the same model as the registry");

            JsonObject back = Json.parseLenient(Json.toJson(data)).getAsJsonObject();
            for (String key : f.payload.keySet()) {
                JsonElement value = f.payload.get(key);
                if (value.isJsonNull() || !back.has(key)) {
                    continue;
                }
                assertEquals(value, back.get(key), "field " + key + " changed shape for " + f.event);
            }
        }));
    }

    @Test
    void c2cMessageKeepsAuthorSceneAndMessageTypeWiring() throws IOException {
        Fixture c2c = find("C2C_MESSAGE_CREATE");
        QQEvent envelope = new QQEvent(c2c.payload.get("id").getAsString(), 0, 1L,
                "C2C_MESSAGE_CREATE", EventType.C2C_MESSAGE_CREATE, c2c.payload);

        assertEquals(c2c.payload.getAsJsonObject("author").get("user_openid").getAsString(),
                envelope.targetId(), "single chat events key off the sender openid");
        Object typed = envelope.data();
        List<String> wires = wireNames(typed.getClass());
        assertTrue(wires.contains("message_type"), String.valueOf(wires));
        assertTrue(wires.contains("message_scene"), String.valueOf(wires));
        assertTrue(wires.contains("attachments") || wires.contains("ark_data"), String.valueOf(wires));
    }

    @Test
    void groupMessageModesShareOneIntentButStayDistinct() throws IOException {
        List<String> names = load().stream().map(Fixture::event).toList();
        assertTrue(names.contains("GROUP_AT_MESSAGE_CREATE"), String.valueOf(names));
        assertTrue(names.contains("GROUP_MESSAGE_CREATE"), String.valueOf(names));
        assertSame(EventType.GROUP_AT_MESSAGE_CREATE.intent(), EventType.GROUP_MESSAGE_CREATE.intent(),
                "both group modes arrive on GROUP_AND_C2C_EVENT");
        assertNotSame(EventType.GROUP_AT_MESSAGE_CREATE, EventType.GROUP_MESSAGE_CREATE);
    }

    @Test
    void guildChannelAndMemberEventsMapToTheirOwnIntents() {
        assertSame(io.github.skiesworld.qqbot.websocket.Intent.GUILDS, EventType.CHANNEL_CREATE.intent());
        assertSame(io.github.skiesworld.qqbot.websocket.Intent.GUILD_MEMBERS, EventType.GUILD_MEMBER_ADD.intent());
        assertSame(io.github.skiesworld.qqbot.websocket.Intent.INTERACTION, EventType.INTERACTION_CREATE.intent());
        assertSame(io.github.skiesworld.qqbot.websocket.Intent.DIRECT_MESSAGE, EventType.DIRECT_MESSAGE_CREATE.intent());
        assertSame(io.github.skiesworld.qqbot.websocket.Intent.GROUP_MEMBER_EVENT, EventType.GROUP_JOIN_REQUEST.intent());
    }

    private static Fixture find(String event) throws IOException {
        return load().stream().filter(f -> f.event.equals(event)).findFirst()
                .orElseThrow(() -> new AssertionError("missing fixture for " + event));
    }

    private static List<String> wireNames(Class<?> model) {
        List<String> out = new ArrayList<>();
        for (Field f : model.getDeclaredFields()) {
            SerializedName name = f.getAnnotation(SerializedName.class);
            out.add(name != null ? name.value() : f.getName());
        }
        return out;
    }

    private static void assertNot(Object actual, Object forbidden, String message) {
        assertNotSame(forbidden, actual, message);
    }

}
