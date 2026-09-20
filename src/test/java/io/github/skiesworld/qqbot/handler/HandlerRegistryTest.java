package io.github.skiesworld.qqbot.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventEnvelopes;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.GroupJoinRequestEvent;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.event.QQNoticeEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a method's {@link On} annotation and its parameter types together decide: which dispatches reach it, what
 * the arguments are, and which mistakes stop the bot from starting.
 */
class HandlerRegistryTest {

    private final EventBus bus = new EventBus();
    private final QQBotClient bot = QQBotClient.create(BotConfig.builder("APP").clientSecret("s").build());
    private final HandlerRegistry handlers = new HandlerRegistry(bus, bot);

    private void dispatch(String name, String payload) {
        bus.dispatch(EventEnvelopes.of("E1", 0, 1L, name,
                io.github.skiesworld.qqbot.util.Json.parseLenient(payload), bot.events().outbound()));
    }

    private static final String MESSAGE = "{\"id\":\"M1\",\"content\":\"hi\",\"user_openid\":\"U1\","
            + "\"author\":{\"user_openid\":\"U1\"},\"message_type\":0}";

    @Test
    void anEnvelopeParameterIsTheWholeClassOfEvents() {
        Messages handler = new Messages();
        handlers.register(handler);

        dispatch("C2C_MESSAGE_CREATE", MESSAGE);
        dispatch("GROUP_MESSAGE_CREATE", "{\"group_openid\":\"G1\",\"content\":\"yo\","
                + "\"author\":{\"member_openid\":\"M1\"}}");
        dispatch("FRIEND_ADD", "{\"openid\":\"U1\"}");

        assertEquals(2, handler.messages.size());
        assertEquals("hi", handler.messages.get(0));
        assertEquals(1, handler.notices.size(), "the notice route only hears the notice events");
    }

    @Test
    void aConcreteEnvelopeNarrowsToOneEventAndAPayloadTypeFillsTheRest() {
        Requests handler = new Requests();
        handlers.register(handler);

        dispatch("GROUP_JOIN_REQUEST", "{\"group_openid\":\"G1\",\"join_request_id\":\"R1\","
                + "\"member_openid\":\"M1\",\"username\":\"张三\"}");
        dispatch("C2C_MESSAGE_CREATE", MESSAGE);

        assertEquals(List.of("R1/M1"), handler.answers);
        assertEquals(1, handler.others, "the other route on the same bus still heard its own event");
    }

    @Test
    void aBareQqEventParameterHearsEverything() {
        Everything handler = new Everything();
        handlers.register(handler);

        dispatch("C2C_MESSAGE_CREATE", MESSAGE);
        dispatch("FRIEND_DEL", "{\"openid\":\"U1\"}");
        dispatch("GROUP_SOMETHING_NEW", "{}");

        assertEquals(3, handler.names.size());
        assertTrue(handler.names.contains("GROUP_SOMETHING_NEW"), "unmodelled names still arrive");
    }

    @Test
    void explicitEventsNarrowWhatTheParameterWouldHaveCovered() {
        OnlyGroup handler = new OnlyGroup();
        handlers.register(handler);

        dispatch("GROUP_AT_MESSAGE_CREATE", "{\"group_openid\":\"G1\",\"content\":\"x\","
                + "\"author\":{\"member_openid\":\"M1\"}}");
        dispatch("C2C_MESSAGE_CREATE", MESSAGE);

        assertEquals(List.of("G1"), handler.seen);
    }

    @Test
    void bindingFollowsTheDeclaredTypeOfEveryParameter() {
        Mixed handler = new Mixed();
        handlers.register(handler);

        dispatch("C2C_MESSAGE_CREATE", MESSAGE);

        assertEquals(EventType.C2C_MESSAGE_CREATE, handler.type);
        assertNotNull(handler.raw);
        assertEquals("hi", handler.body.get("content").getAsString());
        assertEquals("hi", handler.element.getAsJsonObject().get("content").getAsString());
        assertNotNull(handler.api);
        assertNotNull(handler.client);
        assertEquals("U1", handler.sender);
    }

    @Test
    void apayloadThatWillNotBindSkipsTheCallWithoutTouchingTheChain() {
        WrongPayload handler = new WrongPayload();
        handlers.register(handler);

        dispatch("C2C_MESSAGE_CREATE", "{\"content\":\"hi\",\"author\":{\"user_openid\":\"U1\"}}");

        assertEquals(0, handler.calls, "that event carries a C2CMessageCreate, not a GroupMessageCreate");
    }

    @Test
    void aCommandRouteStillTakesTheEnvelopeItAskedFor() {
        WithBoth handler = new WithBoth();
        handlers.register(handler);

        dispatch("C2C_MESSAGE_CREATE", MESSAGE);

        assertEquals(1, handler.contextRuns);
        assertEquals(1, handler.messageRuns);
        assertEquals("hi", handler.text);
    }

