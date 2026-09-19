package io.github.skiesworld.qqbot.websocket;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.util.Json;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the gateway against a mock WebSocket: IDENTIFY, READY, heartbeat, RESUME and failure paths. */
class GatewayTest {

    private MockWebServer server;
    private HttpTransport transport;
    private EventBus bus;
    private Gateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        BotConfig config = BotConfig.builder("111111111")
                .accessToken("TK")
                .apiBase("http://" + server.getHostName() + ":" + server.getPort())
                .wsUrl("ws://" + server.getHostName() + ":" + server.getPort() + "/ws")
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .retryBaseDelay(Duration.ofMillis(1))
                .retryMaxDelay(Duration.ofMillis(5))
                .build();
        transport = new HttpTransport(config);
        bus = new EventBus();
        gateway = new Gateway(config, transport, bus);
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        transport.close();
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Test
    void identifiesReceivesReadyAndDispatchesEvents() throws Exception {
        FakeGateway fake = new FakeGateway("session-1");
        server.enqueue(new MockResponse().withWebSocketUpgrade(fake));

        gateway.start();
        assertTrue(gateway.awaitConnected(5_000), "state was " + gateway.state());
        assertEquals("session-1", gateway.sessionId());

        JsonObject identify = fake.awaitOp(OpCode.IDENTIFY.code());
        assertEquals("QQBot TK", identify.getAsJsonObject("d").get("token").getAsString());
        assertEquals(1L << 25, identify.getAsJsonObject("d").get("intents").getAsLong());
        assertEquals(0, identify.getAsJsonObject("d").getAsJsonArray("shard").get(0).getAsInt());
        assertEquals(1, identify.getAsJsonObject("d").getAsJsonArray("shard").get(1).getAsInt());
        assertEquals("qqbot-java-sdk", identify.getAsJsonObject("d").getAsJsonObject("properties")
                .get("$browser").getAsString());

        CountDownLatch got = new CountDownLatch(1);
        List<QQEvent> received = new CopyOnWriteArrayList<>();
        bus.on(EventType.C2C_MESSAGE_CREATE, e -> {
            received.add(e);
            got.countDown();
        });
        fake.send(eventFrame(3, "C2C_MESSAGE_CREATE",
                "{\"id\":\"EVENT1\",\"author\":{\"user_openid\":\"U1\"},\"content\":\"hi\"}"));
        assertTrue(got.await(5, TimeUnit.SECONDS), "event never reached the bus");
        assertEquals(1, received.size());
        QQEvent event = received.get(0);
        assertEquals("EV3", event.id());
        assertEquals(3L, event.seq());
        assertEquals("hi", event.rawObject().get("content").getAsString());
        assertEquals("U1", event.targetId());
        assertEquals(3L, gateway.lastSeq());
    }

    @Test
    void heartbeatsWithTheLatestSeqAndHandlesAcks() throws Exception {
        FakeGateway fake = new FakeGateway("session-hb");
        server.enqueue(new MockResponse().withWebSocketUpgrade(fake));
        gateway.start();
        assertTrue(gateway.awaitConnected(5_000));
        fake.send(eventFrame(7, "C2C_MSG_RECEIVE", "{\"openid\":\"U1\"}"));
        awaitSeq(gateway, 7);
        JsonObject beat = fake.awaitOp(OpCode.HEARTBEAT.code());
        assertEquals(7, beat.get("d").getAsInt(), "heartbeat must carry the last received seq");
        assertTrue(gateway.isConnected(), "an acknowledged heartbeat must keep the session alive");
    }

    @Test
    void resumesWithSessionIdAndSeqAfterTheSocketDrops() throws Exception {
        FakeGateway first = new FakeGateway("session-r");
        FakeGateway second = new FakeGateway("session-r");
        server.enqueue(new MockResponse().withWebSocketUpgrade(first));
        server.enqueue(new MockResponse().withWebSocketUpgrade(second));

        gateway.start();
        assertTrue(gateway.awaitConnected(5_000));
        assertNotNull(first.awaitOp(OpCode.IDENTIFY.code()));
        first.send(eventFrame(11, "C2C_MSG_REJECT", "{\"openid\":\"U1\"}"));
        awaitSeq(gateway, 11);
        assertEquals(Long.valueOf(11), gateway.lastSeq());

        first.drop();

        JsonObject resume = second.awaitOp(OpCode.RESUME.code());
        assertEquals("session-r", resume.getAsJsonObject("d").get("session_id").getAsString());
        assertEquals(11, resume.getAsJsonObject("d").get("seq").getAsLong());
        second.send("{\"op\":0,\"s\":12,\"t\":\"RESUMED\",\"d\":\"\"}");
        assertTrue(gateway.awaitConnected(5_000), "state was " + gateway.state());
        assertEquals(Gateway.State.CONNECTED, gateway.state());
    }

