package io.github.skiesworld.qqbot.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates in front of handlers: who may run, in what order, and what a broken gate declaration says about itself.
 */
class CheckTest {

    private static final String OWNER = "U-OWNER";
    private static final String STRANGER = "U-STRANGER";

    private final List<String> ran = new CopyOnWriteArrayList<>();
    private final AtomicInteger consulted = new AtomicInteger();

    private static QQEvent privateMessage(String sender, String content) {
        return event("C2C_MESSAGE_CREATE", "{\"id\":\"M1\",\"author\":{\"user_openid\":\"" + sender
                + "\",\"username\":\"小明\"},\"content\":\"" + content + "\"}");
    }

    private static QQEvent groupMessage(String role) {
        return event("GROUP_AT_MESSAGE_CREATE", "{\"group_openid\":\"G1\",\"content\":\"ping\",\"author\":"
                + "{\"member_openid\":\"" + OWNER + "\",\"member_role\":\"" + role + "\"}}");
    }

    private static QQEvent event(String name, String json) {
        JsonElement d = Json.parseLenient(json);
        return new QQEvent("ID1", 0, 1L, name, EventType.from(name), d);
    }

    @Test
    void aNamedCheckDecidesWhetherTheHandlerRuns() {
        EventBus bus = new EventBus();
        bus.register(new NamedCheckHandler());
        bus.dispatch(privateMessage(STRANGER, "ping"));
        assertTrue(ran.isEmpty(), "the gate denied it");
        bus.dispatch(privateMessage(OWNER, "ping"));
        assertEquals(List.of("ran"), ran);
    }

    @Test
    void aCheckDeclaresItsOwnArgumentsIndependentlyOfTheHandler() {
        EventBus bus = new EventBus();
        bus.register(new PartialArgsHandler());
        bus.dispatch(privateMessage(STRANGER, "ping"));
        assertTrue(ran.isEmpty());
        bus.dispatch(privateMessage(OWNER, "ping"));
        assertEquals(List.of("ran"), ran, "the check read the author; the handler never mentioned it");
    }

    @Test
    void sceneAndRoleRulesAreReusableTypes() {
        EventBus bus = new EventBus();
        bus.register(new TypedCheckHandler());
        bus.dispatch(privateMessage(OWNER, "ping"));
        assertTrue(ran.isEmpty(), "not a group");
        bus.dispatch(groupMessage("member"));
        assertTrue(ran.isEmpty(), "a plain member may not");
        bus.dispatch(groupMessage("owner"));
        assertEquals(List.of("ran"), ran);
    }

    @Test
    void checksRunInDeclarationOrderAndStopAtTheFirstDenial() {
        EventBus bus = new EventBus();
        new HandlerRegistry(bus).permission(Counted.class, () -> new Counted(consulted))
                .register(new OrderingHandler());

        bus.dispatch(privateMessage(STRANGER, "ping"));
        assertEquals(0, consulted.get(), "the named check denied first, so the type was never consulted");
        assertTrue(ran.isEmpty());

        bus.dispatch(privateMessage(OWNER, "ping"));
        assertEquals(1, consulted.get());
        assertEquals(List.of("ran"), ran);
    }

    @Test
    void aGateThatCannotDecideDenies() {
        EventBus bus = new EventBus();
        bus.register(new ThrowingCheckHandler());
        bus.dispatch(privateMessage(OWNER, "ping"));
        assertTrue(ran.isEmpty(), "a throwing check is a denial, not a pass");
    }

    @Test
    void configuredRulesComeFromTheFactory() {
        try (QQBotClient bot = QQBotClient.create(BotConfig.builder("APP").accessToken("TOKEN").build())) {
            bot.handlers().permission(AllowList.class, () -> new AllowList(Set.of(OWNER)))
                    .register(new ConfiguredCheckHandler());
            bot.events().dispatch(privateMessage(STRANGER, "ping"));
            assertTrue(ran.isEmpty());
            bot.events().dispatch(privateMessage(OWNER, "ping"));
            assertEquals(List.of("ran"), ran);
        }
    }

