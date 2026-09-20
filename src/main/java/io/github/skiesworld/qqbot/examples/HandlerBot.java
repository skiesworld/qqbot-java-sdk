package io.github.skiesworld.qqbot.examples;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.GroupJoinRequestEvent;
import io.github.skiesworld.qqbot.event.InteractionEvent;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.event.QQNoticeEvent;
import io.github.skiesworld.qqbot.handler.BotHandler;
import io.github.skiesworld.qqbot.handler.BotHandlers;
import io.github.skiesworld.qqbot.handler.Check;
import io.github.skiesworld.qqbot.handler.On;
import io.github.skiesworld.qqbot.handler.OnContext;
import io.github.skiesworld.qqbot.handler.Permissions;
import io.github.skiesworld.qqbot.model.SetMemberMuteState;
import io.github.skiesworld.qqbot.model.request.SetGroupMemberMuteRequest;
import io.github.skiesworld.qqbot.websocket.Intent;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * 注解式机器人：handler 类按参数类型接事件，命令按词或正则匹配，门禁写在注解上。
 *
 * <p>事件集合不用写全：参数写 {@link QQMessageEvent} 就是「所有带文本的消息事件」，写
 * {@link GroupJoinRequestEvent} 就只是那一个事件。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... QQ_SUPER_USERS=id1,id2 \
 *       ./gradlew runExample -Pexample=HandlerBot
 * </pre>
 */
@BotHandlers("handler")
public final class HandlerBot implements BotHandler {

    private final Set<String> superUsers = Set.of(Env.required("QQ_SUPER_USERS").split(","));

    public static void main(String[] args) throws Exception {
        QQBotClient bot = Env.client(Intent.GROUP_AND_C2C_EVENT, Intent.GROUP_MEMBER_EVENT,
                Intent.INTERACTION);
        HandlerBot handlers = new HandlerBot();
        bot.handlers().usePrefixes("/", "");        // 群消息本来就必须 @ 机器人，前缀只是可选的更严
        bot.handlers().register(handlers);
        bot.start();
        Env.log("commands: {}", String.join(" | ", bot.handlers().describe()));
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }

    /** 参数决定订阅什么：这里是六个带文本的消息事件。 */
    @On
    public void onAnyMessage(QQMessageEvent msg) {
        Env.log("[{}] {}: {}", msg.scene(), msg.senderId(), msg.content());
    }

    /** 一个方法多个事件；不接信封时参数照旧是 payload。 */
    @On({EventType.FRIEND_ADD, EventType.FRIEND_DEL})
    public void onFriendToggle(QQEvent raw) {
        Env.log("{} from {}", raw.name(), raw.conversationId());
    }

    /** 通知事件里的人由路由表指明：这条报的是「谁退的群」。 */
    @On(EventType.GROUP_MEMBER_REMOVE)
    public void onMemberLeft(QQNoticeEvent notice) {
        notice.subject().ifPresent(member -> Env.log("{} left {}", member, notice.conversationId()));
    }

    /** 加群申请在事件上就能答；不答就一直挂着。 */
    @On
    public void onJoinRequest(GroupJoinRequestEvent request) {
        if (request.verifyMessage() == null || request.verifyMessage().isBlank()) {
            request.deny("请先回答入群问题");
            return;
        }
        request.approve();
        Env.log("admitted {} into {}", request.applicant(), request.groupId());
    }

    /** 按钮点击要先应答再做事，答晚了用户已经走了。 */
    @On
    public void onButton(InteractionEvent interaction) {
        interaction.acknowledge();
        Env.log("interaction {} from {}", interaction.interactionId(), interaction.actor().orElse("?"));
    }

    /** 尚未建模的事件名：拿不到信封，就用原始 d。 */
    @On(name = "GROUP_SOMETHING_NEW")
    public void onFutureEvent(JsonObject body) {
        Env.log("unmodelled event: {}", body);
    }

    // ---- 命令 ----

    @On(command = {"帮助", "help"}, priority = -10, description = "列出命令")
    public void help(OnContext ctx) {
        ctx.reply("/复读 <文本> · /禁言 <member_openid> <分钟> · /状态");
    }

    @On(command = "复读", description = "把参数原样发回")
    public void repeat(OnContext ctx) {
        ctx.reply(ctx.rest());
    }

    @On(command = "禁言 (\\S+) (\\d+)", kind = On.Kind.REGEX,
            requires = {Permissions.Group.class, Permissions.GroupAdmin.class}, description = "群管限时禁言")
    public void mute(OnContext ctx) {
        SetMemberMuteState state = new SetMemberMuteState();
        state.op = "add";
        state.memberOpenid = ctx.groups().get(0);
        state.muteExpireAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusMinutes(Long.parseLong(ctx.groups().get(1))).toString();
        SetGroupMemberMuteRequest request = new SetGroupMemberMuteRequest();
        request.members = List.of(state);
        ctx.api().group().setGroupMemberMute(ctx.message().conversationId(), request);
        ctx.reply("已禁言 " + state.memberOpenid + " " + ctx.groups().get(1) + " 分钟");
    }

    /** 名字指向本类的 {@link Check} 方法，声明顺序即判定顺序。 */
    @On(command = "清档")
    @Check("superUser")
    public void wipe(OnContext ctx) {
        ctx.reply("已清档");
    }

    @On(command = "状态", requires = Permissions.ToMe.class, block = true, description = "只回答被 @ 的那次")
    public void status(OnContext ctx) {
        ctx.reply("在线");
    }

    /** 门禁本体：接了 QQMessageEvent，所以非消息事件在绑定阶段就被拒。 */
    @Check
    boolean superUser(QQMessageEvent msg) {
        return superUsers.contains(msg.senderId());
    }
}
