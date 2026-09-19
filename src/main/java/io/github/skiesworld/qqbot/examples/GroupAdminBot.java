package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.model.GroupJoinRequest;
import io.github.skiesworld.qqbot.event.model.GroupMessageCreate;
import io.github.skiesworld.qqbot.model.SetMemberMuteState;
import io.github.skiesworld.qqbot.model.request.ApproveGroupJoinRequestRequest;
import io.github.skiesworld.qqbot.model.request.SendGroupMessageRequest;
import io.github.skiesworld.qqbot.model.request.SetGroupMemberMuteRequest;
import io.github.skiesworld.qqbot.websocket.Intent;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 群管理演示：机器人被拉入群时打招呼、审批入群申请、限时禁言与撤回。
 * 审批与禁言要求机器人具备群管理员身份，单次禁言设置不超过 20 人，最长 30 天。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... ./gradlew runExample -Pexample=GroupAdminBot
 * </pre>
 */
public final class GroupAdminBot {

    private GroupAdminBot() {
    }

    public static void main(String[] args) throws Exception {
        QQBotClient bot = Env.client(Intent.GROUP_AND_C2C_EVENT, Intent.GROUP_MEMBER_EVENT);

        bot.events().on(EventType.GROUP_ADD_ROBOT, event -> {
            SendGroupMessageRequest hello = new SendGroupMessageRequest();
            hello.msgType = 0L;
            hello.content = "大家好，我是新来的机器人";
            bot.api().group().sendGroupMessage(event.rawObject().get("group_openid").getAsString(), hello);
        });

        bot.events().on(EventType.GROUP_JOIN_REQUEST, GroupJoinRequest.class, request -> {
            ApproveGroupJoinRequestRequest approve = new ApproveGroupJoinRequestRequest();
            approve.op = "approve";
            approve.joinRequestId = request.joinRequestId;
            bot.api().group().approveGroupJoinRequest(request.groupOpenid, request.memberOpenid, approve);
            Env.log("approved %s into %s", request.memberOpenid, request.groupOpenid);
        });

        // #admin-mute <member_openid>  /  #admin-requests  /  #admin-recall <message_id>
        bot.events().on(EventType.GROUP_MESSAGE_CREATE, GroupMessageCreate.class, message -> {
            if (message.content == null || !message.content.startsWith("#admin")) {
                return;
            }
            String[] parts = message.content.trim().split("\\s+");
            switch (parts[0]) {
                case "#admin-mute" -> mute(bot, message, parts);
                case "#admin-requests" -> {
                    var page = bot.api().group().listGroupJoinRequests(message.groupOpenid, null, 20L);
                    Env.log("%d pending join request(s), cursor=%s", page.list.size(), page.nextCursor);
                }
                case "#admin-recall" -> bot.api().group().recallGroupMessage(message.groupOpenid, message.id);
                default -> Env.log("unknown admin command %s", parts[0]);
            }
        });

        bot.connectAndAwaitReady(20_000);
        Env.log("group admin bot online");
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }

    private static void mute(QQBotClient bot, GroupMessageCreate message, String[] parts) {
        if (parts.length < 2) {
            Env.log("usage: #admin-mute <member_openid>");
            return;
        }
        SetMemberMuteState state = new SetMemberMuteState();
        state.op = "add";
        state.memberOpenid = parts[1];
        state.muteExpireAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10).toString();
        SetGroupMemberMuteRequest request = new SetGroupMemberMuteRequest();
        request.members = List.of(state);
        bot.api().group().setGroupMemberMute(message.groupOpenid, request);
        Env.log("muted %s in %s until %s", state.memberOpenid, message.groupOpenid, state.muteExpireAt);
    }
}
