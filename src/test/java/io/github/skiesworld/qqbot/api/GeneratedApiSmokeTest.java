package io.github.skiesworld.qqbot.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.event.EventModels;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.model.Schedule;
import io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest;
import io.github.skiesworld.qqbot.model.request.SetGuildMuteRequest;
import io.github.skiesworld.qqbot.util.Json;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the generated layer: it exercises one call of each parameter shape against
 * MockWebServer and checks that everything spec.json documents is actually reachable.
 */
class GeneratedApiSmokeTest {

    private static final Path SPEC = Path.of("tools", "docgen", "spec.json");

    private MockWebServer server;
    private HttpTransport transport;
    private Api api;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        BotConfig config = BotConfig.builder("123456789")
                .accessToken("STATIC_TOKEN")
                .apiBase(server.url("/").toString().replaceAll("/$", ""))
                .maxRetries(0)
                .retryBaseDelay(Duration.ofMillis(1))
                .build();
        transport = new HttpTransport(config);
        api = new Api(transport);
    }

    @AfterEach
    void tearDown() throws IOException {
        transport.close();
        server.close();
    }

    @Test
    void facadeExposesEveryGeneratedGroup() throws Exception {
        assertSame(api.me(), api.me(), "accessors hand out one instance per group");
        List<Object> groups = List.of(api.me(), api.c2c(), api.group(), api.guild(), api.channel(),
                api.channelMessages(), api.channelContent(), api.channelPermissions(), api.menu(),
                api.panels(), api.interactions(), api.gateway());
        assertEquals(12, groups.size());
        for (Object group : groups) {
            assertNotNull(group, "group api must be built");
        }
        assertNotNull(api.http());
        int endpoints = 0;
        for (Field field : io.github.skiesworld.qqbot.api.endpoint.Endpoints.class.getFields()) {
            if (Endpoint.class.isAssignableFrom(field.getType())) {
                endpoints++;
            }
        }
        assertTrue(endpoints >= 90, "only " + endpoints + " endpoint constants were generated");
    }

    @Test
    void sendC2CMessageSendsTheDocumentedBodyAndDecodesTheResponse() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"id\":\"MSG1\",\"timestamp\":\"2026-07-21T10:30:00+08:00\","
                        + "\"ext_info\":{\"ref_idx\":\"REFIDX_abc\"}}"));

        SendC2CMessageRequest request = new SendC2CMessageRequest();
        request.msgType = 0L;
        request.content = "hello";
        request.msgId = "ROBOT1.0_xyz";
        var response = api.c2c().sendC2CMessage("USER_OPENID", request);

        RecordedRequest sent = server.takeRequest();
        assertEquals("POST", sent.getMethod());
        assertEquals("/v2/users/USER_OPENID/messages", sent.getPath());
        assertEquals("{\"msg_type\":0,\"content\":\"hello\",\"msg_id\":\"ROBOT1.0_xyz\"}",
                sent.getBody().readUtf8());
        assertEquals("MSG1", response.id);
        assertEquals("REFIDX_abc", response.extInfo.refIdx);
    }

    @Test
    void queryOnlyReadsBecomeQueryParametersInDocumentedOrder() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"guilds\":[{\"id\":\"G1\",\"name\":\"club\"}]}"));

        var guilds = api.me().listBotGuilds("BEFORE", "AFTER", 20L);

        RecordedRequest sent = server.takeRequest();
        assertEquals("GET", sent.getMethod());
        assertEquals("/users/@me/guilds?before=BEFORE&after=AFTER&limit=20", sent.getPath());
        assertEquals("G1", guilds.guilds.get(0).id);
        assertEquals("club", guilds.guilds.get(0).name);
    }

    @Test
    void flattenedArgumentsStillTravelAsAWireNamedBody() throws Exception {
        server.enqueue(new MockResponse().setStatus("HTTP/1.1 204 No Content"));
        api.guild().setGuildMute("G1", "1641916800", "120");

        RecordedRequest sent = server.takeRequest();
        assertEquals("PATCH", sent.getMethod());
        assertEquals("/guilds/G1/mute", sent.getPath());
        assertEquals("{\"mute_end_timestamp\":\"1641916800\",\"mute_seconds\":\"120\"}",
                sent.getBody().readUtf8());
    }

    @Test
    void generatedDtosKeepWireNamesAndANoArgConstructor() throws Exception {
        List<String> wires = new ArrayList<>();
        for (Field field : SetGuildMuteRequest.class.getFields()) {
            SerializedName annotated = field.getAnnotation(SerializedName.class);
            assertNotNull(annotated, field + " must carry its wire name");
            assertTrue(Modifier.isPublic(field.getModifiers()), field + " must be a public field");
            wires.add(annotated.value());
        }
        assertEquals(List.of("mute_end_timestamp", "mute_seconds"), wires);
        assertNotNull(SetGuildMuteRequest.class.getConstructor());
    }

    @Test
    void topArrayOfTheDocumentedShapeDecodesIntoTheListType() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "[{\"id\":\"S1\",\"name\":\"up\",\"start_timestamp\":\"1642076400000\"}]"));

        List<Schedule> schedules = api.channelContent().listSchedules("C1", 1642076400000L);

        RecordedRequest sent = server.takeRequest();
        assertEquals("/channels/C1/schedules?since=1642076400000", sent.getPath());
        assertEquals(1, schedules.size());
        assertEquals("S1", schedules.get(0).id);
        assertEquals("up", schedules.get(0).name);
    }

    @Test
    void everyDocumentedEventResolvesToAGeneratedPayloadClass() throws IOException {
        JsonObject spec = Json.parseLenient(Files.readString(SPEC, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray events = spec.getAsJsonArray("events");
        assertTrue(events.size() >= 21, "spec.json documents only " + events.size() + " events");
        for (var element : events) {
            String name = element.getAsJsonObject().getAsJsonObject("info")
                    .get("事件名").getAsString();
            Class<?> model = EventModels.dataClass(EventType.from(name), name);
            assertNotSame(Void.class, model,
                    "no payload class for " + name + ", expected " + EventModels.className(name));
            assertEquals(EventModels.className(name), model.getName(),
                    name + " must be modelled under the naming convention EventModels resolves");
        }
    }
}
