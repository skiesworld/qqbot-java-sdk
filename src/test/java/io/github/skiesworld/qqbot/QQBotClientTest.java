package io.github.skiesworld.qqbot;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.error.QQBotException;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.http.Params;
import io.github.skiesworld.qqbot.websocket.Gateway;
import io.github.skiesworld.qqbot.websocket.GatewayInfo;
import io.github.skiesworld.qqbot.websocket.Intent;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wiring of the facade: shared transport, lazy gateway, event bus fan-out and the raw escape hatch. */
class QQBotClientTest {

    private MockWebServer server;
    private QQBotClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        BotConfig config = BotConfig.builder("APP")
                .accessToken("TOKEN")
                .apiBase("http://" + server.getHostName() + ":" + server.getPort())
                .maxRetries(0)
                .retryBaseDelay(Duration.ofMillis(1))
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .build();
        client = QQBotClient.create(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.close();
    }

    @Test
    void exposesEveryFacetBuiltOnTheSameTransport() {
        assertNotNull(client.api().c2c());
        assertNotNull(client.api().group());
        assertNotNull(client.media());
        assertNotNull(client.gateway());
    }

    @Test
    void gatewayIsCreatedOnceAndReused() {
        Gateway first = client.gateway();
        assertSame(first, client.gateway(), "gateway() must not create a second connection holder");
        assertEquals(Gateway.State.IDLE, first.state());
    }

    @Test
    void connectResolvesTheGatewayUrlThenOpensTheSocket() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"url\":\"ws://" + server.getHostName() + ":"
                + server.getPort() + "/ws\",\"shards\":2,\"session_start_limit\":{\"total\":1000,"
                + "\"remaining\":999,\"reset_after\":86400000,\"max_concurrency\":1}}"));
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                ws.send("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                JsonObject d = new JsonObject();
                d.addProperty("version", 1);
                d.addProperty("session_id", "S-FROM-BOT-TEST");
                ws.send("{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":" + d + "}");
            }
        }));

        Gateway gateway = client.connect();
        assertSame(gateway, client.gateway());
        assertTrue(gateway.awaitConnected(10_000), "state=" + gateway.state());
        assertEquals("S-FROM-BOT-TEST", gateway.sessionId());

        RecordedRequest discovery = server.takeRequest();
        assertEquals("GET", discovery.getMethod());
        assertEquals("/gateway/bot", discovery.getPath());
        assertEquals("QQBot TOKEN", discovery.getHeader("Authorization"));
    }

    @Test
    void executeIsAnUntypedEscapeHatchForAnyDocumentedPath() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"BOT9\",\"username\":\"demo\",\"bot\":true}"));
        JsonObject user = client.execute(
                Endpoint.of(Endpoint.Method.GET, "/users/@me", JsonObject.class), Params.of(), null);
        assertEquals("BOT9", user.get("id").getAsString());
        RecordedRequest recorded = server.takeRequest();
        assertEquals("/users/@me", recorded.getPath());
        assertTrue(recorded.getHeader("User-Agent").contains("qqbot"), recorded.getHeader("User-Agent"));
    }

    @Test
    void dispatchedEventsReachListenersRegisteredOnTheFacade() throws Exception {
        EventBus bus = client.events();
        CountDownLatch got = new CountDownLatch(1);
        bus.on(EventType.FRIEND_ADD, e -> got.countDown());

        JsonObject d = new JsonObject();
        d.addProperty("openid", "USER1");
        bus.dispatch(new io.github.skiesworld.qqbot.event.QQEvent("E1", 0, 1L, "FRIEND_ADD",
                EventType.FRIEND_ADD, d));
        assertTrue(got.await(2, TimeUnit.SECONDS));
    }

    @Test
    void connectTwiceDoesNotRestartTheGateway() {
        server.enqueue(new MockResponse().setBody("{\"url\":\"ws://127.0.0.1:1/ws\"}"));
        Gateway first = client.connect();
        Gateway second = client.connect();
        assertSame(first, second);
        assertThrows(QQBotException.class, first::start, "an already started gateway must refuse a second start");
    }

    @Test
    void closeStopsTheGatewayItCreated() {
        Gateway gateway = client.gateway();
        client.close();
        assertEquals(Gateway.State.STOPPED, gateway.state());
    }
}
