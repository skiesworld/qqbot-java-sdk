package io.github.skiesworld.qqbot.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The counters behind {@code msg_seq}: one per original message, and bounded. */
class ReplySequenceTest {

    @Test
    void countsUpFromOnePerMessage() {
        ReplySequence sequences = new ReplySequence();
        assertEquals(1L, sequences.next("M1"));
        assertEquals(2L, sequences.next("M1"));
        assertEquals(1L, sequences.next("M2"), "another message has its own counter");
        assertEquals(3L, sequences.next("M1"));
        assertEquals(2, sequences.size());
    }

    @Test
    void forgetsTheLeastRecentMessageOnceFull() {
        ReplySequence sequences = new ReplySequence(2);
        assertEquals(1L, sequences.next("M1"));
        assertEquals(1L, sequences.next("M2"));
        assertEquals(2L, sequences.next("M1"), "M1 is now the one worth remembering");
        assertEquals(1L, sequences.next("M3"), "the table was full, so M2 is the one dropped");
        assertEquals(2, sequences.size());
        assertEquals(1L, sequences.next("M2"), "a forgotten message starts again, and a fresh number is all"
                + " the reply window needs to have seen");
        assertEquals(1L, sequences.next("M1"), "M1 fell out too while M2 and M3 were being replied to");
    }

    @Test
    void refusesAUselessCapacity() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new ReplySequence(0)).getMessage()
                .contains("capacity"));
    }
}
