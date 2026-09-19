package io.github.skiesworld.qqbot.auth;

import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.error.AuthException;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTokenProviderTest {

    private MockWebServer server;
    private OkHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        server.close();
    }

    private BotConfig config() {
        return BotConfig.builder("APPID1")
                .clientSecret("SECRET1")
                .apiBase("http://" + server.getHostName() + ":" + server.getPort())
                .maxRetries(0)
                .tokenRefreshMargin(Duration.ofSeconds(60))
                .build();
    }

    @Test
    void postsAppIdAndSecretAndReadsToken() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"AT1\",\"expires_in\":\"7200\"}"));
        AccessToken token = new AccessTokenProvider(config(), client).get();
        assertEquals("AT1", token.token());
        assertEquals("QQBot AT1", token.authorization());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals(BotConfig.TOKEN_PATH, recorded.getPath());
        assertEquals("{\"appId\":\"APPID1\",\"clientSecret\":\"SECRET1\"}", recorded.getBody().readUtf8());
    }

    /** expires_in arrives as a string in some responses and the platform accepts both forms. */
    @Test
    void acceptsNumericOrStringExpiry() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"AT2\",\"expires_in\":3600}"));
        AccessToken numeric = new AccessTokenProvider(config(), client).get();
        assertTrue(numeric.expiresAtMillis() > System.currentTimeMillis() + 3500_000L);
    }

    @Test
    void cachesUntilTheRefreshMargin() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AccessTokenProvider provider = new AccessTokenProvider(config(), () -> {
            fetches.incrementAndGet();
            return new AccessToken("T" + fetches.get(), 7200);
        });
        AccessToken first = provider.get();
        assertSame(first, provider.get());
        assertSame(first, provider.get());
        assertEquals(1, fetches.get());

        provider.invalidate();
        AccessToken second = provider.get();
        assertNotSame(first, second);
        assertEquals(2, fetches.get());
    }

    @Test
    void refetchsInsideTheExpiryMargin() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        BotConfig shortMargin = config().toBuilder().tokenRefreshMargin(Duration.ofMinutes(1)).build();
        AccessTokenProvider provider = new AccessTokenProvider(shortMargin, () -> {
            fetches.incrementAndGet();
            // 30s of life left, inside the 60s margin -> must be replaced
            return new AccessToken("T" + fetches.get(), 30);
        });
        provider.get();
        provider.get();
        assertEquals(2, fetches.get());
    }

    @Test
    void concurrentCallsFetchOnce() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        AccessTokenProvider provider = new AccessTokenProvider(config(), () -> {
            try {
                start.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AccessToken("SHARED" + fetches.incrementAndGet(), 7200);
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            AccessToken[] results = new AccessToken[4];
            for (int i = 0; i < 4; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        results[index] = provider.get();
                    } catch (IOException ignored) {
                        // the latch above always releases
                    }
                });
            }
            Thread.sleep(60);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, fetches.get(), "a single token request serves every caller");
            for (AccessToken r : results) {
                assertSame(results[0], r);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The platform answers HTTP 200 even when the credential is rejected, so code decides. */
    @Test
    void rejectsBusinessErrorReturnedWithHttp200() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":100016,\"message\":\"invalid appid or secret\"}"));
        AuthException e = assertThrows(AuthException.class, () -> new AccessTokenProvider(config(), client).get());
        assertEquals(100016, e.code());
        assertTrue(e.getMessage().contains("invalid appid"), e.getMessage());
    }

    @Test
    void wrapsUnparseableTokenResponses() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        assertThrows(AuthException.class, () -> new AccessTokenProvider(config(), client).get());
    }

    @Test
    void staticTokenSkipsTheNetwork() throws Exception {
        BotConfig cfg = config().toBuilder().accessToken("PRESET").build();
        AccessTokenProvider provider = AccessTokenProvider.ofStaticToken(cfg);
        assertEquals("QQBot PRESET", provider.authorization());
        assertEquals(0, server.getRequestCount());
    }
}
