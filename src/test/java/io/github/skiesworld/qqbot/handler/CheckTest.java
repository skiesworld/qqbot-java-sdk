package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventEnvelopes;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.event.QQNoticeEvent;
import io.github.skiesworld.qqbot.message.ReplyTarget;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gates: who may trigger a route, named locally or shared as a rule type, and what a gate that cannot decide
 * is worth.
 */
class CheckTest {

    private final EventBus bus = new EventBus();
    private final QQBotClient bot = QQBotClient.create(BotConfig.builder("APP").clientSecret("s").build());
    private final HandlerRegistry handlers = new HandlerRegistry(bus, bot);

    private void groupMessage(String memberOpenid, String role, String content) {
        bus.dispatch(EventEnvelopes.of("M1", 0, 1L, "GROUP_AT_MESSAGE_CREATE", Json.parseLenient(
                "{\"group_openid\":\"G1\",\"content\":\"" + content + "\",\"author\":{\"member_openid\":\""
                        + memberOpenid + "\",\"member_role\":\"" + role + "\"}}"), bot.events().outbound()));
    }

    @Test
    void aNamedCheckDecidesCallByCall() {
        Gated handler = new Gated();
        handlers.register(handler);

        groupMessage("OWNER", "owner", "清档");
        groupMessage("STRANGER", "member", "清档");

        assertEquals(List.of("OWNER"), handler.wiped);
    }

    @Test
    void severalChecksRunInDeclarationOrderAndStopAtTheFirstDenial() {
        Gated handler = new Gated();
        handlers.register(handler);
        handler.firstPasses = false;

        groupMessage("OWNER", "owner", "清档");

        assertEquals(1, handler.firstCalls);
        assertEquals(0, handler.secondCalls, "the first said no, so the second was never asked");
    }

    @Test
    void aCheckThatThrowsDeniesInsteadOfLeavingTheActionUngated() {
        Throwing handler = new Throwing();
        handlers.register(handler);

        groupMessage("OWNER", "owner", "清档");

        assertEquals(0, handler.dangerous);
    }

    @Test
    void aCheckThatCannotReadTheDispatchDeniesIt() {
        GroupOnly handler = new GroupOnly();
        handlers.register(handler);

        bus.dispatch(EventEnvelopes.of("E1", 0, 1L, "FRIEND_ADD",
                Json.parseLenient("{\"openid\":\"U1\"}"), bot.events().outbound()));

        assertEquals(0, handler.ran, "a private chat has no group role to honour");
    }

    @Test
    void builtInRulesNameTheScenesAndRolesTheyMean() {
        BuiltIns handler = new BuiltIns();
        handlers.register(handler);

        groupMessage("ADMIN", "admin", "管");
        groupMessage("MEMBER", "member", "管");
        bus.dispatch(EventEnvelopes.of("E1", 0, 1L, "C2C_MESSAGE_CREATE",
                Json.parseLenient("{\"content\":\"管\",\"user_openid\":\"U1\","
                        + "\"author\":{\"user_openid\":\"U1\"}}"), bot.events().outbound()));

        assertEquals(List.of("ADMIN"), handler.adminRuns);
        assertEquals(List.of("G1", "G1", "U1"), handler.anyC2cOrGroup);
    }

    @Test
    void toMeAnswersTheAddressedEventsAndAnAtBotMentionInGroupWideMode() {
        Addressed handler = new Addressed();
        handlers.register(handler);

        groupMessage("M1", "member", "在吗");
        dispatch("GROUP_MESSAGE_CREATE", "{\"group_openid\":\"G1\",\"content\":\"闲聊\","
                + "\"author\":{\"member_openid\":\"M1\"}}");
        dispatch("GROUP_MESSAGE_CREATE", "{\"group_openid\":\"G1\",\"content\":\"@别人 你好\","
                + "\"author\":{\"member_openid\":\"M1\"},\"mentions\":[{\"user_openid\":\"H1\",\"bot\":false}]}");
        dispatch("GROUP_MESSAGE_CREATE", "{\"group_openid\":\"G1\",\"content\":\"@机器人 你好\","
                + "\"author\":{\"member_openid\":\"M1\"},\"mentions\":[{\"user_openid\":\"B1\",\"bot\":true}]}");

        assertEquals(List.of("在吗", "全量:@机器人 你好"), handler.heard);
    }

