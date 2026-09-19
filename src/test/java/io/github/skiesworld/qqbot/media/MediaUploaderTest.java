package io.github.skiesworld.qqbot.media;

import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.util.Digests;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the documented four-step chunked upload, the URL variant and the instant-upload shortcut. */
class MediaUploaderTest {

    private MockWebServer server;
    private HttpTransport transport;
    private MediaUploader uploader;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        BotConfig config = BotConfig.builder("123")
                .accessToken("TK")
                .apiBase("http://" + server.getHostName() + ":" + server.getPort())
                .maxRetries(0)
                .retryBaseDelay(Duration.ofMillis(1))
                .build();
        transport = new HttpTransport(config);
        uploader = new MediaUploader(transport);
    }

    @AfterEach
    void tearDown() throws IOException {
        transport.close();
        server.close();
    }

    @Test
    void uploadsChunksThenMerges() throws Exception {
        byte[] data = repeat("a", 8).getBytes(StandardCharsets.UTF_8); // two 4-byte blocks
        server.enqueue(new MockResponse().setBody("{\"upload_id\":\"UP1\",\"block_size\":\"4\",\"parts\":["
                + "{\"index\":0,\"presigned_url\":\"" + server.url("/blob/0").toString() + "\",\"block_size\":\"4\"},"
                + "{\"index\":1,\"presigned_url\":\"" + server.url("/blob/1").toString() + "\",\"block_size\":\"4\"}],"
                + "\"upload_config\":{\"concurrency\":1,\"retry_timeout\":1000,\"retry_delay\":10}}"));
        // two chunk PUTs and two part_finish calls, then the merge
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse().setBody(
                "{\"file_uuid\":\"FU1\",\"file_info\":\"FI1\",\"ttl\":3600}"));

        MediaFile result = uploader.uploadBytes(MediaTarget.C2C, "USER_OPENID", "note.txt", FileType.FILE, data);

        assertEquals("FU1", result.fileUuid);
        assertEquals("FI1", result.fileInfo);
        assertEquals(3600L, result.ttl);

        RecordedRequest prepare = server.takeRequest();
        assertEquals("POST", prepare.getMethod());
        assertEquals("/v2/users/USER_OPENID/upload_prepare", prepare.getPath());
        String prepareBody = prepare.getBody().readUtf8();
        assertTrue(prepareBody.contains("\"file_size\":\"8\""), prepareBody);
        assertTrue(prepareBody.contains("\"md5\":\"" + Digests.md5Hex(data) + "\""), prepareBody);
        assertTrue(prepareBody.contains("\"sha1\":\"" + Digests.sha1Hex(data) + "\""), prepareBody);
        // md5_10m is the digest of the first 10_002_432 bytes, which for a small file is the whole file
        assertTrue(prepareBody.contains("\"md5_10m\":\"" + Digests.md5Hex(data) + "\""), prepareBody);

        assertEquals("aaaa", server.takeRequest().getBody().readUtf8());
        RecordedRequest finish0 = server.takeRequest();
        assertEquals("/v2/users/USER_OPENID/upload_part_finish", finish0.getPath());
        assertTrue(finish0.getBody().readUtf8().contains("\"part_index\":0"));

        assertEquals("aaaa", server.takeRequest().getBody().readUtf8());
        RecordedRequest finish1 = server.takeRequest();
        assertTrue(finish1.getBody().readUtf8().contains("\"part_index\":1"));

        RecordedRequest merge = server.takeRequest();
        assertEquals("/v2/users/USER_OPENID/files", merge.getPath());
        assertTrue(merge.getBody().readUtf8().contains("\"upload_id\":\"UP1\""));
    }

    @Test
    void groupSceneUsesGroupEndpoints() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"upload_id\":\"UP2\",\"parts\":[]}"));
        server.enqueue(new MockResponse().setBody("{\"file_info\":\"FI2\",\"file_uuid\":\"FU2\"}"));
        MediaFile result = uploader.uploadBytes(MediaTarget.GROUP, "GROUP_OPENID", "pic.png",
                FileType.IMAGE, "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("FI2", result.fileInfo);
        assertEquals("/v2/groups/GROUP_OPENID/upload_prepare", server.takeRequest().getPath());
        assertEquals("/v2/groups/GROUP_OPENID/files", server.takeRequest().getPath());
    }

    @Test
    void emptyPartsMeansInstantUpload() {
        server.enqueue(new MockResponse().setBody("{\"upload_id\":\"UP3\",\"parts\":[]}"));
        server.enqueue(new MockResponse().setBody("{\"file_info\":\"FI3\"}"));
        uploader.uploadBytes(MediaTarget.C2C, "U", "x.bin", FileType.FILE, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, server.getRequestCount(), "no chunk PUTs when the platform already has the file");
    }

    @Test
    void urlUploadSendsOnlyTheMergeCall() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"file_info\":\"FI4\",\"ttl\":60}"));
        MediaFile result = uploader.uploadFromUrl(MediaTarget.C2C, "U9", FileType.VIDEO,
                "https://example.com/a.mp4", true);
        assertEquals("FI4", result.fileInfo);
        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v2/users/U9/files", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"url\":\"https://example.com/a.mp4\""), body);
        assertTrue(body.contains("\"file_type\":2"), body);
        assertTrue(body.contains("\"srv_send_msg\":true"), body);
    }

    @Test
    void rejectsSizesAboveTheHardLimitBeforeTouchingTheNetwork() {
        long over = FileType.VIDEO.hardLimitBytes() + 1;
        assertThrows(IllegalArgumentException.class, () -> MediaUploader.validateSize(FileType.VIDEO, over));
        MediaUploader.validateSize(FileType.IMAGE, 1024);
        assertEquals(0, server.getRequestCount());
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }
}
