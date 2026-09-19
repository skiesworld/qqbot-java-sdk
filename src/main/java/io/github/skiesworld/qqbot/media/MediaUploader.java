package io.github.skiesworld.qqbot.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.error.QQBotException;
import io.github.skiesworld.qqbot.http.HttpTransport;
import io.github.skiesworld.qqbot.http.Params;
import io.github.skiesworld.qqbot.util.Digests;
import io.github.skiesworld.qqbot.util.Strings;
import okhttp3.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The four-step rich-media flow: {@code upload_prepare} -> PUT each chunk to its presigned url ->
 * {@code upload_part_finish} per chunk -> the scene's {@code files} call merges them and returns
 * {@code file_info}.
 *
 * <p>Chunk size, concurrency and the per-chunk retry policy come from {@code upload_config}, which the
 * server hands out per upload. When prepare returns no parts the file is already known to the platform
 * and the merge call is issued directly.
 */
public final class MediaUploader {

    /** Length of the prefix whose MD5 ({@code md5_10m}) the platform uses for the instant-upload check. */
    public static final int HEAD_SIZE = 10_002_432;
    public static final long DEFAULT_BLOCK_SIZE = 5L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MediaUploader.class);
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private final HttpTransport http;

    public MediaUploader(HttpTransport http) {
        this.http = http;
    }

    /** Let the platform download and store a publicly reachable file. */
    public MediaFile uploadFromUrl(MediaTarget target, String openid, FileType type, String url) {
        return uploadFromUrl(target, openid, type, url, false);
    }

    /** @param sendImmediately {@code srv_send_msg} delivers while uploading, at the cost of active-message quota. */
    public MediaFile uploadFromUrl(MediaTarget target, String openid, FileType type, String url,
                                   boolean sendImmediately) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file_type", type.value());
        body.put("url", Strings.requireNonBlank(url, "url"));
        body.put("srv_send_msg", sendImmediately);
        return http.execute(target.files(), target.params(target.files().pathTemplate(), openid), body);
    }

    public MediaFile uploadBytes(MediaTarget target, String openid, String fileName, FileType type, byte[] data) {
        return upload(target, openid, fileName, type, data);
    }

    public MediaFile uploadFile(MediaTarget target, String openid, Path file, FileType type) {
        try {
            return upload(target, openid, file.getFileName().toString(), type, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new QQBotException("cannot read " + file, e);
        }
    }

    private MediaFile upload(MediaTarget target, String openid, String fileName, FileType type, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("refusing to upload an empty file");
        }
        validateSize(type, data.length);
        Params params = target.params(target.prepare().pathTemplate(), openid);
        Map<String, Object> prepare = new LinkedHashMap<>();
        prepare.put("file_type", type.value());
        prepare.put("file_size", String.valueOf(data.length));
        prepare.put("file_name", Strings.requireNonBlank(fileName, "fileName"));
        prepare.put("md5", Digests.md5Hex(data));
        prepare.put("sha1", Digests.sha1Hex(data));
        prepare.put("md5_10m", Digests.md5Hex(data, 0, Math.min(data.length, HEAD_SIZE)));

        JsonObject prepared = http.execute(target.prepare(), params, prepare);
        if (prepared == null || !prepared.has("upload_id")) {
            throw new QQBotException("upload_prepare returned no upload_id");
        }
        String uploadId = prepared.get("upload_id").getAsString();
        JsonArray parts = prepared.has("parts") ? prepared.getAsJsonArray("parts") : new JsonArray();
        if (parts.size() > 0) {
            UploadConfig config = UploadConfig.from(prepared);
            transferChunks(target, openid, uploadId, fileName, data, toParts(parts, data.length), config);
        } else {
            log.debug("instant upload for {}, no chunks to send", fileName);
        }
        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("upload_id", uploadId);
        merge.put("file_type", type.value());
        merge.put("file_name", fileName);
        MediaFile result = http.execute(target.files(),
                target.params(target.files().pathTemplate(), openid), merge);
        if (result == null || Strings.isBlank(result.fileInfo)) {
            throw new QQBotException("media merge returned no file_info for " + fileName);
        }
        return result;
    }

    /** Sizes beyond the hard limit are rejected by the platform, so reject them before sending bytes. */
    static void validateSize(FileType type, long size) {
        if (size > type.hardLimitBytes()) {
            throw new IllegalArgumentException(type + " exceeds the hard limit of "
                    + type.hardLimitBytes() + " bytes, got " + size);
        }
        if (size > type.softLimitBytes()) {
            log.warn("{} bytes passes the {} soft limit of {} and will be downgraded to FILE",
                    size, type, type.softLimitBytes());
        }
    }

    private void transferChunks(MediaTarget target, String openid, String uploadId, String fileName,
                                byte[] data, List<Chunk> chunks, UploadConfig config) {
        int threads = (int) Math.max(1, Math.min(config.concurrency, chunks.size()));
        ExecutorService pool = threads == 1 ? null : Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "qqbot-upload-" + fileName);
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (Chunk chunk : chunks) {
                Runnable task = () -> sendChunk(target, openid, uploadId, data, chunk, config);
                if (pool == null) {
                    task.run();
                } else {
                    pending.add(pool.submit(task));
                }
            }
            for (Future<?> f : pending) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new QQBotException("upload interrupted", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new QQBotException("chunk upload failed", e.getCause());
                }
            }
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
    }

    private void sendChunk(MediaTarget target, String openid, String uploadId, byte[] data, Chunk chunk,
                           UploadConfig config) {
        byte[] slice = new byte[chunk.length];
        System.arraycopy(data, (int) Math.min(chunk.offset, Integer.MAX_VALUE), slice, 0, chunk.length);
        long deadline = System.currentTimeMillis() + config.retryTimeoutMillis;
        int attempt = 0;
        while (true) {
            try {
                http.putBytes(chunk.presignedUrl, slice, OCTET_STREAM);
                Map<String, Object> finish = new LinkedHashMap<>();
                finish.put("upload_id", uploadId);
                finish.put("part_index", chunk.index);
                finish.put("block_size", String.valueOf(chunk.blockSize));
                finish.put("md5", Digests.md5Hex(slice));
                http.execute(target.partFinish(),
                        target.params(target.partFinish().pathTemplate(), openid), finish);
                return;
            } catch (RuntimeException e) {
                if (System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                attempt++;
                log.debug("chunk {} attempt {} failed, retrying", chunk.index, attempt, e);
                sleep(config.retryDelayMillis);
            }
        }
    }

    private static List<Chunk> toParts(JsonArray parts, long dataSize) {
        List<Chunk> out = new ArrayList<>(parts.size());
        for (JsonElement element : parts) {
            JsonObject o = element.getAsJsonObject();
            long blockSize = readLong(o, "block_size", DEFAULT_BLOCK_SIZE);
            long index = readLong(o, "index", out.size());
            String url = o.has("presigned_url") ? o.get("presigned_url").getAsString() : null;
            if (Strings.isBlank(url)) {
                throw new QQBotException("upload_prepare returned a part without presigned_url");
            }
            out.add(new Chunk((int) index, blockSize, url, dataSize));
        }
        return out;
    }

    private static long readLong(JsonObject o, String field, long fallback) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement v = o.get(field);
        try {
            return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                    ? Long.parseLong(v.getAsString().trim()) : v.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QQBotException("upload interrupted while waiting to retry", e);
        }
    }

    private static final class Chunk {
        final int index;
        final long blockSize;
        final String presignedUrl;
        final int length;
        final long offset;

        Chunk(int index, long blockSize, String presignedUrl, long dataSize) {
            this.index = index;
            this.blockSize = blockSize;
            this.presignedUrl = presignedUrl;
            this.offset = Math.min(index * blockSize, dataSize);
            this.length = (int) Math.max(0, Math.min(blockSize, dataSize - this.offset));
        }
    }

    /** Server-supplied tuning from {@code upload_config}. */
    static final class UploadConfig {
        int concurrency = 1;
        long retryTimeoutMillis = 30_000;
        long retryDelayMillis = 500;

        static UploadConfig from(JsonObject prepared) {
            UploadConfig c = new UploadConfig();
            if (!prepared.has("upload_config") || !prepared.get("upload_config").isJsonObject()) {
                return c;
            }
            JsonObject o = prepared.getAsJsonObject("upload_config");
            c.concurrency = (int) readLong(o, "concurrency", c.concurrency);
            c.retryTimeoutMillis = readLong(o, "retry_timeout", c.retryTimeoutMillis);
            c.retryDelayMillis = readLong(o, "retry_delay", c.retryDelayMillis);
            return c;
        }
    }
}