    private void dispatch(String name, String payload) {
        bus.dispatch(EventEnvelopes.of("E1", 0, 1L, name, Json.parseLenient(payload), bot.events().outbound()));
    }

    @Test
    void aConfiguredRuleIsNamedByTypeOnceItsFactoryIsRegistered() {
        handlers.permission(SuperUsers.class, () -> new SuperUsers(List.of("BOSS")));
        Configured handler = new Configured();
        handlers.register(handler);

        groupMessage("BOSS", "member", "特权");
        groupMessage("NOBODY", "owner", "特权");

        assertEquals(List.of("BOSS"), handler.granted);
    }

    @Test
    void anUnknownCheckNameIsAStartupFailure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handlers.register(new UnknownName()));
        assertTrue(error.getMessage().contains("no @Check method"), error.getMessage());
    }

    @Test
    void aCheckMustReturnBoolean() {
        assertThrows(IllegalArgumentException.class, () -> handlers.register(new CheckNotBoolean()));
    }

    @Test
    void anOverloadedCheckNameWouldLeaveItAmbiguous() {
        assertThrows(IllegalArgumentException.class, () -> handlers.register(new OverloadedCheck()));
    }

    @Test
    void aRuleTypeWithNoWayToBuildItIsReportedAtRegistration() {
        assertThrows(IllegalArgumentException.class, () -> handlers.register(new UnbuildableRule()));
    }

    @Test
    void senderInComparesThePersonTheEnvelopeNames() {
        Permission rule = Permissions.senderIn(List.of("U1"));
        QQEvent message = EventEnvelopes.of("E1", 0, 1L, "C2C_MESSAGE_CREATE",
                Json.parseLenient("{\"content\":\"x\",\"author\":{\"user_openid\":\"U1\"}}"), null);
        QQEvent notice = EventEnvelopes.of("E1", 0, 1L, "GROUP_ADD_ROBOT",
                Json.parseLenient("{\"group_openid\":\"G1\",\"op_member_openid\":\"U1\"}"), null);
        QQEvent nobody = EventEnvelopes.of("E1", 0, 1L, "CHANNEL_CREATE",
                Json.parseLenient("{\"guild_id\":\"456\",\"owner_id\":\"U1\"}"), null);

        assertTrue(rule.allows(message, bot));
        assertTrue(rule.allows(notice, bot), "a notice names its actor by role, not by key guesswork");
        assertFalse(rule.allows(nobody, bot), "owner_id is a guild id and is not offered as a person");
    }

    @Test
    void theSceneRulesReadTheConversationTheEventCameFrom() {
        QQEvent group = EventEnvelopes.of("E1", 0, 1L, "GROUP_MESSAGE_CREATE",
                Json.parseLenient("{\"group_openid\":\"G1\"}"), null);

        assertEquals(ReplyTarget.GROUP, group.scene());
        assertTrue(new Permissions.Group().allows(group, bot));
        assertTrue(Permissions.scene(ReplyTarget.GROUP).allows(group, bot));
    }

    static class Gated {

        final List<String> wiped = new java.util.ArrayList<>();
        boolean firstPasses = true;
        int firstCalls;
        int secondCalls;

        @On(command = "清档")
        @Check({"first", "second"})
        public void wipe(OnContext ctx) {
            wiped.add(ctx.message().senderId());
        }

        @Check
        boolean first(QQMessageEvent msg) {
            firstCalls++;
            return firstPasses;
        }

        @Check
        boolean second(QQMessageEvent msg) {
            secondCalls++;
            return "owner".equals(msg.author().memberRole);
        }
    }

    static class Throwing {

        int dangerous;

        @On(command = "清档")
        @Check("boom")
        public void wipe(OnContext ctx) {
            dangerous++;
        }

        @Check
        boolean boom(QQEvent event) {
            throw new IllegalStateException("the rule could not load its list");
        }
    }

    static class GroupOnly {

        int ran;

        @On(EventType.FRIEND_ADD)
        @Check("admin")
        public void onFriend(QQEvent event) {
            ran++;
        }

        @Check
        boolean admin(QQMessageEvent msg) {
            return "admin".equals(msg.author().memberRole);
        }
    }

    static class BuiltIns {

        final List<String> adminRuns = new java.util.ArrayList<>();
        final List<String> anyC2cOrGroup = new java.util.ArrayList<>();

        @On(command = "管", requires = {Permissions.Group.class, Permissions.GroupAdmin.class})
        public void adminOnly(OnContext ctx) {
            adminRuns.add(ctx.message().senderId());
        }

        @On(EventType.GROUP_AT_MESSAGE_CREATE)
        public void group(QQMessageEvent msg) {
            anyC2cOrGroup.add(msg.conversationId());
        }

        @On(EventType.C2C_MESSAGE_CREATE)
        public void c2c(QQMessageEvent msg) {
            anyC2cOrGroup.add(msg.conversationId());
        }
    }

    static class Addressed {

        final List<String> heard = new java.util.ArrayList<>();

        @On(EventType.GROUP_AT_MESSAGE_CREATE)
        @Check(type = Permissions.ToMe.class)
        public void on(QQMessageEvent msg) {
            heard.add(msg.content());
        }

        @On(EventType.GROUP_MESSAGE_CREATE)
        @Check(type = Permissions.ToMe.class)
        public void onGroupWide(QQMessageEvent msg) {
            heard.add("全量:" + msg.content());
        }
    }

    static class Configured {

        final List<String> granted = new java.util.ArrayList<>();

        @On(command = "特权", requires = SuperUsers.class)
        public void on(OnContext ctx) {
            granted.add(ctx.message().senderId());
        }
    }

    /** A rule that asks for what it reads, instead of taking the whole dispatch and digging in it. */
    public static class SuperUsers implements Permission {

        private final List<String> ids;

        public SuperUsers() {
            this(List.of());
        }

        SuperUsers(List<String> ids) {
            this.ids = List.copyOf(ids);
        }

        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            throw new AssertionError("the bound check below answers instead");
        }

        public boolean check(QQMessageEvent msg) {
            return ids.contains(msg.senderId());
        }
    }

    static class UnknownName {

        @On(EventType.C2C_MESSAGE_CREATE)
        @Check("nobodyProvidesThis")
        public void on(QQEvent event) {
        }
    }

    static class CheckNotBoolean {

        @On(EventType.C2C_MESSAGE_CREATE)
        @Check("notBoolean")
        public void on(QQEvent event) {
        }

        @Check
        String notBoolean(QQEvent event) {
            return "no";
        }
    }

    static class OverloadedCheck {

        @On(EventType.C2C_MESSAGE_CREATE)
        @Check("twice")
        public void on(QQEvent event) {
        }

        @Check
        boolean twice(QQEvent event) {
            return true;
        }

        @Check
        boolean twice(QQMessageEvent msg) {
            return true;
        }
    }

    static class UnbuildableRule {

        @On(EventType.C2C_MESSAGE_CREATE)
        @Check(type = NeedsConfig.class)
        public void on(QQEvent event) {
        }
    }

    public static class NeedsConfig implements Permission {

        private final String only;

        public NeedsConfig(String only) {
            this.only = only;
        }

        @Override
        public boolean allows(QQEvent event, QQBotClient bot) {
            return event.name().equals(only);
        }
    }
}