    @Test
    void aConfiguredRuleWithoutItsFactorySaysSoAtRegistration() {
        EventBus bus = new EventBus();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new ConfiguredCheckHandler()));
        assertTrue(error.getMessage().contains("no public no-arg constructor"), error.getMessage());
        assertTrue(error.getMessage().contains("permission("), error.getMessage());
    }

    @Test
    void aGateGetsNoClientWhenRegisteredOnABareBus() {
        EventBus bus = new EventBus();
        bus.register(new ReportsClientHandler());
        bus.dispatch(privateMessage(OWNER, "ping"));
        assertEquals(List.of("ran"), ran, "ReportsClient only allows a null client, so the bus route reached it");
    }

    @Test
    void brokenGateDeclarationsFailAtRegistration() {
        EventBus bus = new EventBus();

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new MissingCheckHandler()));
        assertTrue(missing.getMessage().contains("no @Check method"), missing.getMessage());

        IllegalArgumentException overloaded = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new OverloadedCheckHandler()));
        assertTrue(overloaded.getMessage().contains("is overloaded"), overloaded.getMessage());

        IllegalArgumentException notBoolean = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new CheckThatIsNotABooleanHandler()));
        assertTrue(notBoolean.getMessage().contains("a check returns boolean"), notBoolean.getMessage());

        IllegalArgumentException routedNonVoid = assertThrows(IllegalArgumentException.class,
                () -> bus.register(new RoutedMethodReturnsAValueHandler()));
        assertTrue(routedNonVoid.getMessage().contains("Make it void"), routedNonVoid.getMessage());
    }

    /** Reads what the handler does not ask for, to show the two signatures are independent. */
    static class SenderOnly {

        @SerializedName("author")
        Author author;
    }

    static class Author {

        @SerializedName("user_openid")
        String userOpenid;
    }

    /** Needs configuration, so it has no no-arg constructor and must come from a factory. */
    public static final class AllowList implements Permission {

        private final Set<String> allowed;

        public AllowList(Set<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            String sender = Permissions.senderId(event);
            return sender != null && allowed.contains(sender);
        }
    }

    /** Counts how often it was consulted, to pin the evaluation order. */
    public static final class Counted implements Permission {

        private final AtomicInteger consulted;

        Counted(AtomicInteger consulted) {
            this.consulted = consulted;
        }

        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            consulted.incrementAndGet();
            return true;
        }
    }

    /** Only passes when the registration had no client to hand out. */
    public static final class ReportsClient implements Permission {

        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return bot == null && Permissions.scene(ReplyTarget.C2C).allows(event, bot);
        }
    }

    @SuppressWarnings("unused")
    class NamedCheckHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check("ownerOnly")
        public void ping(QQEvent raw) {
            ran.add("ran");
        }

        @Check
        boolean ownerOnly(SenderOnly msg) {
            return msg.author != null && OWNER.equals(msg.author.userOpenid);
        }
    }

    @SuppressWarnings("unused")
    class PartialArgsHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check("ownerOnly")
        public void ping() {
            ran.add("ran");
        }

        @Check
        boolean ownerOnly(SenderOnly msg) {
            return msg.author != null && OWNER.equals(msg.author.userOpenid);
        }
    }

    @SuppressWarnings("unused")
    class TypedCheckHandler {

        @BotEvent(EventType.GROUP_AT_MESSAGE_CREATE)
        @Check(type = {Permissions.Group.class, Permissions.GroupOwner.class})
        public void ping(QQEvent raw) {
            ran.add("ran");
        }
    }

    @SuppressWarnings("unused")
    class OrderingHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check(value = "ownerOnly", type = Counted.class)
        public void ping(QQEvent raw) {
            ran.add("ran");
        }

        @Check
        boolean ownerOnly(QQEvent raw) {
            return OWNER.equals(Permissions.senderId(raw));
        }
    }

    @SuppressWarnings("unused")
    class ThrowingCheckHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check("boom")
        public void ping(QQEvent raw) {
            ran.add("ran");
        }

        @Check
        boolean boom(QQEvent raw) {
            throw new IllegalStateException("gate failed on purpose");
        }
    }

    @SuppressWarnings("unused")
    class ConfiguredCheckHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check(type = AllowList.class)
        public void ping(QQEvent raw) {
            ran.add("ran");
        }
    }

    @SuppressWarnings("unused")
    class ReportsClientHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check(type = ReportsClient.class)
        public void ping(QQEvent raw) {
            ran.add("ran");
        }
    }

    @SuppressWarnings("unused")
    class MissingCheckHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check("nobodyWithThisName")
        public void ping(QQEvent raw) {
            ran.add("ran");
        }
    }

    @SuppressWarnings("unused")
    class OverloadedCheckHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check("ownerOnly")
        public void ping(QQEvent raw) {
            ran.add("ran");
        }

        @Check
        boolean ownerOnly(QQEvent raw) {
            return true;
        }

        @Check
        boolean ownerOnly(JsonObject body) {
            return true;
        }
    }

    @SuppressWarnings("unused")
    class CheckThatIsNotABooleanHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        @Check("ownerOnly")
        public void ping(QQEvent raw) {
            ran.add("ran");
        }

        @Check
        void ownerOnly(QQEvent raw) {
        }
    }

    @SuppressWarnings("unused")
    class RoutedMethodReturnsAValueHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public boolean ping(QQEvent raw) {
            ran.add("ran");
            return true;
        }
    }
}
