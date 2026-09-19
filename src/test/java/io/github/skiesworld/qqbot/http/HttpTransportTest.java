package io.github.skiesworld.qqbot.http;

import com.google.gson.annotations.SerializedName;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.auth.AccessToken;
import io.github.skiesworld.qqbot.auth.AccessTokenProvider;
import io.github.skiesworld.qqbot.error.ApiException;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private MockWebServer server;
    private HttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        BotConfig config = BotConfig.builder("123456789")
                .accessToken("STATIC_TOKEN")
                .apiBase(server.url("/").toString().replaceAll("/$", ""))
                .maxRetries(2)
                .retryBaseDelay(Duration.ofMillis(1))
                .retryMaxDelay(Duration.ofMillis(5))
                .build();
        transport = new HttpTransport(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        transport.close();
        server.close();
    }

    @Test
    void sendsPlatformAuthHeaderAndParsesBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"MSG1\",\"timestamp\":\"2026-07-21T10:30:00+08:00\"}"));
        SendResult result = transport.execute(
                Endpoint.of(Endpoint.Method.POST, "/v2/users/{user_openid}/messages", SendResult.class),
                Params.of().pathValue("user_openid", "ABC123"),
                new SendBody("hello", 0));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v2/users/ABC123/messages", recorded.getPath());
        assertEquals("QQBot STATIC_TOKEN", recorded.getHeader("Authorization"));
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
        assertEquals("{\"content\":\"hello\",\"msg_type\":0}",
                recorded.getBody().readUtf8());
        assertEquals("MSG1", result.id);
        assertEquals("2026-07-21T10:30:00+08:00", result.timestamp);
    }

    @Test
    void throwsApiExceptionCarryingErrCodeAndTrace() {
        server.enqueue(new MockResponse().setStatus("HTTP/1.1 400 Bad Request")
                .setHeader("X-Tps-trace-ID", "trace-abc")
                .setBody("{\"err_code\":40034005,\"message\":\"回复消息msg_id已过期\"}"));

        ApiException e = assertThrows(ApiException.class, () -> transport.execute(
                Endpoint.of(Endpoint.Method.POST, "/v2/groups/{group_openid}/messages", SendResult.class),
                Params.of().pathValue("group_openid", "G"), new SendBody("x", 0)));
        assertEquals(40034005, e.errCode());
        assertEquals(400, e.httpStatus());
        assertTrue(e.getMessage().contains("40034005"), e.getMessage());
        assertTrue(e.rawBody().contains("msg_id"), e.rawBody());
    }

    @Test
    void treatsErrCodeZeroAsSuccess() {
        server.enqueue(new MockResponse().setBody("{\"err_code\":0,\"message\":\"\"}"));
        SendResult result = transport.execute(
                Endpoint.of(Endpoint.Method.GET, "/users/@me", SendResult.class), Params.of(), null);
        assertNull(result.id);
    }

    @Test
    void emptyBodyOn204ReturnsNull() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        Object result = transport.execute(
                Endpoint.of(Endpoint.Method.DELETE, "/v2/users/{user_openid}/messages/{message_id}",
                        SendResult.class),
                Params.of().pathValue("user_openid", "U").pathValue("message_id", "M"), null);
        assertNull(result);
        assertEquals("DELETE", server.takeRequest().getMethod());
    }

    @Test
    void flattensGetBodyIntoQueryParameters() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"list\":[],\"next_cursor\":\"\"}"));
        transport.execute(
                Endpoint.of(Endpoint.Method.GET, "/v2/groups/{group_openid}/join_request_list", CursorPage.class),
                Params.of().pathValue("group_openid", "G"),
                new ListFilter("cursor-1", 50));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/v2/groups/G/join_request_list?cursor=cursor-1&limit=50", recorded.getPath());
        assertTrue(recorded.getBody().size() == 0, "GET must not carry a request body");
    }

    @Test
    void retriesRateLimitThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"err_code\":429,\"message\":\"slow\"}"));
        server.enqueue(new MockResponse().setBody("{\"id\":\"OK\"}"));
        SendResult result = transport.execute(
                Endpoint.of(Endpoint.Method.GET, "/gateway/bot", SendResult.class), Params.of(), null);
        assertEquals("OK", result.id);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void surfacesRateLimitAfterExhaustingRetries() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"err_code\":429,\"message\":\"slow\"}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"err_code\":429,\"message\":\"slow\"}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"err_code\":429,\"message\":\"slow\"}"));
        ApiException e = assertThrows(ApiException.class, () -> transport.execute(
                Endpoint.of(Endpoint.Method.GET, "/gateway", SendResult.class), Params.of(), null));
        assertEquals(429, e.httpStatus());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void refreshesTokenOnceOnUnauthorized() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        BotConfig config = BotConfig.builder("987654321")
                .clientSecret("s3cr3t")
                .apiBase(server.url("/").toString().replaceAll("/$", ""))
                .maxRetries(0)
                .build();
        HttpTransport t = new HttpTransport(config, HttpTransport.defaultClient(config),
                new AccessTokenProvider(config, () -> {
                    int n = fetches.incrementAndGet();
                    return new AccessToken("TOKEN_" + n, 7200);
                }));
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"err_code\":11243,\"message\":\"token invalid\"}"));
        server.enqueue(new MockResponse().setBody("{\"id\":\"after-refresh\"}"));

        SendResult result = t.execute(
                Endpoint.of(Endpoint.Method.GET, "/users/@me", SendResult.class), Params.of(), null);
        assertEquals("after-refresh", result.id);
        assertEquals(2, fetches.get(), "the rejected token is dropped and one replacement is fetched");
        assertEquals("QQBot TOKEN_1", server.takeRequest().getHeader("Authorization"));
        assertEquals("QQBot TOKEN_2", server.takeRequest().getHeader("Authorization"));
        t.close();
    }

    @Test
    void multipartReachesTheServerAsFormData() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"SENT\"}"));
        MultipartBody part = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("content", "<@!1234>hello")
                .addFormDataPart("file_image", "a.png",
                        RequestBody.create("png-bytes".getBytes(StandardCharsets.UTF_8), MediaType.parse("image/png")))
                .build();
        transport.executeRawBody(
                Endpoint.of(Endpoint.Method.POST, "/channels/{channel_id}/messages", SendResult.class),
                Params.of().pathValue("channel_id", "10"), part);

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getHeader("Content-Type").startsWith("multipart/form-data"),
                recorded.getHeader("Content-Type"));
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("name=\"file_image\""), body);
        assertTrue(body.contains("<@!1234>hello"), body);
    }

    @Test
    void putBytesUploadsToPresignedUrlWithoutAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        transport.putBytes(server.url("/presigned/abc").toString(),
                "chunk".getBytes(StandardCharsets.UTF_8), MediaType.parse("application/octet-stream"));
        RecordedRequest recorded = server.takeRequest();
        assertEquals("PUT", recorded.getMethod());
        assertEquals("/presigned/abc", recorded.getPath());
        assertEquals("chunk", recorded.getBody().readUtf8());
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    void endpointBucketCollapsesPlaceholders() {
        Endpoint<SendResult> e = Endpoint.of(Endpoint.Method.POST, "/v2/groups/{group_openid}/messages",
                SendResult.class);
        assertEquals("/v2/groups/*/messages", e.bucket());
        assertEquals("POST /v2/groups/{group_openid}/messages", e.toString());
    }

    @Test
    void missingPathVariableFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> transport.execute(
                Endpoint.of(Endpoint.Method.DELETE, "/v2/groups/{group_openid}/messages/{message_id}",
                        SendResult.class),
                Params.of().pathValue("group_openid", "G"), null));
    }

    @Test
    void listOfResponseTypeDecodesArrays() {
        server.enqueue(new MockResponse().setBody("[{\"id\":\"a\"},{\"id\":\"b\"}]"));
        List<SendResult> results = transport.execute(
                Endpoint.of(Endpoint.Method.GET, "/guilds/{guild_id}/channels", Endpoint.listOf(SendResult.class)),
                Params.of().pathValue("guild_id", "1"), null);
        assertEquals(2, results.size());
        assertEquals("b", results.get(1).id);
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"err_code\":500,\"message\":\"boom\"}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"err_code\":500,\"message\":\"boom\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"late\"}"));
        SendResult r = transport.execute(
                Endpoint.of(Endpoint.Method.GET, "/v2/menu", SendResult.class), Params.of(), null);
        assertEquals("late", r.id);
        assertEquals(3, server.getRequestCount());
    }

    @SuppressWarnings("unused")
    private static final class SendResult {
        @SerializedName("id")
        String id;
        @SerializedName("timestamp")
        String timestamp;
    }

    @SuppressWarnings("unused")
    private static final class SendBody {
        @SerializedName("content")
        final String content;
        @SerializedName("msg_type")
        final Integer msgType;

        SendBody(String content, Integer msgType) {
            this.content = content;
            this.msgType = msgType;
        }
    }

    @SuppressWarnings("unused")
    private static final class ListFilter {
        @SerializedName("cursor")
        final String cursor;
        @SerializedName("limit")
        final Integer limit;

        ListFilter(String cursor, Integer limit) {
            this.cursor = cursor;
            this.limit = limit;
        }
    }

    @SuppressWarnings("unused")
    private static final class CursorPage {
        @SerializedName("list")
        List<SendResult> list;
        @SerializedName("next_cursor")
        String nextCursor;
    }
}
