package io.github.skiesworld.qqbot.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.api.Api;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.model.C2CMessageCreate;
import io.github.skiesworld.qqbot.model.Message;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Annotated handlers: what binds, what fails at registration, and what a bad dispatch does to the bus. */
class HandlerRegistryTest {

    private static final List<String> DISCOVERED = new CopyOnWriteArrayList<>();

    private final List<String> hits = new CopyOnWriteArrayList<>();

    private static QQEvent event(String name, String json) {
        // deliberately not coerced to an object: a payload that is not one must skip, not blow up
        JsonElement d = json == null ? null : Json.parseLenient(json);
        return new QQEvent("ID1", 0, 1L, name, EventType.from(name), d);
    }

    private static QQBotClient client() {
        return QQBotClient.create(BotConfig.builder("APP").accessToken("TOKEN").build());
    }

    @Test
    void bindsPayloadAndEnvelopeTogether() {
        EventBus bus = new EventBus();
        bus.register(new PayloadHandlers());
        bus.dispatch(event("C2C_MESSAGE_CREATE",
                "{\"id\":\"M1\",\"content\":\"你好\",\"author\":{\"user_openid\":\"U1\"}}"));
        assertEquals(List.of("envelope:C2C_MESSAGE_CREATE/ID1", "payload:你好"), hits.stream().sorted().toList(),
                "both methods of the handler ran, whichever order the class declares them in");
    }

    @Test
    void oneMethodCanCoverSeveralTypesAndRawNames() {
        EventBus bus = new EventBus();
        bus.register(new MultiRouteHandlers());
        bus.dispatch(event("FRIEND_ADD", "{\"openid\":\"U\"}"));
        bus.dispatch(event("FRIEND_DEL", "{\"openid\":\"U\"}"));
        bus.dispatch(event("AUDIO_START", "{\"channel_id\":\"C\"}"));
        bus.dispatch(event("FRIEND_ADD_UNRELATED", "{}"));
        assertEquals(List.of("friend", "friend", "byName:AUDIO_START"), hits);
    }