    @Test
    void registeringAClassWithNothingAnnotatedIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> handlers.register(new Empty()));
    }

    @Test
    void aMethodThatListensToNothingAndAnswersNothingCannotRun() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new NoRoute()));
        assertTrue(error.getMessage().contains("listens to no event"), error.getMessage());
    }

    @Test
    void aParameterNoRuleFillsIsRejectedAtRegistration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new PrimitiveParam()));
        assertTrue(error.getMessage().contains("cannot be bound"), error.getMessage());
    }

    @Test
    void anEnvelopeThatTheListenedEventsNeverBuildIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new MismatchedEnvelope()));
        assertTrue(error.getMessage().contains("could never run"), error.getMessage());
    }

    @Test
    void aRawEventNameCannotCarryAnEnvelopeNobodyBuildsForIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new NameWithEnvelope()));
        assertTrue(error.getMessage().contains("unmodelled events"), error.getMessage());
    }

    @Test
    void aRouteThatReturnsSomethingWouldDiscardIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new ReturnsValue()));
        assertTrue(error.getMessage().contains("@Check"), error.getMessage());
    }

    @Test
    void anOnContextWithoutACommandHasNothingToReport() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new ContextWithoutCommand()));
        assertTrue(error.getMessage().contains("declares no command"), error.getMessage());
    }

    @Test
    void closingTheRegistrationTakesTheRoutesAway() {
        Messages handler = new Messages();
        EventBus.Subscription subscription = handlers.register(handler);

        subscription.close();
        dispatch("C2C_MESSAGE_CREATE", MESSAGE);
        assertEquals(0, handler.messages.size());
    }

    @Test
    void anApiParameterNeedsTheClientAndSaysSo() {
        HandlerRegistry bare = new HandlerRegistry(new EventBus());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> bare.register(new Mixed()));
        assertTrue(error.getMessage().contains("needs a client"), error.getMessage());
    }

    class Messages {

        final List<String> messages = new java.util.ArrayList<>();
        final List<String> notices = new java.util.ArrayList<>();

        @On
        public void onMessage(QQMessageEvent msg) {
            messages.add(msg.content());
        }

        @On
        public void onNotice(QQNoticeEvent notice) {
            notices.add(notice.name());
        }
    }

    class Requests {

        final List<String> answers = new java.util.ArrayList<>();
        int others;

        @On
        public void onJoin(GroupJoinRequestEvent event, io.github.skiesworld.qqbot.event.model.GroupJoinRequest p) {
            answers.add(p.joinRequestId + '/' + event.applicant());
        }

        @On(EventType.C2C_MESSAGE_CREATE)
        public void onMessage(QQEvent event) {
            others++;
        }
    }

    class Everything {

        final List<String> names = new java.util.ArrayList<>();

        @On
        public void onAny(QQEvent event) {
            names.add(event.name());
        }
    }

    class OnlyGroup {

        final List<String> seen = new java.util.ArrayList<>();

        @On(EventType.GROUP_AT_MESSAGE_CREATE)
        public void onGroup(QQMessageEvent msg) {
            seen.add(msg.conversationId());
        }
    }

    class Mixed {

        EventType type;
        QQEvent raw;
        JsonObject body;
        JsonElement element;
        io.github.skiesworld.qqbot.api.Api api;
        QQBotClient client;
        String sender;

        @On(EventType.C2C_MESSAGE_CREATE)
        public void onAll(EventType type, QQEvent raw, JsonObject body, JsonElement element,
                io.github.skiesworld.qqbot.api.Api api, QQBotClient client, QQMessageEvent msg) {
            this.type = type;
            this.raw = raw;
            this.body = body;
            this.element = element;
            this.api = api;
            this.client = client;
            this.sender = msg.senderId();
        }
    }

    class WrongPayload {

        int calls;

        @On(EventType.C2C_MESSAGE_CREATE)
        public void on(io.github.skiesworld.qqbot.event.model.GroupMessageCreate msg) {
            calls++;
        }
    }

    class WithBoth {

        int contextRuns;
        int messageRuns;
        String text;

        @On(command = "hi")
        public void onCommand(OnContext ctx) {
            contextRuns++;
            text = ctx.text();
        }

        @On(EventType.C2C_MESSAGE_CREATE)
        public void onMessage(QQMessageEvent msg) {
            messageRuns++;
        }
    }

    class Empty {

        public void plain(QQEvent event) {
        }
    }

    class NoRoute {

        @On
        public void nothing(String ignored) {
        }
    }

    class PrimitiveParam {

        @On(EventType.C2C_MESSAGE_CREATE)
        public void on(int count) {
        }
    }

    class MismatchedEnvelope {

        @On(EventType.FRIEND_ADD)
        public void on(QQMessageEvent msg) {
        }
    }

    class NameWithEnvelope {

        @On(name = "GROUP_SOMETHING_NEW")
        public void on(QQNoticeEvent notice) {
        }
    }

    class ReturnsValue {

        @On(EventType.C2C_MESSAGE_CREATE)
        public int on(QQEvent event) {
            return 0;
        }
    }

    class ContextWithoutCommand {

        @On(EventType.C2C_MESSAGE_CREATE)
        public void on(OnContext ctx) {
        }
    }
}
