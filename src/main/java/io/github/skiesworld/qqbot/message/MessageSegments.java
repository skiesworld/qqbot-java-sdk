package io.github.skiesworld.qqbot.message;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.model.ArkData;
import io.github.skiesworld.qqbot.model.MessageAttachment;
import io.github.skiesworld.qqbot.model.User;
import io.github.skiesworld.qqbot.media.FileType;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.util.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A received message read as an ordered list of {@link Segment}s.
 *
 * <p>The QQ API v2 payload keeps text and media in <em>sibling fields</em>, not in one markup string:
 * {@code content} carries the plain text, {@code attachments[]} the images, videos, voice notes and files,
 * {@code ark_data} the card, {@code msg_elements[]} nested messages. There is therefore no
 * {@code <image>} placeholder to split the text on and no offset telling you where in the sentence an image
 * sat. {@link #segments()} returns the text first, then the mentions, attachments and card in the order the
 * platform listed them, which is a grouping of the fields rather than a reconstruction of the bubble —
 * compare {@link Segment.Media}, whose url is all you get to line up with the text.
 *
 * <pre>{@code
 * MessageSegments msg = MessageSegments.of(event);
 * if (msg.messageType() == 0 && !msg.text().isBlank()) {
 *     ...
 * }
 * for (Segment.Media media : msg.segmentsOfType(Segment.Media.class)) {
 *     download(media.attachment().url);
 * }
 * }</pre>
 */
public final class MessageSegments {

    private final long messageType;
    private final List<Segment> segments;

    private MessageSegments(long messageType, List<Segment> segments) {
        this.messageType = messageType;
        this.segments = List.copyOf(segments);
    }

    /** The dispatch payload; an empty result for events that are not messages at all. */
    public static MessageSegments of(QQEvent event) {
        return fromJson(event.rawObject());
    }

    /**
     * Any message-shaped object: the {@code d} of a message event, or a {@code Message} from a send or list
     * call. Unknown or missing members are skipped rather than rejected, because the platform keeps adding
     * element kinds.
     */
    public static MessageSegments fromJson(JsonObject payload) {
        return new MessageSegments(longOr(payload, "message_type", 0L), segmentsOf(payload));
    }

    private static List<Segment> segmentsOf(JsonObject payload) {
        List<Segment> out = new ArrayList<>();
        String content = strings(payload, "content");
        if (content != null) {
            out.add(new Segment.Text(content));
        }
        for (User user : listOf(payload, "mentions", User[].class)) {
            out.add(new Segment.Mention(user));
        }
        for (MessageAttachment attachment : listOf(payload, "attachments", MessageAttachment[].class)) {
            out.add(new Segment.Media(kindOf(attachment), attachment));
        }
        if (payload.get("ark_data") instanceof JsonObject ark) {
            ArkData data = Json.GSON.fromJson(ark, ArkData.class);
            if (data != null) {
                out.add(new Segment.Card(data));
            }
        }
        for (JsonElement element : arrayOr(payload, "msg_elements")) {
            if (element instanceof JsonObject o) {
                out.add(new Segment.Element(longOr(o, "message_type", 0L), strings(o, "msg_idx"), segmentsOf(o),
                        authorOf(o)));
            }
        }
        return out;
    }

    private static User authorOf(JsonObject payload) {
        return payload.get("author") instanceof JsonObject author
                ? Json.GSON.fromJson(author, User.class) : null;
    }

    /** {@code voice} and {@code file} are literal content types; everything else follows the MIME prefix. */
    static FileType kindOf(MessageAttachment attachment) {
        String type = attachment.contentType;
        if (Strings.isBlank(type)) {
            return FileType.FILE;
        }
        String t = type.toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("image/")) {
            return FileType.IMAGE;
        }
        if (t.startsWith("video/")) {
            return FileType.VIDEO;
        }
        if (t.startsWith("audio/") || t.equals("voice")) {
            return FileType.VOICE;
        }
        return FileType.FILE;
    }

    private static JsonArray arrayOr(JsonObject payload, String key) {
        return payload.get(key) instanceof JsonArray a ? a : new JsonArray();
    }

    private static <T> List<T> listOf(JsonObject payload, String key, Class<T[]> type) {
        JsonArray array = arrayOr(payload, key);
        if (array.isEmpty()) {
            return List.of();
        }
        T[] parsed = Json.GSON.fromJson(array, type);
        return parsed == null ? List.of() : Arrays.stream(parsed).filter(Objects::nonNull).toList();
    }

    private static String strings(JsonObject payload, String key) {
        JsonElement e = payload.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static long longOr(JsonObject payload, String key, long fallback) {
        String raw = strings(payload, key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Text of the top-level segments, i.e. the {@code content} string itself. */
    public String text() {
        return textOf(segments);
    }

    /** All text at any depth, in segment order, skipping the nesting. */
    public String allText() {
        StringBuilder sb = new StringBuilder();
        appendText(segments, sb);
        return sb.toString();
    }

    public long messageType() {
        return messageType;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** The segments of one kind, at the top level. */
    public <T extends Segment> List<T> segmentsOfType(Class<T> kind) {
        List<T> out = new ArrayList<>();
        for (Segment s : segments) {
            if (kind.isInstance(s)) {
                out.add(kind.cast(s));
            }
        }
        return out;
    }

    public List<User> mentions() {
        return segmentsOfType(Segment.Mention.class).stream().map(Segment.Mention::user).toList();
    }

    public List<MessageAttachment> media() {
        return segmentsOfType(Segment.Media.class).stream().map(Segment.Media::attachment).toList();
    }

    public boolean hasMedia() {
        return !media().isEmpty();
    }

    /** The card payload, or null when the message is not {@code message_type=3}. */
    public ArkData card() {
        for (Segment s : segments) {
            if (s instanceof Segment.Card card) {
                return card.data();
            }
        }
        return null;
    }

    /** The nested {@code msg_elements[]} of a quote or chat record, empty for a plain message. */
    public List<Segment.Element> elements() {
        return segmentsOfType(Segment.Element.class);
    }

    static String textOf(List<Segment> segments) {
        for (Segment s : segments) {
            if (s instanceof Segment.Text text) {
                return text.value();
            }
        }
        return "";
    }

    private static void appendText(List<Segment> segments, StringBuilder out) {
        for (Segment s : segments) {
            if (s instanceof Segment.Text text) {
                out.append(text.value());
            } else if (s instanceof Segment.Element element) {
                appendText(element.segments(), out);
            }
        }
    }
}
