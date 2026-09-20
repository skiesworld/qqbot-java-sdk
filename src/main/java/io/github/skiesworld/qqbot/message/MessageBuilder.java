package io.github.skiesworld.qqbot.message;

import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.model.Keyboard;
import io.github.skiesworld.qqbot.model.MediaInfo;
import io.github.skiesworld.qqbot.model.MessageArk;
import io.github.skiesworld.qqbot.model.MessageMarkdown;
import io.github.skiesworld.qqbot.model.MessageReference;
import io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest;
import io.github.skiesworld.qqbot.model.request.SendChannelMessageRequest;
import io.github.skiesworld.qqbot.model.request.SendGroupMessageRequest;
import io.github.skiesworld.qqbot.util.Strings;

import java.util.Objects;

/**
 * Builds an outgoing message and lowers it onto the fields each scene actually accepts.
 *
 * <p>One body at a time, because that is what the wire allows: {@code msg_type} picks text (0), markdown (2)
 * or rich media (7) for private and group chats, while sub-channels and direct messages have no
 * {@code msg_type} and choose between {@code content}, {@code markdown}, {@code ark} and {@code image}.
 * Combining two bodies, or putting a scene's unsupported body in it, fails here instead of sending something the
 * platform will reject.
 *
 * <p>Passive replies carry {@code msg_id} or {@code event_id} plus a {@code msg_seq} that must be unique per
 * original message — the docs reject a repeat of the same pair, so use
 * {@link io.github.skiesworld.qqbot.event.QQMessageEvent#reply(String)} or
 * {@link ReplySequence} rather than reusing one sequence.
 *
 * <p>Anything this class does not expose (an {@code embed}, an {@code input_notify}, a markdown template id) is
 * reachable by mutating the returned request: its fields are public.
 */
public final class MessageBuilder {

    private String text;
    private String markdown;
    private String fileInfo;
    private MessageArk ark;
    private Keyboard keyboard;
    private String msgId;
    private String eventId;
    private Long msgSeq;
    private String quotedMessageId;

    private MessageBuilder() {
    }

    public static MessageBuilder create() {
        return new MessageBuilder();
    }

    public static MessageBuilder of(String text) {
        return new MessageBuilder().text(text);
    }

    /** Plain text, i.e. {@code msg_type=0}. Replaces any other body already chosen. */
    public MessageBuilder text(String value) {
        return body(Strings.requireNonBlank(value, "text"), null, null, null);
    }

    /** Markdown, i.e. {@code msg_type=2} in private and group chats. */
    public MessageBuilder markdown(String value) {
        return body(null, Strings.requireNonBlank(value, "markdown"), null, null);
    }

    /**
     * One uploaded file, i.e. {@code msg_type=7}. Get {@code fileInfo} from
     * {@link io.github.skiesworld.qqbot.media.MediaUploader}; it expires after its {@code ttl} and is valid only
     * in the scene that uploaded it.
     */
    public MessageBuilder media(String fileInfo) {
        return body(null, null, Strings.requireNonBlank(fileInfo, "fileInfo"), null);
    }

    /** A structured card. Sub-channels and direct messages only. */
    public MessageBuilder ark(MessageArk value) {
        return body(null, null, null, Objects.requireNonNull(value, "ark"));
    }

    /** Inline buttons, private and group chats only. */
    public MessageBuilder keyboard(Keyboard value) {
        this.keyboard = Objects.requireNonNull(value, "keyboard");
        return this;
    }

    /** Reply to a message by id; {@link #replyTo(QQEvent)} is the usual form. */
    public MessageBuilder replyTo(String messageId) {
        this.msgId = Strings.requireNonBlank(messageId, "messageId");
        this.eventId = null;
        return this;
    }

    /** Reply to the message an event carried, as a passive message. */
    public MessageBuilder replyTo(QQEvent event) {
        return replyTo(event.id());
    }

    /** Answer an event by id, for interactions and other non-message dispatches. */
    public MessageBuilder replyToEvent(String eventId) {
        this.eventId = Strings.requireNonBlank(eventId, "eventId");
        this.msgId = null;
        return this;
    }

