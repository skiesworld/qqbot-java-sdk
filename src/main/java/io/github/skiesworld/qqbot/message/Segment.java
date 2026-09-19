package io.github.skiesworld.qqbot.message;

import io.github.skiesworld.qqbot.model.ArkData;
import io.github.skiesworld.qqbot.model.MessageAttachment;
import io.github.skiesworld.qqbot.model.User;
import io.github.skiesworld.qqbot.media.FileType;

import java.util.List;

/**
 * One piece of a received message, as far as the wire lets us cut it up.
 *
 * <p>Read {@link MessageSegments} first: the platform sends the text of a message in one string and its
 * attachments in a sibling array, so a {@link Media} segment has no position inside that string to be placed
 * back into. That is a property of the payload, not of this API, and nothing here pretends otherwise.
 */
public sealed interface Segment {

    /** The whole {@code content} string of the message, or of one nested element. */
    record Text(String value) implements Segment {
    }

    /**
     * A mentioned user from {@code mentions[]}. The group events already strip the {@code @机器人} prefix from
     * {@code content}, so a mention is known to exist but not where it sat.
     */
    record Mention(User user) implements Segment {
    }

    /** An image, video, voice note or file from {@code attachments[]}, typed by its {@code content_type}. */
    record Media(FileType kind, MessageAttachment attachment) implements Segment {
    }

    /** A structured card, i.e. {@code ark_data} ({@code message_type=3}). */
    record Card(ArkData data) implements Segment {
    }

    /**
     * A nested element from {@code msg_elements[]}, which is how a quote ({@code message_type=103}) and a chat
     * record ({@code 102}) carry the original messages; the structure recurses.
     *
     * @param msgIdx the {@code msg_idx} the platform uses to name this message in a quote context
     */
    record Element(long messageType, String msgIdx, List<Segment> segments, User author) implements Segment {

        public Element {
            segments = List.copyOf(segments);
        }

        public String text() {
            return MessageSegments.textOf(segments);
        }
    }
}
