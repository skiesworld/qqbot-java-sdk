package io.github.skiesworld.qqbot.media;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.http.Params;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two rich-media scenes. Uploads are scene-isolated: a file uploaded for a c2c conversation
 * cannot be sent in a group and the other way round.
 */
public enum MediaTarget {

    C2C("/v2/users/{user_id}/upload_prepare",
            "/v2/users/{user_id}/upload_part_finish",
            "/v2/users/{user_openid}/files"),
    GROUP("/v2/groups/{group_id}/upload_prepare",
            "/v2/groups/{group_id}/upload_part_finish",
            "/v2/groups/{group_openid}/files");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final Endpoint<JsonObject> prepare;
    private final Endpoint<JsonObject> partFinish;
    private final Endpoint<MediaFile> files;

    MediaTarget(String preparePath, String partFinishPath, String filesPath) {
        this.prepare = Endpoint.of(Endpoint.Method.POST, preparePath, JsonObject.class);
        this.partFinish = Endpoint.of(Endpoint.Method.POST, partFinishPath, JsonObject.class);
        this.files = Endpoint.of(Endpoint.Method.POST, filesPath, MediaFile.class);
    }

    public Endpoint<JsonObject> prepare() {
        return prepare;
    }

    public Endpoint<JsonObject> partFinish() {
        return partFinish;
    }

    public Endpoint<MediaFile> files() {
        return files;
    }

    /** Bind the conversation openid to whichever placeholder this endpoint uses. */
    public Params params(String template, String openid) {
        Matcher m = PLACEHOLDER.matcher(template);
        Params params = Params.of();
        while (m.find()) {
            params.pathValue(m.group(1), openid);
        }
        return params;
    }
}
