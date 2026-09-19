package io.github.skiesworld.qqbot.message;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hands out {@code msg_seq} values, one increment per reply to the same original message.
 *
 * <p>The platform refuses a repeat of the same {@code msg_id} + {@code msg_seq} pair, so every passive reply
 * needs a fresh number while staying inside the reply window (60 minutes in a private chat, 5 in a group). The
 * bookkeeping is bounded: sequence counters for the most recent {@code capacity} messages are kept, older ones
 * fall out — a counter that got evicted simply restarts above any number the window could still remember.
 */
public final class ReplySequence {

    private final Map<String, Long> issued;
    private final int capacity;

    public ReplySequence() {
        this(1024);
    }

    public ReplySequence(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        // access order, so a message that is still being replied to is the last thing to be forgotten
        this.issued = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** The next sequence for {@code messageId}, starting at 1. */
    public synchronized long next(String messageId) {
        long next = issued.getOrDefault(messageId, 0L) + 1;
        issued.put(messageId, next);
        if (issued.size() > capacity) {
            Iterator<String> oldest = issued.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        return next;
    }

    public synchronized int size() {
        return issued.size();
    }
}
