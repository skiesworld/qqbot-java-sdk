package io.github.skiesworld.qqbot.websocket;

import com.google.gson.annotations.SerializedName;

/** Response of {@code GET /gateway} and {@code GET /gateway/bot}. */
public final class GatewayInfo {

    @SerializedName("url")
    public String url;

    /** Recommended shard count for this bot. */
    @SerializedName("shards")
    public Integer shards;

    @SerializedName("session_start_limit")
    public SessionStartLimit sessionStartLimit;

    public static final class SessionStartLimit {
        @SerializedName("total")
        public Integer total;
        @SerializedName("remaining")
        public Integer remaining;
        /** Milliseconds until the start quota resets. */
        @SerializedName("reset_after")
        public Long resetAfter;
        @SerializedName("max_concurrency")
        public Integer maxConcurrency;
    }

    @Override
    public String toString() {
        return "GatewayInfo{url=" + url + ", shards=" + shards + "}";
    }
}
