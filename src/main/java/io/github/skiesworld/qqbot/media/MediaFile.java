package io.github.skiesworld.qqbot.media;

import com.google.gson.annotations.SerializedName;

/** Result of a media upload; {@code fileInfo} feeds {@code msg_type=7} messages. */
public final class MediaFile {

    @SerializedName("file_uuid")
    public String fileUuid;

    @SerializedName("file_info")
    public String fileInfo;

    /** Seconds the handle stays valid; re-upload after that. */
    @SerializedName("ttl")
    public Long ttl;

    @SerializedName("id")
    public String id;

    @SerializedName("raw_url")
    public String rawUrl;

    public boolean expired(long issuedAtMillis, long nowMillis) {
        return ttl != null && issuedAtMillis + ttl * 1000L < nowMillis;
    }

    @Override
    public String toString() {
        return "MediaFile{fileUuid=" + fileUuid + ", ttl=" + ttl + "}";
    }
}