    @Test
    void invalidSessionWithoutResumeFlagFallsBackToIdentify() throws Exception {
        FakeGateway first = new FakeGateway("session-x");
        FakeGateway second = new FakeGateway("session-y");
        server.enqueue(new MockResponse().withWebSocketUpgrade(first));
        server.enqueue(new MockResponse().withWebSocketUpgrade(second));

        gateway.start();
        assertTrue(gateway.awaitConnected(5_000));
        first.send("{\"op\":9,\"d\":false}");

        assertNotNull(second.awaitOp(OpCode.IDENTIFY.code()), "a rejected session must re-identify");
        assertEquals("session-y", gateway.sessionId());
    }

    @Test
    void fatalCloseCodeStopsReconnecting() throws Exception {
        FakeGateway fake = new FakeGateway("session-f");
        server.enqueue(new MockResponse().withWebSocketUpgrade(fake));
        AtomicInteger connections = new AtomicInteger();
        gateway.addListener(new Gateway.Listener() {
            @Override
            public void onStateChange(Gateway.State from, Gateway.State to) {
                if (to == Gateway.State.CONNECTING) {
                    connections.incrementAndGet();
                }
            }
        });
        gateway.start();
        assertTrue(gateway.awaitConnected(5_000));

        fake.closeWith(4915, "机器人已封禁");
        long deadline = System.currentTimeMillis() + 1_500;
        while (gateway.state() != Gateway.State.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(Gateway.State.STOPPED, gateway.state());
        assertEquals(1, connections.get(), "a banned bot must not keep dialling");
    }

    @Test
    void serverInitiatedReconnectKeepsTheSession() throws Exception {
        FakeGateway first = new FakeGateway("session-n");
        FakeGateway second = new FakeGateway("session-n");
        server.enqueue(new MockResponse().withWebSocketUpgrade(first));
        server.enqueue(new MockResponse().withWebSocketUpgrade(second));
        gateway.start();
        assertTrue(gateway.awaitConnected(5_000));
        first.send(eventFrame(21, "C2C_MSG_RECEIVE", "{\"openid\":\"U\"}"));
        awaitSeq(gateway, 21);

        first.send("{\"op\":7}");

        JsonObject resume = second.awaitOp(OpCode.RESUME.code());
        assertEquals("session-n", resume.getAsJsonObject("d").get("session_id").getAsString());
        assertEquals(21, resume.getAsJsonObject("d").get("seq").getAsLong());
    }

    private static String eventFrame(long seq, String type, String data) {
        return "{\"op\":0,\"s\":" + seq + ",\"t\":\"" + type + "\",\"id\":\"EV" + seq
                + "\",\"d\":" + data + "}";
    }

    private static void awaitSeq(Gateway g, long expected) throws InterruptedException {
        for (int i = 0; i < 200 && !Long.valueOf(expected).equals(g.lastSeq()); i++) {
            Thread.sleep(25);
        }
    }

    /** Scripted stand-in for the QQ gateway. */
    private static final class FakeGateway extends WebSocketListener {

        private final String sessionId;
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final AtomicInteger hellos = new AtomicInteger();
        private volatile WebSocket socket;
        private volatile CountDownLatch ack = new CountDownLatch(1);

        FakeGateway(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            socket = ws;
            hellos.incrementAndGet();
            ws.send("{\"op\":10,\"d\":{\"heartbeat_interval\":300}}");
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            frames.add(text);
            JsonObject o = Json.parseLenient(text).getAsJsonObject();
            int op = o.get("op").getAsInt();
            if (op == OpCode.IDENTIFY.code()) {
                ws.send("{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"version\":1,\"session_id\":\""
                        + sessionId + "\",\"user\":{\"id\":\"BOT1\",\"bot\":true}}}");
            } else if (op == OpCode.RESUME.code()) {
                ws.send("{\"op\":0,\"s\":2,\"t\":\"RESUMED\",\"d\":\"\"}");
            } else if (op == OpCode.HEARTBEAT.code()) {
                ack = new CountDownLatch(1);
                ws.send("{\"op\":11}");
                ack.countDown();
            }
        }

        void send(String frame) {
            WebSocket ws = socket;
            if (ws != null) {
                ws.send(frame);
            }
        }

        /** Server hangs up; the client must reconnect and RESUME. */
        void drop() {
            WebSocket ws = socket;
            if (ws != null) {
                try {
                    ws.close(1000, "server restart");
                } catch (RuntimeException ignored) {
                    // the peer may already be gone
                }
            }
        }

        void closeWith(int code, String reason) {
            WebSocket ws = socket;
            if (ws != null) {
                ws.close(code, reason);
            }
        }

        JsonObject awaitOp(int op) throws InterruptedException {
            String wanted = "\"op\":" + op;
            for (int i = 0; i < 400; i++) {
                for (String f : frames) {
                    if (f.startsWith("{\"op\":" + op + ",") || f.contains(wanted + ",")) {
                        return Json.parseLenient(f).getAsJsonObject();
                    }
                }
                Thread.sleep(25);
            }
            return null;
        }

        int connectionCount() {
            return hellos.get();
        }
    }
}