    @Test
    void bindsTheClientAndApiOnlyWhenARegistryHasOne() {
        try (QQBotClient bot = client()) {
            bot.handlers().register(new ClientHandlers());
            bot.events().dispatch(event("C2C_MESSAGE_CREATE", "{\"content\":\"x\"}"));
            assertEquals(List.of("api+client"), hits);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> bot.events().register(new ClientHandlers()));
            assertTrue(error.getMessage().contains("bot.handlers()"), error.getMessage());
        }
    }

    @Test
    void aThrowingHandlerStaysOutOfTheBusesWay() {
        EventBus bus = new EventBus();
        bus.register(new ThrowingHandlers());
        bus.dispatch(event("C2C_MESSAGE_CREATE", "{\"content\":\"x\"}"));
        assertEquals(List.of("after-the-throw"), hits);
    }

    @Test
    void dispatchesWithoutABindablePayloadSkipTheHandlerInsteadOfFailing() {
        EventBus bus = new EventBus();
        bus.register(new PayloadHandlers());
        bus.dispatch(event("C2C_MESSAGE_CREATE", null));
        bus.dispatch(event("C2C_MESSAGE_CREATE", "\"a string, not an object\""));
        assertEquals(2, hits.size(), "the envelope-bound method still runs, the payload-bound one is skipped");
        assertTrue(hits.contains("envelope:C2C_MESSAGE_CREATE/ID1"), hits.toString());
    }

    @Test
    void closingTheRegistrationStopsEveryRouteOfTheHandler() {
        EventBus bus = new EventBus();
        EventBus.Subscription subscription = bus.register(new MultiRouteHandlers());
        subscription.close();
        bus.dispatch(event("FRIEND_ADD", "{}"));
        bus.dispatch(event("AUDIO_START", "{}"));
        assertTrue(hits.isEmpty(), hits.toString());
    }

    @Test
    void inheritedRoutesRunOnceAndAgainstTheSubclassInstance() {
        EventBus bus = new EventBus();
        bus.register(new SubHandlers());
        bus.dispatch(event("GUILD_CREATE", "{\"id\":\"G1\"}"));
        assertEquals(List.of("base:GUILD_CREATE"), hits);
    }

    @Test
    void rejectsMethodsThatCouldNeverBeCalled() {
        EventBus bus = new EventBus();
        IllegalArgumentException noEvent = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new NoEventHandlers()));
        assertTrue(noEvent.getMessage().contains("listens to no event"), noEvent.getMessage());

        IllegalArgumentException primitive = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new PrimitiveParamHandlers()));
        assertTrue(primitive.getMessage().contains("cannot be bound"), primitive.getMessage());

        IllegalArgumentException nothing = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new Object()));
        assertTrue(nothing.getMessage().contains("nothing to register"), nothing.getMessage());
    }

    @Test
    void bindsAMessagePayloadOfAnEventModelledElsewhere() {
        EventBus bus = new EventBus();
        bus.register(new GuildMessageHandlers());
        bus.dispatch(event("AT_MESSAGE_CREATE", "{\"id\":\"M2\",\"content\":\"<@!1> hi\",\"channel_id\":\"C\"}"));
        assertEquals(List.of("message:M2"), hits);
    }

    @Test
    void routesPreComputedByACallerUseTheSameBindings() {
        EventBus bus = new EventBus();
        PayloadHandlers handler = new PayloadHandlers();
        List<HandlerRegistry.Route> routes = HandlerRegistry.eventRoutes(handler);
        assertEquals(2, routes.size());
        HandlerRegistry.Route payload = routes.stream()
                .filter(r -> r.method().getName().equals("payload"))
                .findFirst().orElseThrow();
        new HandlerRegistry(bus).register(handler, List.of(payload), HandlerRegistry.RouteSpec.plain());
        bus.dispatch(event("C2C_MESSAGE_CREATE", "{\"content\":\"one route\"}"));
        assertEquals(List.of("payload:one route"), hits);
    }

    @Test
    void refusesRoutesFromAnotherClass() {
        EventBus bus = new EventBus();
        HandlerRegistry.Route foreign = HandlerRegistry.eventRoutes(new PayloadHandlers()).get(0);
        assertThrows(IllegalArgumentException.class,
                () -> new HandlerRegistry(bus).register(new GuildMessageHandlers(), List.of(foreign),
                        HandlerRegistry.RouteSpec.plain()));
    }

    @Test
    void findsNothingWithoutAManifest(@TempDir Path dir) throws IOException {
        EventBus bus = new EventBus();
        assertEquals(0, new HandlerRegistry(bus).registerDiscovered(
                new URLClassLoader(new URL[]{dir.toUri().toURL()}, getClass().getClassLoader())));
    }

    @Test
    void registersEveryHandlerNamedInTheServiceManifest(@TempDir Path dir) throws IOException {
        Path services = Files.createDirectories(dir.resolve("META-INF/services"));
        Files.writeString(services.resolve(BotHandler.class.getName()), Discovered.class.getName() + "\n");
        EventBus bus = new EventBus();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                getClass().getClassLoader())) {
            assertEquals(1, new HandlerRegistry(bus).registerDiscovered(loader));
            bus.dispatch(event("C2C_MESSAGE_CREATE", "{\"content\":\"found\"}"));
        }
        assertEquals(List.of("found"), DISCOVERED);
    }

    @SuppressWarnings("unused")
    public static class Discovered implements BotHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void onC2c(C2CMessageCreate msg) {
            DISCOVERED.add(msg.content);
        }
    }

    @SuppressWarnings("unused")
    class PayloadHandlers {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void payload(C2CMessageCreate msg) {
            hits.add("payload:" + msg.content);
        }

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void envelope(QQEvent event) {
            hits.add("envelope:" + event.name() + "/" + event.id());
            assertInstanceOf(JsonObject.class, event.raw());
        }
    }

    @SuppressWarnings("unused")
    class MultiRouteHandlers {

        @BotEvent({EventType.FRIEND_ADD, EventType.FRIEND_DEL})
        public void friends(QQEvent event) {
            hits.add("friend");
        }

        @BotEvent(name = "AUDIO_START")
        public void rawName(JsonObject d, QQEvent event) {
            hits.add("byName:" + event.name());
            assertTrue(d.has("channel_id"), d.toString());
        }
    }

    @SuppressWarnings("unused")
    class ClientHandlers {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void both(Api api, QQBotClient bot, C2CMessageCreate msg) {
            assertSame(api, bot.api());
            hits.add("api+client");
        }
    }

    @SuppressWarnings("unused")
    class ThrowingHandlers {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void boom(C2CMessageCreate msg) {
            throw new IllegalStateException("handler failed on purpose");
        }

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void survivor(QQEvent event) {
            hits.add("after-the-throw");
        }
    }

    class BaseHandlers {

        @BotEvent(EventType.GUILD_CREATE)
        public void guild(QQEvent event) {
            hits.add("base:" + event.name());
        }
    }

    class SubHandlers extends BaseHandlers {
    }

    @SuppressWarnings("unused")
    class NoEventHandlers {

        @BotEvent
        public void nowhere(QQEvent event) {
            hits.add("never");
        }
    }

    @SuppressWarnings("unused")
    class PrimitiveParamHandlers {

        @BotEvent(EventType.GUILD_CREATE)
        public void needsInt(int seq) {
            hits.add("never");
        }
    }

    @SuppressWarnings("unused")
    class GuildMessageHandlers {

        @BotEvent(EventType.AT_MESSAGE_CREATE)
        public void message(Message message) {
            hits.add("message:" + message.id);
        }
    }
}