    public MessageBuilder replyToEvent(QQEvent event) {
        return replyToEvent(event.id());
    }

    /** {@code msg_seq}: any value, as long as it has not been used for the same {@code msg_id} before. */
    public MessageBuilder seq(long msgSeq) {
        this.msgSeq = msgSeq;
        return this;
    }

    /** Quote another message with {@code message_reference}. */
    public MessageBuilder quote(String messageId) {
        this.quotedMessageId = Strings.requireNonBlank(messageId, "messageId");
        return this;
    }

    private MessageBuilder body(String text, String markdown, String fileInfo, MessageArk ark) {
        this.text = text;
        this.markdown = markdown;
        this.fileInfo = fileInfo;
        this.ark = ark;
        return this;
    }

    /** The computed {@code msg_type}: 0 text, 2 markdown, 7 media; null for a card, which has none. */
    public Long msgType() {
        checkBody();
        if (fileInfo != null) {
            return 7L;
        }
        if (markdown != null) {
            return 2L;
        }
        return text != null ? 0L : null;
    }

    public SendC2CMessageRequest toC2C() {
        checkBody();
        requireNoArk("sendC2CMessage");
        SendC2CMessageRequest request = new SendC2CMessageRequest();
        request.msgType = msgType();
        request.content = text;
        request.markdown = markdown == null ? null : markdownBody(markdown);
        request.media = fileInfo == null ? null : mediaBody(fileInfo);
        request.keyboard = keyboard;
        request.msgId = msgId;
        request.eventId = eventId;
        request.msgSeq = msgSeq;
        request.messageReference = reference();
        return request;
    }

    public SendGroupMessageRequest toGroup() {
        checkBody();
        requireNoArk("sendGroupMessage");
        SendGroupMessageRequest request = new SendGroupMessageRequest();
        request.msgType = msgType();
        request.content = text;
        request.markdown = markdown == null ? null : markdownBody(markdown);
        request.media = fileInfo == null ? null : mediaBody(fileInfo);
        request.keyboard = keyboard;
        request.msgId = msgId;
        request.eventId = eventId;
        request.msgSeq = msgSeq;
        request.messageReference = reference();
        return request;
    }

    /** Sub-channel and direct-message form: no {@code msg_type}, and no inline keyboard. */
    public SendChannelMessageRequest toChannel() {
        checkBody();
        if (fileInfo != null) {
            throw new IllegalStateException("a sub-channel message carries no media file_info; the channel"
                    + " endpoint takes a public image url in SendChannelMessageRequest.image");
        }
        if (keyboard != null) {
            throw new IllegalStateException("a sub-channel message has no keyboard field");
        }
        SendChannelMessageRequest request = new SendChannelMessageRequest();
        request.content = text;
        request.markdown = markdown == null ? null : markdownBody(markdown);
        request.ark = ark;
        request.msgId = msgId;
        request.eventId = eventId;
        request.messageReference = reference();
        return request;
    }

    private MessageMarkdown markdownBody(String content) {
        MessageMarkdown markdown = new MessageMarkdown();
        markdown.content = content;
        return markdown;
    }

    private MediaInfo mediaBody(String fileInfo) {
        MediaInfo info = new MediaInfo();
        info.fileInfo = fileInfo;
        return info;
    }

    private MessageReference reference() {
        if (quotedMessageId == null) {
            return null;
        }
        MessageReference reference = new MessageReference();
        reference.messageId = quotedMessageId;
        return reference;
    }

    private void checkBody() {
        if (text == null && markdown == null && fileInfo == null && ark == null) {
            throw new IllegalStateException("nothing to send; call text(), markdown(), media() or ark() first"
                    + " — each one replaces the body chosen before it");
        }
    }

    private void requireNoArk(String operation) {
        if (ark != null) {
            throw new IllegalStateException(operation + " has no ark field; a card is msg_type=3 in a"
                    + " sub-channel, use toChannel()");
        }
    }
}
